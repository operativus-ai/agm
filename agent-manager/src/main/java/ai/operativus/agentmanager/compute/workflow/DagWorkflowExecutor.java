package ai.operativus.agentmanager.compute.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.AgentContextSnapshot;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowNodeRun;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.SecurityPrincipals;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.enums.StepActionType;
import ai.operativus.agentmanager.core.model.workflow.DagFrontier;
import ai.operativus.agentmanager.core.model.workflow.NestedPause;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;

/**
 * Domain Responsibility: The DAG workflow engine's frontier scheduler (REQ-DR-5, DAG plan §2.3;
 *     DAG-3a/3c). Walks a workflow's {@link WorkflowStep} nodes along its persisted
 *     {@link WorkflowEdge} topology — replacing the flat-list {@code step_order} index loop — with
 *     real fan-out (independent branches on virtual threads) and join-counting fan-in (a node runs
 *     only once ALL its predecessors complete). Each node is dispatched to the
 *     {@link WorkflowNodeExecutor} for its {@link NodeKind} and its {@link StepOutput} is persisted
 *     as a {@code workflow_node_runs} row.
 *
 *     <p><b>Pause + resume (DAG-3c).</b> When a node bubbles up a HITL pause, the scheduler enters a
 *     <i>draining</i> mode: it stops enqueueing successors, lets in-flight sibling branches finish
 *     and persist their rows (never enqueuing THEIR successors), then snapshots a compact
 *     {@link DagFrontier} of the non-derivable scheduler state. {@link #resume} rehydrates
 *     completed-node {@code outputs} from {@code workflow_node_runs} (the source of truth), seeds its
 *     working maps from the frontier, injects the settled node's output, and re-enters the exact
 *     graph — running the remainder of the DAG exactly once.
 *
 *     <p><b>Scope.</b> Edges are unconditional unless a CONDITION/ROUTER node restricts active ports
 *     (DAG-4) and LOOP back-edges (port {@code "back"}, DAG-5a) thread control back to the LOOP node.
 *     A node whose kind has no registered executor fails fast. Failure short-circuits the run.
 *
 *     <p><b>Tenancy / context.</b> Agent runs execute in the run's resolved org (never the security
 *     principal — the #1134 contract); each branch rebinds the captured {@link AgentContextSnapshot}
 *     on its virtual thread (F2/F3/F4). Cancellation is cooperative: the caller supplies a poll that
 *     is checked at each scheduler turn (no thread interrupt — see the flat engine's incident note).
 * State: Stateless (all per-run state is local to {@link #run}/{@link #resume}).
 */
