package ai.operativus.agentmanager.compute.workflow;

import ai.operativus.agentmanager.control.repository.WorkflowEdgeRepository;
import ai.operativus.agentmanager.control.repository.WorkflowStepRepository;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.WorkflowValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Domain Responsibility: REQ-DR-5 DAG safety check. Validates that a workflow's
 *     {@code workflow_edges} table defines an acyclic graph reachable from the
 *     designated start step, and that every step row is reachable from start.
 *
 *     <p><b>Why both checks:</b>
 *     <ul>
 *       <li><b>Cycle detection</b> — a cycle in the graph would let the dispatcher
 *           loop forever (or hit the iteration cap). DFS with white/gray/black
 *           coloring catches it before the run starts.</li>
 *       <li><b>Reachability</b> — an orphan step (no inbound edges, not the start)
 *           is dead code; it'll never dispatch. Caller chooses whether to fail
 *           hard or just warn — see {@link #validate}.</li>
 *     </ul>
 *
 *     <p><b>Workflows without edges:</b> validate is a no-op. Legacy flat-list
 *     workflows (which still use {@code step_order} for dispatch) don't have edges
 *     until the migration PR runs; this validator returns success unconditionally
 *     when {@code edges.isEmpty()} so the absence of edges doesn't break boot.
 *
 * State: Stateless (Spring singleton).
 */
@Component
public class WorkflowDagValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDagValidator.class);
    /** Sanctioned LOOP back-edge port (body → LOOP node). Excluded from acyclicity — it's the one
     *  legal cycle (plan §2.9); the DagWorkflowExecutor bounds the loop by max/until. */
    private static final String BACK_PORT = "back";

    private final WorkflowEdgeRepository edgeRepository;
    private final WorkflowStepRepository stepRepository;

    public WorkflowDagValidator(WorkflowEdgeRepository edgeRepository,
                                WorkflowStepRepository stepRepository) {
        this.edgeRepository = edgeRepository;
        this.stepRepository = stepRepository;
    }

    /**
     * @summary Validates the DAG rooted at the workflow's lowest-{@code stepOrder} step
     *     (the implicit start step). Throws {@link BusinessValidationException} on a
     *     detected cycle; logs WARN on unreachable steps without throwing (caller
     *     decides whether unreachable rows are fatal).
     * @param workflowId The workflow whose DAG to validate.
     */
    public void validate(String workflowId) {
        List<WorkflowEdge> edges = edgeRepository.findByWorkflowIdOrderByFromStepIdAsc(workflowId);
        if (edges.isEmpty()) {
            return; // Legacy flat-list workflow — no DAG to validate.
        }
        List<WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        if (steps.isEmpty()) {
            throw new BusinessValidationException(
                    "Workflow " + workflowId + " has edges but no steps — corrupt configuration");
        }
        String startId = steps.get(0).getId();
        Map<String, List<String>> adjacency = buildAdjacency(edges);

        detectCycle(workflowId, adjacency, startId);
        logUnreachableSteps(workflowId, adjacency, steps, startId);
    }

    /**
     * @summary Non-throwing variant for the editor's validation overlay. Reports both a cycle
     *     (if any) and the set of orphan (unreachable-from-start) step ids, without throwing or
     *     logging. An edge-less or empty workflow reports {@link WorkflowValidationResult#ok()}.
     * @param workflowId The workflow whose DAG to inspect.
     */
    public WorkflowValidationResult validateReport(String workflowId) {
        List<WorkflowEdge> edges = edgeRepository.findByWorkflowIdOrderByFromStepIdAsc(workflowId);
        if (edges.isEmpty()) {
            return WorkflowValidationResult.ok(); // Legacy flat-list workflow — nothing to validate.
        }
        List<WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        if (steps.isEmpty()) {
            return new WorkflowValidationResult(false, false,
                    "Workflow " + workflowId + " has edges but no steps — corrupt configuration", List.of());
        }
        String startId = steps.get(0).getId();
        Map<String, List<String>> adjacency = buildAdjacency(edges);

        String cycleMessage = findCycleMessage(workflowId, adjacency, startId);
        Set<String> reachable = reachableFrom(adjacency, startId);
        List<String> orphans = steps.stream()
                .map(WorkflowStep::getId)
                .filter(stepId -> !reachable.contains(stepId))
                .toList();
        boolean valid = cycleMessage == null && orphans.isEmpty();
        return new WorkflowValidationResult(valid, cycleMessage != null, cycleMessage, orphans);
    }

    /** Adjacency list keyed by from-step id (forward edges only). Sanctioned LOOP back-edges
     *  (port {@code "back"}) are excluded so the loop's intentional cycle doesn't fail the
     *  acyclicity check; the same exclusion keeps reachability a forward-only measure. */
    private static Map<String, List<String>> buildAdjacency(List<WorkflowEdge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (WorkflowEdge e : edges) {
            if (BACK_PORT.equals(e.getCondition())) continue;
            adj.computeIfAbsent(e.getFromStepId(), k -> new ArrayList<>()).add(e.getToStepId());
        }
        return adj;
    }

    /** Throwing wrapper over {@link #findCycleMessage} — preserves the edit-path contract
     *  (a cycle-introducing edge is rejected with a {@link BusinessValidationException}). */
    private static void detectCycle(String workflowId, Map<String, List<String>> adj, String start) {
        String cycle = findCycleMessage(workflowId, adj, start);
        if (cycle != null) {
            throw new BusinessValidationException(cycle);
        }
    }

    /** DFS with white (unvisited) / gray (on stack) / black (done) coloring. Encountering
     *  a gray node along an out-edge means we've closed a cycle. Returns the cycle description,
     *  or null when the reachable subgraph is acyclic. */
    private static String findCycleMessage(String workflowId, Map<String, List<String>> adj, String start) {
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(start, adj.getOrDefault(start, List.of()).iterator()));
        gray.add(start);

        while (!stack.isEmpty()) {
            Frame top = stack.peek();
            if (top.children.hasNext()) {
                String next = top.children.next();
                if (gray.contains(next)) {
                    return "Workflow " + workflowId + " DAG has a cycle reaching step " + next
                            + " from " + top.node;
                }
                if (black.contains(next)) {
                    continue; // Already explored from another branch.
                }
                gray.add(next);
                stack.push(new Frame(next, adj.getOrDefault(next, List.of()).iterator()));
            } else {
                gray.remove(top.node);
                black.add(top.node);
                stack.pop();
            }
        }
        return null;
    }

    /** BFS forward-reachable set from the start step over the (back-edge-excluded) adjacency. */
    private static Set<String> reachableFrom(Map<String, List<String>> adj, String start) {
        Set<String> reachable = new HashSet<>();
        Deque<String> bfs = new ArrayDeque<>();
        bfs.add(start);
        reachable.add(start);
        while (!bfs.isEmpty()) {
            String n = bfs.poll();
            for (String next : adj.getOrDefault(n, List.of())) {
                if (reachable.add(next)) bfs.add(next);
            }
        }
        return reachable;
    }

    private static void logUnreachableSteps(String workflowId, Map<String, List<String>> adj,
                                            List<WorkflowStep> steps, String start) {
        Set<String> reachable = reachableFrom(adj, start);
        for (WorkflowStep s : steps) {
            if (!reachable.contains(s.getId())) {
                log.warn("workflow.dag.unreachable workflow={} step={} order={} — orphan step",
                        workflowId, s.getId(), s.getStepOrder());
            }
        }
    }

    /** @return true if the workflow has any DAG edges (used by the dispatcher to
     *     decide between flat-list iteration and DAG walking). PR-2 consumes this. */
    public boolean hasEdges(String workflowId) {
        return edgeRepository.countByWorkflowId(workflowId) > 0;
    }

    private record Frame(String node, java.util.Iterator<String> children) {}

    /** Convenience for callers that just want to know the first outbound edge
     *  matching a condition (or the unconditional one when condition is null). */
    public Optional<WorkflowEdge> findOutbound(String fromStepId, String condition) {
        return edgeRepository.findByFromStepIdOrderByConditionAscNullsFirst(fromStepId).stream()
                .filter(e -> condition == null
                        ? e.getCondition() == null
                        : condition.equals(e.getCondition()))
                .findFirst();
    }
}
