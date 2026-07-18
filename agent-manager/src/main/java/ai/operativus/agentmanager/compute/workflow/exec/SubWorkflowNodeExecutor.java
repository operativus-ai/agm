package ai.operativus.agentmanager.compute.workflow.exec;

import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.DagResult;
import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.NodeEventSink;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.workflow.NestedPause;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import ai.operativus.agentmanager.control.repository.WorkflowEdgeRepository;
import ai.operativus.agentmanager.control.repository.WorkflowRepository;
import ai.operativus.agentmanager.control.repository.WorkflowStepRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#WORKFLOW} nodes
 *     (REQ-DR-5, DAG-6) — nested sub-workflow execution. The child workflow id lives in the
 *     step's {@code agent_id} column (the same column-reuse convention as CONDITION / FUNCTION /
 *     WEBHOOK). The child graph is loaded org-scoped (cross-tenant id → indistinguishable from
 *     not-found, the §79 contract) and run synchronously through the SAME {@link DagWorkflowExecutor}
 *     scheduler ({@link ObjectProvider} breaks the registry ↔ scheduler bean cycle).
 *
 *     <p><b>Depth guard.</b> Nesting is bounded by {@code agm.workflow.dag.max-nesting-depth}
 *     (default 3) via {@link AgentContextHolder#workflowDepth} — bound to depth+1 around the
 *     recursive call, captured into {@code AgentContextSnapshot}, so it propagates through the
 *     child scheduler's fan-out virtual threads and a workflow that (transitively) includes
 *     itself terminates with a recorded failure instead of recursing forever.
 *
 *     <p><b>Child run identity.</b> The child executes under a derived run id
 *     ({@code <parentRunId>#<nodeId>#<uuid>}, minted once per logical invocation and REUSED across
 *     pause/resume cycles via {@link NestedPause#childRunId}) so its node-run rows never collide on
 *     {@code (run_id, node_id, attempt)} when the same child workflow appears behind two WORKFLOW
 *     nodes (or a LOOP re-arm), never pollute the parent's DAG-3c resume rehydration, and DO
 *     accumulate across resumes — which is what lets the child's own resume rehydrate its
 *     completed outputs. The rows still carry the child {@code workflow_id} for attribution.
 *
 *     <p><b>Nested pause + resume.</b> A HITL pause inside the child bubbles up as
 *     {@link StepOutput#pausedNested} carrying the child's frontier; the parent scheduler records
 *     it in its own frontier and, on settle, dispatches {@link #resumeNested} — which retargets the
 *     operator-settled output onto the child's innermost paused node and re-enters the child graph.
 *     Multi-level nesting recurses naturally (each level retargets one hop down).
 *
 *     <p><b>Limit.</b> Parent cancellation is observed between parent nodes, not inside a running
 *     child.
 * State: Stateless
 */
@Component
public class SubWorkflowNodeExecutor implements WorkflowNodeExecutor {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final ObjectProvider<DagWorkflowExecutor> dag;
    private final int maxNestingDepth;

    public SubWorkflowNodeExecutor(WorkflowRepository workflowRepository,
                                   WorkflowStepRepository stepRepository,
                                   WorkflowEdgeRepository edgeRepository,
                                   ObjectProvider<DagWorkflowExecutor> dag,
                                   @Value("${agm.workflow.dag.max-nesting-depth:3}") int maxNestingDepth) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.edgeRepository = edgeRepository;
        this.dag = dag;
        this.maxNestingDepth = Math.max(1, maxNestingDepth);
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WORKFLOW;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String childWorkflowId = ctx.node().getAgentId(); // WORKFLOW repurposes agent_id for the child workflow id
        if (childWorkflowId == null || childWorkflowId.isBlank()) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "WORKFLOW node has no sub-workflow id (expected in the step's agent_id field)",
                    in.media(), started, Instant.now());
        }
        StepOutput guard = guardChild(in, ctx, childWorkflowId, started);
        if (guard != null) return guard;

        List<WorkflowStep> childSteps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(childWorkflowId);
        if (childSteps.isEmpty()) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow " + childWorkflowId + " has no steps",
                    in.media(), started, Instant.now());
        }
        List<WorkflowEdge> childEdges = edgeRepository.findByWorkflowIdOrderByFromStepIdAsc(childWorkflowId);

        String childRunId = ctx.runId() + "#" + in.nodeId() + "#" + UUID.randomUUID();
        WorkflowRun childRun = childRun(ctx, childWorkflowId, childRunId);
        int depth = AgentContextHolder.getWorkflowDepth();
        try {
            DagResult result = ScopedValue.where(AgentContextHolder.workflowDepth, depth + 1)
                    .call(() -> dag.getObject().run(childRun, childSteps, childEdges,
                            in.inputText(), () -> false, NodeEventSink.NOOP));
            return mapChildResult(in, childWorkflowId, childRunId, result, started);
        } catch (Exception e) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow " + childWorkflowId + " execution error: " + e.getMessage(),
                    in.media(), started, Instant.now());
        }
    }

    @Override
    public StepOutput resumeNested(StepInput in, NodeContext ctx, NestedPause nested, StepOutput settledInner) {
        Instant started = Instant.now();
        String childWorkflowId = nested.childWorkflowId();
        StepOutput guard = guardChild(in, ctx, childWorkflowId, started);
        if (guard != null) return guard;

        List<WorkflowStep> childSteps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(childWorkflowId);
        List<WorkflowEdge> childEdges = edgeRepository.findByWorkflowIdOrderByFromStepIdAsc(childWorkflowId);
        WorkflowRun childRun = childRun(ctx, childWorkflowId, nested.childRunId());

        // Retarget the operator-settled output (built against THIS parent node id) onto the child's
        // innermost paused node. One hop only — a doubly-nested pause retargets again at the next
        // level when the child scheduler dispatches its own resumeNested.
        String childPausedId = nested.childFrontier().pausedNodeIds().isEmpty()
                ? null : nested.childFrontier().pausedNodeIds().get(0);
        if (childPausedId == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Nested pause state for sub-workflow " + childWorkflowId + " has no paused node",
                    in.media(), started, Instant.now());
        }
        WorkflowStep childNode = childSteps.stream()
                .filter(s -> childPausedId.equals(s.getId())).findFirst().orElse(null);
        String childName = childNode != null && childNode.getAgentId() != null && !childNode.getAgentId().isBlank()
                ? childNode.getAgentId() : childPausedId;
        NodeKind childKind = childNode != null
                ? NodeKind.fromAction(ai.operativus.agentmanager.core.model.enums.StepActionType
                        .fromString(childNode.getAction()))
                : NodeKind.AGENT;
        StepOutput retargeted = new StepOutput(childPausedId, childName, childKind,
                settledInner.executorType(), settledInner.content(), settledInner.media(),
                settledInner.success(), settledInner.error(), settledInner.stop(), settledInner.paused(),
                settledInner.pauseKind(), settledInner.children(), settledInner.startedAt(),
                settledInner.endedAt(), settledInner.tokenCost(), settledInner.modelId(),
                settledInner.activePorts(), settledInner.nested());

        int depth = AgentContextHolder.getWorkflowDepth();
        try {
            DagResult result = ScopedValue.where(AgentContextHolder.workflowDepth, depth + 1)
                    .call(() -> dag.getObject().resume(childRun, childSteps, childEdges,
                            nested.childFrontier(), retargeted, () -> false, NodeEventSink.NOOP));
            return mapChildResult(in, childWorkflowId, nested.childRunId(), result, started);
        } catch (Exception e) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow " + childWorkflowId + " resume error: " + e.getMessage(),
                    in.media(), started, Instant.now());
        }
    }

    /** Shared depth + org-scoped existence guard; null when the child may run. */
    private StepOutput guardChild(StepInput in, NodeContext ctx, String childWorkflowId, Instant started) {
        int depth = AgentContextHolder.getWorkflowDepth();
        if (depth + 1 > maxNestingDepth) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow nesting depth " + (depth + 1) + " exceeds the maximum of " + maxNestingDepth
                            + " (agm.workflow.dag.max-nesting-depth) — possible recursive workflow inclusion",
                    in.media(), started, Instant.now());
        }
        if (!workflowRepository.existsByIdAndOrgId(childWorkflowId, ctx.orgId())) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow not found: " + childWorkflowId,
                    in.media(), started, Instant.now());
        }
        return null;
    }

    /** Maps the child scheduler's terminal/paused result onto this WORKFLOW node's output. */
    private static StepOutput mapChildResult(StepInput in, String childWorkflowId, String childRunId,
                                             DagResult result, Instant started) {
        StepOutput childTerminal = result.output();
        if (childTerminal.paused()) {
            // Thread the child's paused content up — it becomes the run's currentPayload, i.e. the
            // input the operator-settled resume threads back into the child's chosen branch.
            return StepOutput.pausedNested(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    childTerminal.pauseKind(), childTerminal.contentText(),
                    new NestedPause(childRunId, childWorkflowId, result.frontier()),
                    in.media(), started, Instant.now());
        }
        if (!childTerminal.success()) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                    "Sub-workflow " + childWorkflowId + " failed: " + childTerminal.error(),
                    in.media(), started, Instant.now());
        }
        return StepOutput.success(in.nodeId(), in.nodeName(), NodeKind.WORKFLOW, "WORKFLOW",
                childTerminal.contentText(), childTerminal.media(), started, Instant.now(),
                childTerminal.tokenCost(), childTerminal.modelId());
    }

    private static WorkflowRun childRun(NodeContext ctx, String childWorkflowId, String childRunId) {
        WorkflowRun child = new WorkflowRun();
        child.setId(childRunId);
        child.setWorkflowId(childWorkflowId);
        child.setSessionId(ctx.sessionId());
        child.setOrgId(ctx.orgId());
        child.setStatus(RunStatus.RUNNING);
        return child;
    }
}
