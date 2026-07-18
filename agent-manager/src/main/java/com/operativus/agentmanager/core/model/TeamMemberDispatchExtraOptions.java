package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;

/**
 * Domain Responsibility: REQ-HR follow-up — key constants for the
 *     {@link HumanReviewPending#getOptions} map when {@code subject_type =
 *     TEAM_MEMBER_DISPATCH}. Centralizes the contract so the pre-dispatch
 *     gate and the resume handler read/write through the same names.
 *
 *     <p>See {@link HumanReviewSubjectType#TEAM_MEMBER_DISPATCH} javadoc for
 *     the full encoding convention.
 *
 * State: Stateless (constants only).
 */
public final class TeamMemberDispatchExtraOptions {

    /** The team id whose orchestrator is paused. */
    public static final String TEAM_ID = "teamId";

    /** The member agent id that would have been dispatched (duplicate of subject_id). */
    public static final String MEMBER_AGENT_ID = "memberAgentId";

    // ─────────────────────────────────────────────────────────────────────────
    // Resume cursor — populated by team orchestrators at pause time so the
    // resume handler can re-enter the loop at the right position with the
    // right state. Orchestrator-specific keys live alongside the shared ones.
    // ─────────────────────────────────────────────────────────────────────────

    /** The team-run strategy name at pause time (SEQUENTIAL / ROUTER / PLANNER / SWARM). */
    public static final String STRATEGY = "strategy";

    /** The textual input that would have been fed to the paused member. For Sequential
     *  this is the prior member's output (or the team's initialInput at index 0). */
    public static final String CURRENT_INPUT = "currentInput";

    /** The session id under which the team is running. */
    public static final String SESSION_ID = "sessionId";

    /** The user id that initiated the team run. */
    public static final String USER_ID = "userId";

    /** Zero-based index of the paused member in the orchestrator's active-member list
     *  at the time of pause. Snapshot — orchestrators must re-resolve members on
     *  resume and validate that the paused member is still at the recorded index
     *  (defensive against concurrent team-edit). */
    public static final String MEMBER_INDEX = "memberIndex";

    /** REQ-TT-6 — id of the task whose dispatch the gate paused. Populated for
     *  strategy=TASKS only. The TasksOrchestrator drains the rest of the worker
     *  loop in parallel (D8); on resume, only this task re-enters dispatch. */
    public static final String TASK_ID = "taskId";

    /** Planner-strategy resume — encoded plan: {@code List<Map<String,Object>>} where
     *  each entry is {@code {stepNumber, targetAgentId, taskDescription}}. Persisted
     *  in the cursor so the resume handler can re-enter at memberIndex without re-running
     *  the LLM plan-generation step (which is non-deterministic and would yield a
     *  different plan on each call). */
    public static final String PLAN = "plan";

    private TeamMemberDispatchExtraOptions() {
    }
}
