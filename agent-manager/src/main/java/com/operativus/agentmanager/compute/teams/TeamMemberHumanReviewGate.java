package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.control.service.HumanReviewService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.exception.TeamMemberPausedException;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.RequiredAction;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain Responsibility: REQ-HR follow-up — pre-dispatch HITL gate for team
 *     orchestrators. Called by each orchestrator (Sequential / Router / Planner
 *     / Swarm) BEFORE invoking {@code agentService.run(memberAgentId, ...)} on a
 *     member whose {@code AgentDefinition.humanReview} (plumbed by PR-1) is set
 *     with an active pause flag ({@code requiresConfirmation /
 *     requiresUserInput / requiresOutputReview} = true).
 *
 *     <p>On a pause hit, the gate:
 *     <ol>
 *       <li>Writes a {@code human_review_pending} row via
 *           {@code HumanReviewService.pauseFor(TEAM_MEMBER_DISPATCH, ...)}
 *           using the convention pinned on {@link HumanReviewSubjectType#TEAM_MEMBER_DISPATCH}.</li>
 *       <li>Throws {@link TeamMemberPausedException} carrying a
 *           {@link RequiredAction#teamMemberDispatchApproval} payload so the
 *           team-branch catch in {@code AgentService.run} can lift the pause
 *           into the team-level run row — same mechanism already used for
 *           post-dispatch member pauses (see {@link MemberRunGuard}).</li>
 *     </ol>
 *
 *     <p><strong>No-op path:</strong> if {@code humanReview} is null OR
 *     {@code isPauseActive()} is false (e.g. policy-only config without an
 *     active gate flag), the gate returns silently and the orchestrator
 *     proceeds with normal dispatch.
 *
 *     <p><strong>Distinction from {@link MemberRunGuard}:</strong> the guard
 *     fires AFTER a member ran (catches downstream PAUSED status from a tool
 *     approval inside the member's own advisor chain). This gate fires BEFORE
 *     dispatch and is driven by the team-member-level HumanReview config.
 *
 *     <p><strong>PR-2 scope:</strong> this class compiles and is unit-tested
 *     but has zero call sites yet. PR-3 wires it into the orchestrator
 *     dispatch path. Dead-code staging mirrors the SPI seam pattern from PR
 *     #875 / PR-1.
 *
 * State: Stateless (Spring bean; thread-safe).
 */
@Component
public class TeamMemberHumanReviewGate {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberHumanReviewGate.class);

    private final HumanReviewService humanReviewService;

    public TeamMemberHumanReviewGate(HumanReviewService humanReviewService) {
        this.humanReviewService = humanReviewService;
    }

    /**
     * Inspect the member's {@link AgentDefinition#humanReview} and, if pause is
     * active, persist a pending row and throw
     * {@link TeamMemberPausedException}. Returns silently on the no-op path.
     *
     * @param memberDefinition the resolved AgentDefinition for the about-to-be-dispatched
     *                         member (PR-1 plumbed {@code humanReview} onto this DTO)
     * @param teamRunId        the team's run id (the parent run that's pausing)
     * @param teamId           the team's id (carried in options so the resume handler
     *                         can target the right orchestrator instance)
     * @param memberAgentId    the member agent id that would have been dispatched
     * @param orgId            the caller's org id (tenant scoping for the pending row)
     */
    public void requireApprovalIfConfigured(AgentDefinition memberDefinition,
                                            String teamRunId,
                                            String teamId,
                                            String memberAgentId,
                                            String orgId) {
        requireApprovalIfConfigured(memberDefinition, teamRunId, teamId, memberAgentId, orgId, null);
    }

    /**
     * Overload accepting orchestrator-supplied resume-cursor data merged into the
     * pending row's options map. Callers (Sequential / Router / Planner / Swarm)
     * populate the keys in {@link TeamMemberDispatchExtraOptions} that are
     * meaningful for their strategy. The shared {@code TEAM_ID} and
     * {@code MEMBER_AGENT_ID} keys are always written by this method and override
     * any conflicting values in {@code extraCursorData}.
     */
    public void requireApprovalIfConfigured(AgentDefinition memberDefinition,
                                            String teamRunId,
                                            String teamId,
                                            String memberAgentId,
                                            String orgId,
                                            Map<String, Object> extraCursorData) {
        if (memberDefinition == null) {
            return;
        }
        HumanReview review = memberDefinition.humanReview();
        if (review == null || !review.isPauseActive()) {
            return;
        }
        // Resume path — if AgentService.continueRun has seeded the approved set
        // with this member id, the operator already approved on the prior pause;
        // skip the gate so the orchestrator can dispatch normally.
        if (AgentContextHolder.approvedTeamMembers.isBound()
                && AgentContextHolder.approvedTeamMembers.get().contains(memberAgentId)) {
            log.debug("TeamMemberHumanReviewGate: skipping gate for member {} (already approved on resume)",
                    memberAgentId);
            return;
        }

        Map<String, Object> extraOptions = new HashMap<>();
        if (extraCursorData != null) {
            extraOptions.putAll(extraCursorData);
        }
        // Shared identity keys win over any caller-supplied conflicts.
        extraOptions.put(TeamMemberDispatchExtraOptions.TEAM_ID, teamId);
        extraOptions.put(TeamMemberDispatchExtraOptions.MEMBER_AGENT_ID, memberAgentId);

        HumanReviewPending pending = humanReviewService.pauseFor(
                HumanReviewSubjectType.TEAM_MEMBER_DISPATCH,
                memberAgentId,
                teamRunId,
                orgId,
                "Team requires permission to dispatch member: " + memberAgentId,
                review,
                extraOptions,
                SecurityPrincipals.SYSTEM_PRINCIPAL);

        log.info("TeamMemberHumanReviewGate: paused team {} run {} on member {} (pending {})",
                teamId, teamRunId, memberAgentId, pending.getId());

        RequiredAction ra = RequiredAction.teamMemberDispatchApproval(
                teamId, memberAgentId, pending.getId(),
                null, null, null);
        throw new TeamMemberPausedException(teamRunId, memberAgentId, ra);
    }
}
