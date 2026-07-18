package ai.operativus.agentmanager.core.model.workflow;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: The persisted, compact snapshot of a paused DAG run's in-flight scheduler
 *     state (REQ-DR-5, DAG-3c). Stored as JSONB on {@code workflow_runs.dag_frontier} when the
 *     frontier scheduler drains to a pause. On resume, {@code DagWorkflowExecutor.resume} rehydrates
 *     completed-node {@code outputs} from {@code workflow_node_runs} (the source of truth) and seeds
 *     the rest of its working maps from this snapshot, then re-enters the exact graph from the
 *     paused frontier.
 *
 *     <p><b>Only non-derivable state lives here.</b> Completed-node outputs are NOT duplicated —
 *     they are reconstructed from the node-run trace. This record carries the scheduler bookkeeping
 *     that cannot be recovered from the trace: which not-yet-run nodes are pending, the partial
 *     join state ({@code remaining} in-degree + {@code liveTokens} per node), the LOOP counters
 *     ({@code iteration}/{@code attempts}/{@code loopInput}), and which node(s) paused + the pause
 *     flavour (see {@link PauseKind}).
 * State: Stateless (Immutable Record carrier). Flat/primitive fields only (ids + int counters +
 *     small string maps) so the JSONB round-trip mirrors the proven {@code WorkflowStep.routerConfig}
 *     mapping — no nested {@code StepOutput}s.
 *
 * @param pendingNodeIds node ids that were enqueued/ready but had not produced an output at pause
 * @param remaining      per-node un-delivered predecessor count (join bookkeeping) at pause
 * @param liveTokens     per-node count of LIVE tokens received so far (dead-path elimination state)
 * @param iteration      per-LOOP-node current iteration counter
 * @param attempts       per-node execution-attempt counter (drives the {@code workflow_node_runs} attempt key)
 * @param loopInput      per-LOOP-node current input text (the body's last output threaded via the back-edge)
 * @param pausedNodeIds  the node id(s) that bubbled up a pause (one per concurrent pause)
 * @param pauseKind      the pause flavour of the triggering pause (see {@link PauseKind}); null if none
 * @param nestedPauses   per-paused-WORKFLOW-node child resume state (the recursive frontier of a
 *                       paused sub-workflow); empty for non-nested pauses. Pre-nesting persisted
 *                       frontiers deserialize with this absent → empty map.
 */
public record DagFrontier(
        List<String> pendingNodeIds,
        Map<String, Integer> remaining,
        Map<String, Integer> liveTokens,
        Map<String, Integer> iteration,
        Map<String, Integer> attempts,
        Map<String, String> loopInput,
        List<String> pausedNodeIds,
        String pauseKind,
        Map<String, NestedPause> nestedPauses
) {
    public DagFrontier {
        pendingNodeIds = pendingNodeIds == null ? List.of() : List.copyOf(pendingNodeIds);
        remaining = remaining == null ? Map.of() : Map.copyOf(remaining);
        liveTokens = liveTokens == null ? Map.of() : Map.copyOf(liveTokens);
        iteration = iteration == null ? Map.of() : Map.copyOf(iteration);
        attempts = attempts == null ? Map.of() : Map.copyOf(attempts);
        loopInput = loopInput == null ? Map.of() : Map.copyOf(loopInput);
        pausedNodeIds = pausedNodeIds == null ? List.of() : List.copyOf(pausedNodeIds);
        nestedPauses = nestedPauses == null ? Map.of() : Map.copyOf(nestedPauses);
    }
}
