package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Executes a Router pattern for dynamic agent selection.
 * State: Stateless (Orchestration Strategy)
 */
@Component
public non-sealed class RouterOrchestrator implements OrchestrationStrategy {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RouterOrchestrator.class);

    private final org.springframework.ai.chat.client.ChatClient.Builder builder;
    private final ai.operativus.agentmanager.compute.service.AgentClientFactory agentClientFactory;
    private final ai.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry;
    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ai.operativus.agentmanager.core.event.AgentRunEventBus eventBus;
    private final OrchestratorMembers orchestratorMembers;
    private final TeamMemberHumanReviewGate humanReviewGate;

    public RouterOrchestrator(org.springframework.ai.chat.client.ChatClient.Builder builder,
                              ai.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry,
                              TransitionValidator transitionValidator,
                              TierEscalationValidator tierEscalationValidator,
                              org.springframework.context.ApplicationEventPublisher eventPublisher,
                              ai.operativus.agentmanager.core.event.AgentRunEventBus eventBus,
                              ai.operativus.agentmanager.compute.service.AgentClientFactory agentClientFactory,
                              OrchestratorMembers orchestratorMembers,
                              TeamMemberHumanReviewGate humanReviewGate) {
        this.builder = builder;
        this.agentClientFactory = agentClientFactory;
        this.agentRegistry = agentRegistry;
        this.transitionValidator = transitionValidator;
        this.tierEscalationValidator = tierEscalationValidator;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
        this.orchestratorMembers = orchestratorMembers;
        this.humanReviewGate = humanReviewGate;
    }

    @Override
    public boolean supports(String teamMode) {
        return "ROUTER".equalsIgnoreCase(teamMode);
    }

    @Override
    public String getStrategyName() {
        return "ROUTER";
    }

    public record RouterDecision(String targetAgentId, String rationale) {}

    /**
     * @summary Processes user intent and routes execution to the single best-fit agent sequentially natively using ChatClient structured routing.
     */
    @Override
    @io.micrometer.observation.annotation.Observed(name = MetricConstants.ORCHESTRATION_OBSERVATION, contextualName = "router")
    public String execute(AgentDefinition rootAgent, String initialInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, AgentOperations runner) {
        String targetAgentId = determineTargetAgent(rootAgent, initialInput, runner, orgId, userId);

        // Phase 2 Governance: Validate DAG transition constraints before LLM-driven dispatch
        transitionValidator.validate(rootAgent.id(), rootAgent.id(), targetAgentId);

        // Phase 2 Governance: Validate security tier escalation (throws SwarmEscalationException if cross-tier)
        tierEscalationValidator.validate(rootAgent.id(), targetAgentId, orgId);

        eventPublisher.publishEvent(new ai.operativus.agentmanager.core.event.AgentSwitchEvent(
                this,
                ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId(),
                rootAgent.id(), targetAgentId, "ROUTER", null,
                ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrchestrationDepth()));

        // REQ-HR follow-up — pre-dispatch HITL gate on Router's chosen agent. Same
        // TEAM_MEMBER_DISPATCH subject type as Sequential/Tasks; strategy=ROUTER in the
        // cursor lets TeamMemberDispatchResumeHandler re-run the orchestrator on approve.
        AgentDefinition targetDef = agentRegistry.findById(targetAgentId, orgId);
        String teamRunId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();
        humanReviewGate.requireApprovalIfConfigured(
                targetDef, teamRunId, rootAgent.id(), targetAgentId, orgId,
                java.util.Map.of(
                        ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.STRATEGY, "ROUTER",
                        ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.CURRENT_INPUT,
                                initialInput == null ? "" : initialInput,
                        ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.SESSION_ID,
                                sessionId == null ? "" : sessionId,
                        ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.USER_ID,
                                userId == null ? "" : userId));

        String memberSession = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, targetAgentId);
        RunResponse response = MemberRunGuard.requireNotPaused(
                runner.run(targetAgentId, initialInput, media, memberSession, userId, orgId, false, null),
                targetAgentId);
        return response.content();
    }


    private String determineTargetAgent(AgentDefinition rootAgent, String initialInput, AgentOperations runner, String orgId, String userId) {
        // REQ-DR-2: resolve members via the flag-aware OrchestratorMembers component so the
        // MemberResolver SPI can filter the roster per request (org tier / feature flag)
        // when agm.member-resolver.enabled=true. Flag-off path is byte-identical to the
        // pre-REQ-DR-2 inline filter.
        List<AgentDefinition> members = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);

        if (members.isEmpty()) {
            throw new ai.operativus.agentmanager.core.exception.BusinessValidationException("Router failed: No valid, active members found for routing.");
        }

        String agentDescriptions = ai.operativus.agentmanager.compute.routing.AgentSelectorPromptTemplate
                .renderCandidates(members, java.util.Map.of());

        String promptString = """
            You are a router. Choose the single best-fit agent to handle the user's request.

            Available Agents (choose exactly one):
            {agents}

            User Request: "{query}"

            Instructions:
            - Respond with the exact id shown after "ID:" for ONE agent in the list above,
              copied verbatim.
            - Never invent an id, and never return a placeholder such as "NO_AGENT_CAPABLE",
              "none", or an empty value.
            - If no agent is an obvious fit, still choose the single most general-purpose agent
              from the list — do not refuse.
            """;

        org.springframework.ai.chat.prompt.PromptTemplate template = new org.springframework.ai.chat.prompt.PromptTemplate(promptString);
        template.add("agents", agentDescriptions);
        template.add("query", initialInput);

        RouterDecision decision = agentClientFactory.buildOrchestrationChatClient(rootAgent, builder)
            .prompt(template.create())
            .call()
            .entity(RouterDecision.class);

        if (decision == null || decision.targetAgentId() == null) {
            throw new RuntimeException("Router failed to map a valid decision.");
        }
        
        String finalId = decision.targetAgentId().trim();
        if (members.stream().noneMatch(a -> a.id().equals(finalId))) {
             throw new RuntimeException("Router selected an invalid agent ID: " + finalId
                     + " — the model returned an id that is not a member of team '" + rootAgent.id()
                     + "'. Try rephrasing the request, or review this team's member agents.");
        }
        String rationale = decision.rationale() != null ? decision.rationale().replaceAll("[\r\n]+", " ") : "none";
        log.info("orchestration.decision mode=ROUTER agent={} target={} rationale=\"{}\"",
                rootAgent.id(), finalId, rationale);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("mode", "ROUTER");
        payload.put("rootAgentId", rootAgent.id());
        payload.put("targetAgentId", finalId);
        payload.put("rationale", rationale);
        payload.put("candidateCount", members.size());
        publishDecisionEvent(payload);

        return finalId;
    }

    private void publishDecisionEvent(java.util.Map<String, Object> payload) {
        if (eventBus == null) return;
        String runId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();
        if (runId == null) return;
        try {
            ai.operativus.agentmanager.core.event.AgentRunEvent event = new ai.operativus.agentmanager.core.event.AgentRunEvent(
                    ai.operativus.agentmanager.core.event.AgentRunEventType.ORCHESTRATOR_DECISION,
                    runId,
                    ai.operativus.agentmanager.core.callback.AgentContextHolder.getAgentId(),
                    null,
                    ai.operativus.agentmanager.core.callback.AgentContextHolder.getSessionId(),
                    ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId(),
                    ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrchestrationDepth(),
                    payload,
                    java.time.Instant.now());
            eventBus.publish(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish ORCHESTRATOR_DECISION event runId={}", runId, ex);
        }
    }
}