@Component
public class DagWorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagWorkflowExecutor.class);
    private static final String JOIN_SEPARATOR = "\n---\n";
    /** Edge port label marking a LOOP back-edge (body → LOOP node); held out of the forward DAG. */
    private static final String BACK_PORT = "back";

    private final WorkflowNodeExecutorRegistry registry;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final int maxParallelism;
    private final int defaultMaxAttempts;
    private final long defaultBackoffMs;
    private final long defaultTimeoutMs;

    /** Bounds per-attempt node execution when a timeout applies. VT-per-task: no threads until used. */
    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DagWorkflowExecutor(WorkflowNodeExecutorRegistry registry,
                               WorkflowNodeRunRepository nodeRunRepository,
                               @Value("${agm.workflow.dag.max-parallelism:8}") int maxParallelism,
                               @Value("${agm.workflow.dag.default-node-max-attempts:1}") int defaultMaxAttempts,
                               @Value("${agm.workflow.dag.default-node-retry-backoff-ms:0}") long defaultBackoffMs,
                               @Value("${agm.workflow.dag.default-node-timeout-ms:0}") long defaultTimeoutMs) {
        this.registry = registry;
        this.nodeRunRepository = nodeRunRepository;
        this.maxParallelism = Math.max(1, maxParallelism);
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
        this.defaultBackoffMs = Math.max(0, defaultBackoffMs);
        this.defaultTimeoutMs = Math.max(0, defaultTimeoutMs);
    }

    @PreDestroy
    void shutdownTimeoutExecutor() {
        timeoutExecutor.shutdownNow();
    }

    /** A node's id paired with the output its executor produced. */
    private record NodeResult(String nodeId, StepOutput output) {}

    /**
     * The outcome of a scheduler turn (run or resume): the terminal/triggering {@link StepOutput},
     * plus the {@link DagFrontier} snapshot when (and only when) the run paused. {@code frontier} is
     * non-null iff {@code output.paused()}.
     */
    public record DagResult(StepOutput output, DagFrontier frontier) {}

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Backward-compatible entry point — runs a fresh graph and returns only the terminal output. */
    public StepOutput execute(WorkflowRun run, List<WorkflowStep> nodes, List<WorkflowEdge> edges,
                              String initialInput, BooleanSupplier cancelled) {
        return execute(run, nodes, edges, initialInput, cancelled, NodeEventSink.NOOP);
    }

    /** As {@link #execute(WorkflowRun, List, List, String, BooleanSupplier)} with a {@link NodeEventSink}. */
    public StepOutput execute(WorkflowRun run, List<WorkflowStep> nodes, List<WorkflowEdge> edges,
                              String initialInput, BooleanSupplier cancelled, NodeEventSink events) {
        return run(run, nodes, edges, initialInput, cancelled, events).output();
    }

    /**
     * Runs the workflow's node graph fresh and returns the {@link DagResult} (terminal output, or the
     * triggering output + a {@link DagFrontier} on pause). The control layer persists the frontier
     * when {@code output.paused()} so a later {@link #resume} can continue from it.
     */
    public DagResult run(WorkflowRun run, List<WorkflowStep> nodes, List<WorkflowEdge> edges,
                         String initialInput, BooleanSupplier cancelled, NodeEventSink events) {
        if (nodes == null || nodes.isEmpty()) {
            return new DagResult(StepOutput.success("<run>", run.getId(), NodeKind.AGENT, "DAG",
                    initialInput, List.of(), Instant.now(), Instant.now(), null, null), null);
        }
        NodeEventSink sink = events == null ? NodeEventSink.NOOP : events;
        // Bind workflowRunId for the whole run so the shared workflow session legitimately spans
        // every node's agent run (the #1134 exemption in AgentService.ensureSessionExists). The
        // snapshot captured inside this scope rebinds it on each fan-out worker VT (F2).
        String[] runIdHolder = new String[]{run.getId()};
        DagResult[] result = new DagResult[1];
        ScopedValue.where(AgentContextHolder.workflowRunId, runIdHolder).run(() -> {
            Work w = new Work(nodes, edges);
            for (WorkflowStep n : nodes) {
                if (w.remaining.get(n.getId()) == 0) w.ready.add(n.getId());
            }
            result[0] = drive(run, w, initialInput, cancelled, sink, List.of());
        });
        return result[0];
    }

    /**
     * Resumes a paused DAG run from its persisted {@link DagFrontier}. Rehydrates completed-node
     * {@code outputs} from {@code workflow_node_runs} (the source of truth), seeds the live scheduler
     * maps from the frontier, injects {@code settledOutput} as the previously-paused node's output,
     * delivers its outgoing tokens, and re-enters the graph from the recorded pending frontier. Any
     * frontier pause nodes OTHER than the settled one are carried forward, so a run with concurrent
     * pauses stays paused until each is settled.
     *
     * @param settledOutput the operator-settled output for {@code frontier.pausedNodeIds().get(0)}
     *                      (an injected AGENT/review result, or a ROUTER {@code branched(choiceKey)})
     */
    public DagResult resume(WorkflowRun run, List<WorkflowStep> nodes, List<WorkflowEdge> edges,
                            DagFrontier frontier, StepOutput settledOutput,
                            BooleanSupplier cancelled, NodeEventSink events) {
        NodeEventSink sink = events == null ? NodeEventSink.NOOP : events;
        String[] runIdHolder = new String[]{run.getId()};
        DagResult[] result = new DagResult[1];
        ScopedValue.where(AgentContextHolder.workflowRunId, runIdHolder).run(() -> {
            Work w = new Work(nodes, edges);
            // 1. Rehydrate the working maps from the snapshot (join + loop bookkeeping).
            w.remaining.putAll(frontier.remaining());
            w.liveTokens.putAll(frontier.liveTokens());
            w.iteration.putAll(frontier.iteration());
            w.attempts.putAll(frontier.attempts());
            frontier.loopInput().forEach((k, v) -> w.loopInput.put(k, TextNode.valueOf(v)));
            w.nestedPauses.putAll(frontier.nestedPauses());
            // 2. Rebuild completed-node outputs from the node-run trace (NOT duplicated in the frontier).
            rehydrateOutputs(run.getId(), w);
            // 3. Re-enqueue the recorded pending frontier (ready-but-unstarted nodes).
            for (String pendingId : frontier.pendingNodeIds()) {
                if (w.nodeById.containsKey(pendingId)) w.ready.add(pendingId);
            }
            // 4. Settle the paused node. A NESTED pause (paused child sub-workflow) is settled by the
            //    node's executor re-entering the child graph with the operator output retargeted to
            //    the child's paused node; a plain pause injects the settled output directly.
            String settledNodeId = settledOutput.nodeId();
            StepOutput effective = settledOutput;
            NestedPause nested = w.nestedPauses.remove(settledNodeId);
            if (nested != null) {
                effective = settleNested(run, w, settledNodeId, nested, settledOutput);
            }
            w.outputs.put(settledNodeId, effective);
            persistNodeRun(run, effective, w.attempts.merge(settledNodeId, 1, Integer::sum));
            sink.emit(effective.paused() ? "PAUSED" : effective.success() ? "COMPLETED" : "FAILED",
                    run.getId(), run.getWorkflowId(), effective.nodeId(), effective.nodeName(),
                    effective.kind());
            // 5. Carry forward any still-unsettled pauses so the run stays paused until all are settled.
            List<String> carriedPauses = frontier.pausedNodeIds().stream()
                    .filter(id -> !id.equals(settledNodeId))
                    .collect(Collectors.toList());
            if (effective.paused()) {
                // The child paused again — the WORKFLOW node stays paused with its fresh nested state.
                if (effective.nested() != null) w.nestedPauses.put(settledNodeId, effective.nested());
                carriedPauses.add(settledNodeId);
            } else if (!effective.success() || effective.stop()) {
                // A failed (or stopping) settle is terminal for the run — never partially advance.
                result[0] = new DagResult(effective, null);
                return;
            } else {
                deliverOutgoing(settledNodeId, effective, w);
            }
            result[0] = drive(run, w, null, cancelled, sink, carriedPauses);
        });
        return result[0];
    }

    /**
     * Per-node lifecycle signal sink (REQ-DR-5). Emitted from the scheduler loop thread only (not
     * the worker VTs), so callers need not be thread-safe. {@code phase} ∈ STARTED/COMPLETED/FAILED/PAUSED.
     */
    @FunctionalInterface
    public interface NodeEventSink {
        void emit(String phase, String runId, String workflowId, String nodeId, String nodeName, NodeKind kind);

        NodeEventSink NOOP = (phase, runId, workflowId, nodeId, nodeName, kind) -> { };
    }

    /**
     * The live (mutable) scheduler working state for one run/resume. Distinct from the persisted
     * {@link DagFrontier} snapshot. The static topology (nodeById/outEdges/predecessors/
     * forwardInDegree/backEdgeTarget) is rebuilt deterministically from nodes+edges; the dynamic maps
     * (remaining/liveTokens/iteration/attempts/loopInput/outputs) carry per-run progress and are
     * seeded fresh (run) or from the frontier + node-run trace (resume).
     */
    private static final class Work {
        final Map<String, WorkflowStep> nodeById = new HashMap<>();
        final Map<String, List<WorkflowEdge>> outEdges = new HashMap<>();
        final Map<String, List<String>> predecessors = new HashMap<>();
        final Map<String, Integer> forwardInDegree = new HashMap<>();
        final Map<String, String> backEdgeTarget = new HashMap<>();
        final Map<String, Integer> remaining = new HashMap<>();
        final Map<String, Integer> liveTokens = new HashMap<>();
        final Map<String, Integer> iteration = new HashMap<>();
        final Map<String, Integer> attempts = new HashMap<>();
        final Map<String, JsonNode> loopInput = new HashMap<>();
        final Map<String, NestedPause> nestedPauses = new HashMap<>();
        final Map<String, StepOutput> outputs = new ConcurrentHashMap<>();
        final Deque<String> ready = new ArrayDeque<>();

        Work(List<WorkflowStep> nodes, List<WorkflowEdge> edges) {
            for (WorkflowStep n : nodes) nodeById.put(n.getId(), n);
            for (WorkflowStep n : nodes) { remaining.put(n.getId(), 0); forwardInDegree.put(n.getId(), 0); }
            // Forward edges build the DAG; LOOP back-edges (port "back") are held out so the cycle does
            // not break the in-degree/join model (DAG-5a). backEdgeTarget maps a loop body node → its
            // LOOP node: when the body completes, control returns to the LOOP for re-evaluation.
            for (WorkflowEdge e : edges) {
                if (!nodeById.containsKey(e.getFromStepId()) || !nodeById.containsKey(e.getToStepId())) continue;
                if (BACK_PORT.equals(e.getCondition())) {
                    backEdgeTarget.put(e.getFromStepId(), e.getToStepId());
                    continue;
                }
                outEdges.computeIfAbsent(e.getFromStepId(), k -> new ArrayList<>()).add(e);
                predecessors.computeIfAbsent(e.getToStepId(), k -> new ArrayList<>()).add(e.getFromStepId());
                remaining.merge(e.getToStepId(), 1, Integer::sum);
                forwardInDegree.merge(e.getToStepId(), 1, Integer::sum);
            }
            // Deterministic predecessor ordering for fan-in content assembly.
            predecessors.values().forEach(l -> l.sort(Comparator.naturalOrder()));
        }
    }

    /**
     * The shared scheduler loop. Submits ready nodes to virtual threads (bounded by the parallelism
     * semaphore), collects results, persists each node run, and delivers outgoing tokens. On the
     * first HITL pause it enters draining mode — stops submitting, lets in-flight branches finish +
     * persist, then snapshots a {@link DagFrontier}. {@code seedPausedNodeIds} carries pauses from a
     * prior frontier (resume) so they are re-recorded if still unsettled.
     */
    private DagResult drive(WorkflowRun run, Work w, String initialInput, BooleanSupplier cancelled,
                            NodeEventSink events, List<String> seedPausedNodeIds) {
        List<String> pausedNodeIds = new ArrayList<>(seedPausedNodeIds);
        StepOutput pauseTrigger = null;
        boolean draining = false;

        AgentContextSnapshot snapshot = AgentContextSnapshot.capture();
        Semaphore gate = new Semaphore(maxParallelism);

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorCompletionService<NodeResult> ecs = new ExecutorCompletionService<>(vt);
            int inflight = 0;

            while (!w.ready.isEmpty() || inflight > 0) {
                if (cancelled.getAsBoolean()) {
                    log.info("DAG run {} cancelled mid-flight; aborting (inflight={})", run.getId(), inflight);
                    return new DagResult(StepOutput.failure("<run>", run.getId(), NodeKind.AGENT, "DAG",
                            "Run cancelled", List.of(), Instant.now(), Instant.now()), null);
                }
                if (!draining) {
                    while (!w.ready.isEmpty()) {
                        WorkflowStep node = w.nodeById.get(w.ready.poll());
                        StepInput in = buildInput(run.getId(), node, w.predecessors, w.outputs, initialInput,
                                w.iteration.getOrDefault(node.getId(), 0), w.loopInput.get(node.getId()));
                        events.emit("STARTED", run.getId(), run.getWorkflowId(), node.getId(),
                                nodeName(node), kindOf(node));
                        ecs.submit(runNode(node, in, run, snapshot, gate));
                        inflight++;
                    }
                }
                // Draining with nothing left in flight → the frontier has quiesced.
                if (inflight == 0) break;

                NodeResult result = takeNext(ecs);
                inflight--;
                if (result == null) continue;

                String nodeId = result.nodeId();
                StepOutput out = result.output();
                w.outputs.put(nodeId, out);
                persistNodeRun(run, out, w.attempts.merge(nodeId, 1, Integer::sum));
                events.emit(out.paused() ? "PAUSED" : out.success() ? "COMPLETED" : "FAILED",
                        run.getId(), run.getWorkflowId(), out.nodeId(), out.nodeName(), out.kind());

                if (out.paused()) {
                    // HITL pause: record it and switch to draining — stop submitting successors, but let
                    // any in-flight sibling branches finish and persist (their successors are NOT enqueued).
                    pausedNodeIds.add(nodeId);
                    if (out.nested() != null) w.nestedPauses.put(nodeId, out.nested());
                    if (pauseTrigger == null) pauseTrigger = out;
                    draining = true;
                    continue; // a paused node delivers no tokens
                }
                if (!out.success() || out.stop()) {
                    // Failure / explicit stop short-circuits the whole run (terminal; no frontier). A
                    // failure observed mid-drain still ends the run — the prior pause is superseded.
                    return new DagResult(out, null);
                }

                deliverOutgoing(nodeId, out, w);
            }
        }

        if (pauseTrigger != null || !pausedNodeIds.isEmpty()) {
            StepOutput trigger = pauseTrigger != null ? pauseTrigger
                    : pausedOutputFor(w, pausedNodeIds.get(0));
            DagFrontier frontier = snapshotFrontier(w, pausedNodeIds, trigger.pauseKind());
            return new DagResult(trigger, frontier);
        }
        return new DagResult(terminalOutput(run, w, initialInput), null);
    }

    /**
     * Port selection (DAG-4) + dead-path elimination (DAG-4b) + LOOP back-edge (DAG-5a) for a
     * just-completed SUCCESS node: delivers a live/dead token down each outgoing edge and re-arms a
     * LOOP body. Single-threaded — only the scheduler loop calls this.
     */
    private void deliverOutgoing(String nodeId, StepOutput out, Work w) {
        List<String> activePorts = out.activePorts();
        boolean isLoop = NodeKind.fromAction(StepActionType.fromString(w.nodeById.get(nodeId).getAction()))
                == NodeKind.LOOP;
        if (isLoop && activePorts != null && activePorts.contains("loop")) {
            w.iteration.merge(nodeId, 1, Integer::sum);
        }

        for (WorkflowEdge e : w.outEdges.getOrDefault(nodeId, List.of())) {
            boolean selected = activePorts == null || activePorts.contains(e.getCondition());
            if (isLoop) {
                // A LOOP node delivers ONLY to its selected port; the unselected port (the exit edge
                // during looping, or the loop edge after exit) is NOT dead-pathed — it may be selected
                // on a later pass. The selected target is re-armed so it can run again this iteration
                // (DAG-5a assumes a single-node loop body).
                if (!selected) continue;
                w.remaining.put(e.getToStepId(), w.forwardInDegree.getOrDefault(e.getToStepId(), 1));
                w.liveTokens.put(e.getToStepId(), 0);
                deliverToken(e.getToStepId(), true, w.remaining, w.liveTokens, w.outEdges, w.ready);
            } else {
                deliverToken(e.getToStepId(), selected, w.remaining, w.liveTokens, w.outEdges, w.ready);
            }
        }

        // Back-edge: a loop body returning control to its LOOP node, carrying its output as the
        // loop's next input. Re-enqueue the LOOP for re-evaluation.
        String loopNode = w.backEdgeTarget.get(nodeId);
        if (loopNode != null) {
            w.loopInput.put(loopNode, out.content());
            w.remaining.put(loopNode, 0);
            w.liveTokens.put(loopNode, 0);
            w.ready.add(loopNode);
        }
    }

    /** Builds the persisted snapshot from the live scheduler maps at a pause. */
    private static DagFrontier snapshotFrontier(Work w, List<String> pausedNodeIds, String pauseKind) {
        Map<String, String> loopInputText = new HashMap<>();
        w.loopInput.forEach((k, v) -> loopInputText.put(k,
                v == null || v.isNull() ? "" : (v.isTextual() ? v.asText() : v.toString())));
        return new DagFrontier(
                new ArrayList<>(w.ready),
                new HashMap<>(w.remaining),
                new HashMap<>(w.liveTokens),
                new HashMap<>(w.iteration),
                new HashMap<>(w.attempts),
                loopInputText,
                pausedNodeIds,
                pauseKind,
                new HashMap<>(w.nestedPauses));
    }

    /**
     * Rebuilds {@code outputs} from the run's node-run trace — the latest attempt per node, keeping
     * only terminal SUCCESS rows (paused/failed rows are not real outputs). This is the single source
     * of truth for completed-node content, so it is never duplicated into the frontier.
     */
    private void rehydrateOutputs(String runId, Work w) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun row : nodeRunRepository.findByRunIdOrderByAttemptAsc(runId)) {
            latest.put(row.getNodeId(), row); // later attempts overwrite earlier ones
        }
        for (WorkflowNodeRun row : latest.values()) {
            if (!row.isSuccess() || row.isPaused()) continue;
            if (!w.nodeById.containsKey(row.getNodeId())) continue;
            StepOutput o = StepOutput.success(row.getNodeId(), row.getNodeName(),
                    row.getKind(), null, row.getContent(), List.of(),
                    row.getStartedAt(), row.getEndedAt(), row.getTokenCost(), row.getModelId());
            w.outputs.put(row.getNodeId(), o);
        }
    }

    /**
     * Settles a nested pause: dispatches the paused node's executor {@code resumeNested} so the
     * child sub-workflow re-enters its exact graph with the operator output retargeted onto the
     * child's paused node. Failures are recorded, never thrown (the resume caller maps them).
     */
    private StepOutput settleNested(WorkflowRun run, Work w, String nodeId,
                                    NestedPause nested, StepOutput settledOutput) {
        WorkflowStep node = w.nodeById.get(nodeId);
        if (node == null) {
            return StepOutput.failure(nodeId, nodeId, NodeKind.WORKFLOW, null,
                    "Nested-paused node " + nodeId + " no longer exists in the workflow graph",
                    List.of(), Instant.now(), Instant.now());
        }
        NodeKind kind = kindOf(node);
        WorkflowNodeExecutor executor = registry.resolve(kind).orElse(null);
        if (executor == null) {
            return StepOutput.failure(nodeId, nodeName(node), kind, null,
                    "No executor registered for nested-paused node kind " + kind,
                    List.of(), Instant.now(), Instant.now());
        }
        NodeContext ctx = new NodeContext(run.getId(), run.getWorkflowId(), run.getSessionId(),
                orgOf(run), SecurityPrincipals.SYSTEM_PRINCIPAL, node);
        StepInput in = buildInput(run.getId(), node, w.predecessors, w.outputs, null,
                w.iteration.getOrDefault(nodeId, 0), w.loopInput.get(nodeId));
        try {
            return executor.resumeNested(in, ctx, nested, settledOutput);
        } catch (RuntimeException ex) {
            log.warn("Nested resume for node {} threw", nodeId, ex);
            return StepOutput.failure(nodeId, nodeName(node), kind, null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    List.of(), Instant.now(), Instant.now());
        }
    }

    /** The paused output for a carried (not-this-turn-triggered) pause node, for status mapping —
     *  the node's real paused output when available (preserving pause kind + nested state), else
     *  a synthetic placeholder. */
    private static StepOutput pausedOutputFor(Work w, String nodeId) {
        StepOutput existing = w.outputs.get(nodeId);
        if (existing != null && existing.paused()) return existing;
        WorkflowStep node = w.nodeById.get(nodeId);
        NodeKind kind = node != null ? kindOf(node) : NodeKind.AGENT;
        String name = node != null ? nodeName(node) : nodeId;
        return StepOutput.paused(nodeId, name, kind, null, null, List.of(), Instant.now(), Instant.now());
    }

    private NodeResult takeNext(ExecutorCompletionService<NodeResult> ecs) {
        try {
            Future<NodeResult> f = ecs.take();
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("DAG node task failed unexpectedly", e);
            return null;
        }
    }

    /**
     * Delivers one token (live or dead) to {@code toId} and resolves it once all predecessors have
     * delivered (remaining hits 0): a node that received ≥1 live token is enqueued to run; a node
     * that received only dead tokens is itself dead and recursively propagates dead tokens to its
     * successors (dead-path elimination). Single-threaded — only the scheduler loop calls this.
     */
    private static void deliverToken(String toId, boolean live,
                                     Map<String, Integer> remaining, Map<String, Integer> liveTokens,
                                     Map<String, List<WorkflowEdge>> outEdges, Deque<String> ready) {
        if (live) liveTokens.merge(toId, 1, Integer::sum);
        if (remaining.merge(toId, -1, Integer::sum) > 0) {
            return; // still waiting on other predecessors
        }
        if (liveTokens.getOrDefault(toId, 0) > 0) {
            ready.add(toId);
        } else {
            for (WorkflowEdge e : outEdges.getOrDefault(toId, List.of())) {
                deliverToken(e.getToStepId(), false, remaining, liveTokens, outEdges, ready);
            }
        }
    }

    private Callable<NodeResult> runNode(WorkflowStep node, StepInput in, WorkflowRun run,
                                         AgentContextSnapshot snapshot, Semaphore gate) {
        return () -> {
            gate.acquire();
            try {
                return snapshot.call(() -> new NodeResult(node.getId(), executeNode(node, in, run, snapshot)));
            } finally {
                gate.release();
            }
        };
    }

    private StepOutput executeNode(WorkflowStep node, StepInput in, WorkflowRun run, AgentContextSnapshot snapshot) {
        NodeKind kind = kindOf(node);
        String nodeName = nodeName(node);
        WorkflowNodeExecutor executor = registry.resolve(kind).orElse(null);
        if (executor == null) {
            return StepOutput.failure(node.getId(), nodeName, kind, null,
                    "No executor registered for node kind " + kind + " (unsupported in DAG-3a)",
                    List.of(), Instant.now(), Instant.now());
        }
        NodeContext ctx = new NodeContext(run.getId(), run.getWorkflowId(), run.getSessionId(),
                orgOf(run), SecurityPrincipals.SYSTEM_PRINCIPAL, node);

        int maxAttempts = Math.max(1, valueOr(node.getRetryMaxAttempts(), defaultMaxAttempts));
        long backoffMs = Math.max(0, valueOr(node.getRetryBackoffMs(), defaultBackoffMs));
        long timeoutMs = Math.max(0, valueOr(node.getTimeoutMs(), defaultTimeoutMs));

        StepOutput last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = runAttempt(executor, in, ctx, node, kind, nodeName, timeoutMs, snapshot);
            // Only a genuine failure is retryable. success / stop / paused are terminal outcomes —
            // a HITL pause especially must bubble straight up, never be re-run.
            if (last.success() || last.stop() || last.paused()) {
                return last;
            }
            if (attempt < maxAttempts) {
                log.info("DAG node {} ({}) failed on attempt {}/{}: {}{}",
                        node.getId(), kind, attempt, maxAttempts, last.error(),
                        backoffMs > 0 ? " — retrying after " + backoffMs + "ms" : " — retrying");
                if (backoffMs > 0 && !sleepQuietly(backoffMs)) {
                    return last; // interrupted while backing off — give up with the last failure
                }
            }
        }
        return last; // exhausted attempts — the final failure propagates
    }

    /** One node attempt, optionally bounded by a wall-clock timeout. */
    private StepOutput runAttempt(WorkflowNodeExecutor executor, StepInput in, NodeContext ctx,
                                  WorkflowStep node, NodeKind kind, String nodeName,
                                  long timeoutMs, AgentContextSnapshot snapshot) {
        if (timeoutMs <= 0) {
            return invoke(executor, in, ctx, node, kind, nodeName); // unbounded — run inline, context already bound
        }
        // Bounded: run on a VT with the agent context re-bound (ScopedValues don't cross threads) and
        // stop waiting after timeoutMs. We cannot hard-interrupt a blocking provider call, so an
        // abandoned task may still run to completion — its result is simply discarded.
        Future<StepOutput> f = timeoutExecutor.submit(
                () -> snapshot.call(() -> invoke(executor, in, ctx, node, kind, nodeName)));
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            log.warn("DAG node {} ({}) exceeded timeout of {}ms", node.getId(), kind, timeoutMs);
            return StepOutput.failure(node.getId(), nodeName, kind, null,
                    "Node exceeded timeout of " + timeoutMs + "ms", List.of(), Instant.now(), Instant.now());
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            log.warn("DAG node {} ({}) threw", node.getId(), kind, cause);
            return StepOutput.failure(node.getId(), nodeName, kind, null,
                    cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(),
                    List.of(), Instant.now(), Instant.now());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return StepOutput.failure(node.getId(), nodeName, kind, null,
                    "Interrupted awaiting node completion", List.of(), Instant.now(), Instant.now());
        }
    }

    /** Invoke an executor once, mapping a thrown RuntimeException to a failure output. */
    private StepOutput invoke(WorkflowNodeExecutor executor, StepInput in, NodeContext ctx,
                              WorkflowStep node, NodeKind kind, String nodeName) {
        try {
            return executor.execute(in, ctx);
        } catch (RuntimeException ex) {
            log.warn("DAG node {} ({}) threw", node.getId(), kind, ex);
            return StepOutput.failure(node.getId(), nodeName, kind, null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    List.of(), Instant.now(), Instant.now());
        }
    }

    private static int valueOr(Integer v, int fallback) {
        return v != null ? v : fallback;
    }

    private static long valueOr(Long v, long fallback) {
        return v != null ? v : fallback;
    }

    /** Sleep for {@code ms}; returns false if interrupted (caller should stop retrying). */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private StepInput buildInput(String runId, WorkflowStep node, Map<String, List<String>> predecessors,
                                 Map<String, StepOutput> outputs, String initialInput,
                                 int iteration, JsonNode loopOverride) {
        // iteration is bound (non-null) only inside a LOOP — DAG plan §2.2 (limitation L8).
        Integer iter = iteration > 0 ? iteration : null;
        // A re-entered LOOP node takes its input from the body's last output (threaded via the
        // back-edge), not from its original forward predecessor.
        if (loopOverride != null) {
            return new StepInput(runId, node.getId(), nodeName(node), loopOverride,
                    Map.of(), List.of(), List.of(), null, null, iter);
        }
        List<String> preds = predecessors.getOrDefault(node.getId(), List.of());
        if (preds.isEmpty()) {
            return new StepInput(runId, node.getId(), nodeName(node),
                    TextNode.valueOf(initialInput == null ? "" : initialInput),
                    Map.of(), List.of(), List.of(), null, null, iter);
        }
        Map<String, StepOutput> previousOutputs = new LinkedHashMap<>();
        List<JsonNode> upstreamContent = new ArrayList<>();
        for (String predId : preds) {
            StepOutput po = outputs.get(predId);
            if (po == null) continue;
            previousOutputs.put(po.nodeName(), po);
            upstreamContent.add(po.content());
        }
        String joined = preds.stream()
                .map(outputs::get)
                .filter(java.util.Objects::nonNull)
                .map(StepOutput::contentText)
                .collect(Collectors.joining(JOIN_SEPARATOR));
        return new StepInput(runId, node.getId(), nodeName(node), TextNode.valueOf(joined),
                previousOutputs, upstreamContent, List.of(), null, null, iter);
    }

    /**
     * Terminal output = the forward-out-degree-0 nodes (excluding loop-body nodes, whose only
     * out-edge is the back-edge); one is returned directly, several are joined.
     */
    private StepOutput terminalOutput(WorkflowRun run, Work w, String initialInput) {
        List<StepOutput> terminals = new ArrayList<>();
        for (WorkflowStep n : w.nodeById.values()) {
            if (w.backEdgeTarget.containsKey(n.getId())) continue;
            if (w.outEdges.getOrDefault(n.getId(), List.of()).isEmpty()) {
                StepOutput o = w.outputs.get(n.getId());
                if (o != null) terminals.add(o);
            }
        }
        if (terminals.isEmpty()) {
            return StepOutput.success("<run>", run.getId(), NodeKind.AGENT, "DAG",
                    initialInput, List.of(), Instant.now(), Instant.now(), null, null);
        }
        if (terminals.size() == 1) {
            return terminals.get(0);
        }
        // Deterministic join order (node-run map iteration is unordered); sort by node id.
        terminals.sort(Comparator.comparing(StepOutput::nodeId));
        String joined = terminals.stream().map(StepOutput::contentText).collect(Collectors.joining(JOIN_SEPARATOR));
        return StepOutput.success("<run>", run.getId(), NodeKind.AGENT, "DAG",
                joined, List.of(), Instant.now(), Instant.now(), null, null);
    }

    private void persistNodeRun(WorkflowRun run, StepOutput out, int attempt) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId(UUID.randomUUID().toString());
        row.setRunId(run.getId());
        row.setWorkflowId(run.getWorkflowId());
        row.setNodeId(out.nodeId());
        row.setNodeName(out.nodeName());
        row.setKind(out.kind());
        row.setAttempt(attempt);
        row.setContent(out.contentText());
        row.setSuccess(out.success());
        row.setError(out.error());
        row.setPaused(out.paused());
        row.setPauseKind(out.pauseKind());
        row.setTokenCost(out.tokenCost());
        row.setModelId(out.modelId());
        row.setStartedAt(out.startedAt());
        row.setEndedAt(out.endedAt());
        try {
            nodeRunRepository.save(row);
        } catch (RuntimeException ex) {
            // A trace-row failure must not abort the run; log and continue.
            log.warn("Failed to persist workflow_node_runs row for run {} node {}", run.getId(), out.nodeId(), ex);
        }
    }

    private static NodeKind kindOf(WorkflowStep node) {
        return NodeKind.fromAction(StepActionType.fromString(node.getAction()));
    }

    private static String nodeName(WorkflowStep node) {
        return (node.getAgentId() != null && !node.getAgentId().isBlank()) ? node.getAgentId() : node.getId();
    }

    private static String orgOf(WorkflowRun run) {
        return (run.getOrgId() != null && !run.getOrgId().isBlank()) ? run.getOrgId() : TenantConstants.DEFAULT_SYSTEM_ORG;
    }
}
