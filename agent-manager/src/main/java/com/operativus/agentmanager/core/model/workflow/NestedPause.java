package com.operativus.agentmanager.core.model.workflow;

/**
 * Domain Responsibility: The persisted resume state for a WORKFLOW node whose child sub-workflow
 *     paused on a HITL gate (REQ-DR-5, DAG-6 follow-up). Carried on the paused {@link StepOutput}
 *     out of {@code SubWorkflowNodeExecutor}, recorded into the parent {@link DagFrontier} keyed by
 *     the WORKFLOW node id, and handed back to the executor's {@code resumeNested} on settle so the
 *     child re-enters its exact graph. {@link #childRunId} is the derived child run identity minted
 *     on the FIRST invocation and reused across resumes — the child's {@code workflow_node_runs}
 *     rows accumulate under it, which is what lets the child's resume rehydrate completed outputs.
 *     The structure recurses naturally: a child frontier may itself carry {@code nestedPauses}.
 * State: Stateless (Immutable Record carrier; JSONB-embedded inside {@link DagFrontier})
 *
 * @param childRunId      the derived child run id (stable across pause/resume cycles)
 * @param childWorkflowId the child workflow whose graph is reloaded on resume
 * @param childFrontier   the child scheduler's own frontier snapshot at its pause
 */
public record NestedPause(
        String childRunId,
        String childWorkflowId,
        DagFrontier childFrontier
) {}
