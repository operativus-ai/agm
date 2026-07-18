package com.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: REQ-HR-2 — discriminator for which kind of subject a
 *     {@code human_review_pending} row represents. Drives the resume-handler
 *     dispatch in {@code HumanReviewService.decide} — each subject type has its
 *     own {@code HumanReviewResumeHandler} SPI implementation that knows how to
 *     resume the underlying run (workflow vs agent vs team).
 *
 *     <ul>
 *       <li>{@link #WORKFLOW_STEP} — pause came from a CONDITION/ROUTER/etc.
 *           workflow step. Resume re-enters the workflow dispatcher loop.
 *           Handler lands in REQ-HR-3.</li>
 *       <li>{@link #AGENT_TOOL_CALL} — pause came from a tool invocation with
 *           HumanReview attached at the agent level. Resume injects the operator
 *           decision into the Spring AI tool-execution flow. Handler lands in
 *           REQ-HR-4.</li>
 *       <li>{@link #TEAM_MEMBER_DISPATCH} — pause came from a team orchestrator
 *           selecting a HumanReview-gated member. Resume re-enters the team
 *           orchestrator loop. Handler lands when team orchestrators are wired
 *           (post REQ-HR-3..5).
 *           <p><strong>Encoding convention</strong> (mirrored by the pre-dispatch
 *           gate and the resume handler — change in lockstep):
 *           <ul>
 *             <li>{@code subject_id} = the {@code memberAgentId} that would have
 *                 been dispatched (analogue to {@code AGENT_TOOL_CALL}'s
 *                 {@code toolName})</li>
 *             <li>{@code run_id} = the team run id (the parent run that's pausing)</li>
 *             <li>{@code options.teamId} = the team id (carried so the resume
 *                 handler can target the right orchestrator instance)</li>
 *             <li>{@code options.memberAgentId} = duplicate of {@code subject_id}
 *                 in the options map for symmetric read-side parsing</li>
 *           </ul>
 *           See {@code TeamMemberDispatchExtraOptions} for the key constants.</li>
 *     </ul>
 *
 * State: Stateless (Enum)
 */
public enum HumanReviewSubjectType {
    WORKFLOW_STEP, AGENT_TOOL_CALL, TEAM_MEMBER_DISPATCH;

    /** Returns the enum constant for the given string, case-insensitive; null / unknown returns null. */
    public static HumanReviewSubjectType fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
