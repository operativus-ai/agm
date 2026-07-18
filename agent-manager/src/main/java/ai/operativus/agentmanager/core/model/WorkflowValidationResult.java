package ai.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Non-throwing report from {@link ai.operativus.agentmanager.compute.workflow.WorkflowDagValidator}
 * for the editor's validation overlay (REQ-DR-5). Unlike {@code validate(...)} (which throws on a
 * cycle and only logs orphans), this surfaces both problems for display:
 * <ul>
 *   <li>{@code hasCycle} / {@code cycleMessage} — a cycle reachable from the start step (the edit
 *       paths block these at write time, so a persisted graph is normally cycle-free).</li>
 *   <li>{@code unreachableStepIds} — orphan steps with no path from the start step (common mid-edit:
 *       a step added with no inbound edge, or whose only inbound edge was deleted).</li>
 * </ul>
 * {@code valid} is true iff there is no cycle and no unreachable step. An edge-less (legacy
 * flat-list) or empty workflow is reported valid with no orphans.
 */
public record WorkflowValidationResult(
        boolean valid,
        boolean hasCycle,
        String cycleMessage,
        List<String> unreachableStepIds) {

    public static WorkflowValidationResult ok() {
        return new WorkflowValidationResult(true, false, null, List.of());
    }
}
