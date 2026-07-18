package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Executes a team of agents in a strict Sequential (Chain) pattern.
 * State: Stateless (Orchestration Strategy)
 */
@Component
public non-sealed class SequentialOrchestrator implements OrchestrationStrategy {

    private final AgentRegistry agentRegistry;
    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final OrchestratorMembers orchestratorMembers;
    private final TeamMemberHumanReviewGate humanReviewGate;

    public SequentialOrchestrator(AgentRegistry agentRegistry,
                                  TransitionValidator transitionValidator,
                                  TierEscalationValidator tierEscalationValidator,
                                  OrchestratorMembers orchestratorMembers,
                                  TeamMemberHumanReviewGate humanReviewGate) {
        this.agentRegistry = agentRegistry;
        this.transitionValidator = transitionValidator;
        this.tierEscalationValidator = tierEscalationValidator;
        this.orchestratorMembers = orchestratorMembers;
        this.humanReviewGate = humanReviewGate;
    }

    @Override
    public boolean supports(String teamMode) {
        return "SEQUENTIAL".equalsIgnoreCase(teamMode);
    }

    @Override
    public String getStrategyName() {
        return "SEQUENTIAL";
    }

    /**
     * @summary Chains configured agents sequentially, feeding outputs into inputs.
     * @logic Resolves the declared member ids to active (not in maintenance, not self)
     *        agent definitions via {@link OrchestratorMembers#resolveActive}, validates
     *        tier escalation and DAG transition constraints for the full chain before any
     *        dispatch (fail-fast), then loops over the filtered list in declared order,
     *        blocking on each turn and propagating the textual RunResponse output as the
     *        next member's input prompt.
     */
    @Override
    @io.micrometer.observation.annotation.Observed(name = MetricConstants.ORCHESTRATION_OBSERVATION, contextualName = "sequential")
    public String execute(AgentDefinition rootAgent, String initialInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, AgentOperations runner) {
        List<AgentDefinition> activeMembers = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);
        if (activeMembers.isEmpty()) {
            return "Team has no members.";
        }

        // Fail-fast governance: validate the full chain before any LLM call.
        for (AgentDefinition member : activeMembers) {
            transitionValidator.validate(rootAgent.id(), rootAgent.id(), member.id());
            tierEscalationValidator.validate(rootAgent.id(), member.id(), orgId);
        }

        String currentInput = initialInput;
        RunResponse lastResponse = null;
        String teamRunId = AgentContextHolder.getCurrentRunId();
        for (int memberIndex = 0; memberIndex < activeMembers.size(); memberIndex++) {
            AgentDefinition member = activeMembers.get(memberIndex);
            // REQ-HR follow-up — pre-dispatch HITL gate. No-op when the member's
            // humanReview is null/inactive; throws TeamMemberPausedException
            // otherwise. Cursor data is captured into the pending row's options
            // map so the resume handler can re-enter at the right index.
            humanReviewGate.requireApprovalIfConfigured(
                    member, teamRunId, rootAgent.id(), member.id(), orgId,
                    buildCursorData(currentInput, sessionId, userId, memberIndex));

            // Followups suppressed for internal team member calls; team-level
            // followup generation is the outer caller's responsibility. Matches
            // the contract used by Router/Planner/Swarm.
            String memberSession = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, member.id());
            lastResponse = MemberRunGuard.requireNotPaused(
                    runner.run(member.id(), currentInput, media, memberSession, userId, orgId, false, null),
                    member.id());
            currentInput = lastResponse.content();
            media = null; // Clear media so it isn't resent to subsequent agents
        }
        return lastResponse != null ? lastResponse.content() : "No response generated.";
    }

    /**
     * REQ-HR follow-up — resume entry point invoked by
     * {@code TeamMemberDispatchResumeHandler} (via {@code AgentService.continueRun})
     * when an operator decides on a {@code TEAM_MEMBER_DISPATCH} pause.
     *
     * <p>Re-resolves active members and iterates from {@code fromIndex} with
     * {@code initialInput} as the current input (the value captured at pause
     * time and persisted in {@code human_review_pending.options}). The caller
     * is responsible for setting {@code AgentContextHolder.approvedTeamMembers}
     * if the resumed member should not re-fire the gate.
     *
     * <p>Returns the final member's content like {@link #execute}.
     */
    public String resumeAt(AgentDefinition rootAgent, int fromIndex, String initialInput,
                           List<Media> media, String sessionId, String userId, String orgId,
                           AgentOperations runner) {
        List<AgentDefinition> activeMembers = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);
        if (activeMembers.isEmpty()) {
            return "Team has no members.";
        }
        if (fromIndex >= activeMembers.size()) {
            // Skipped past the last member — nothing left to run.
            return "";
        }

        String currentInput = initialInput;
        RunResponse lastResponse = null;
        String teamRunId = AgentContextHolder.getCurrentRunId();
        for (int memberIndex = fromIndex; memberIndex < activeMembers.size(); memberIndex++) {
            AgentDefinition member = activeMembers.get(memberIndex);
            humanReviewGate.requireApprovalIfConfigured(
                    member, teamRunId, rootAgent.id(), member.id(), orgId,
                    buildCursorData(currentInput, sessionId, userId, memberIndex));

            String memberSession = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, member.id());
            lastResponse = MemberRunGuard.requireNotPaused(
                    runner.run(member.id(), currentInput, media, memberSession, userId, orgId, false, null),
                    member.id());
            currentInput = lastResponse.content();
            media = null;
        }
        return lastResponse != null ? lastResponse.content() : "";
    }

    private static Map<String, Object> buildCursorData(String currentInput, String sessionId,
                                                       String userId, int memberIndex) {
        Map<String, Object> cursor = new HashMap<>();
        cursor.put(TeamMemberDispatchExtraOptions.STRATEGY, "SEQUENTIAL");
        cursor.put(TeamMemberDispatchExtraOptions.CURRENT_INPUT, currentInput);
        cursor.put(TeamMemberDispatchExtraOptions.SESSION_ID, sessionId);
        cursor.put(TeamMemberDispatchExtraOptions.USER_ID, userId);
        cursor.put(TeamMemberDispatchExtraOptions.MEMBER_INDEX, memberIndex);
        return cursor;
    }

}
