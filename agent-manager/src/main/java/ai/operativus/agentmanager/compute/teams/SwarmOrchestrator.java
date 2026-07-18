package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Domain Responsibility: Executes an autonomous Swarm pattern where agents dynamically hand off control to each other.
 * State: Stateless (Orchestration Strategy)
 */
@Component
public non-sealed class SwarmOrchestrator implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(SwarmOrchestrator.class);

    private final org.springframework.ai.chat.client.ChatClient.Builder builder;
    private final ai.operativus.agentmanager.compute.service.AgentClientFactory agentClientFactory;
    private final ai.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry;
    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final ai.operativus.agentmanager.compute.memory.EphemeralSwarmContext ephemeralSwarmContext;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ai.operativus.agentmanager.core.event.AgentRunEventBus eventBus;
    private final OrchestratorMembers orchestratorMembers;
    private final TeamMemberHumanReviewGate humanReviewGate;

    public SwarmOrchestrator(org.springframework.ai.chat.client.ChatClient.Builder builder,
                             ai.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry,
                             TransitionValidator transitionValidator,
                             TierEscalationValidator tierEscalationValidator,
                             ai.operativus.agentmanager.compute.memory.EphemeralSwarmContext ephemeralSwarmContext,
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
        this.ephemeralSwarmContext = ephemeralSwarmContext;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
        this.orchestratorMembers = orchestratorMembers;
        this.humanReviewGate = humanReviewGate;
    }

    @Override
    public boolean supports(String teamMode) {
        return "SWARM".equalsIgnoreCase(teamMode);
    }

    @Override
    public String getStrategyName() {
        return "SWARM";
    }

    public record SwarmSubtask(String targetAgentId, String specificQuery) {}
    public record OrchestratorResponse(List<SwarmSubtask> subtasks, String rationale) {}

    /**
     * @summary Processes a state machine of dynamic agent handoffs natively using Spring AI's OrchestratorWorkersWorkflow pattern and Java 25 Structured Concurrency.
     * @logic The orchestrator (ChatClient) determines subtasks upfront, workers (Agents) process them independently in StructuredTaskScope, and results are combined into a final response.
     */
    @Override
    @io.micrometer.observation.annotation.Observed(name = MetricConstants.ORCHESTRATION_OBSERVATION, contextualName = "swarm")
    public String execute(AgentDefinition rootAgent, String initialInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, AgentOperations runner) {
        // REQ-DR-2: resolve members via OrchestratorMembers so the MemberResolver SPI can
        // filter the roster per request under agm.member-resolver.enabled=true. Flag-off
        // path is byte-identical to the pre-REQ-DR-2 inline filter.
        List<AgentDefinition> members = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);

        if (members.isEmpty()) {
            throw new ai.operativus.agentmanager.core.exception.BusinessValidationException("Swarm failed: No valid, active members found.");
        }

        // F7: immutable snapshot of valid member IDs used to reject LLM-hallucinated targets below
        final java.util.Set<String> validMemberIds = members.stream()
            .map(ai.operativus.agentmanager.core.model.definitions.AgentDefinition::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

        String agentDescriptions = members.stream()
            .map(a -> String.format("- %s (ID: %s): %s", a.name(), a.id(), a.description()))
            .collect(java.util.stream.Collectors.joining("\n"));

        String promptString = """
            You are a Swarm Orchestrator. Break down the user's complex request into specific subtasks.
            Delegate these subtasks to the most appropriate agents from the available pool. You can assign multiple tasks if necessary.
            
            Available Agents:
            {agents}
            
            User Request: "{query}"
            """;

        org.springframework.ai.chat.prompt.PromptTemplate template = new org.springframework.ai.chat.prompt.PromptTemplate(promptString);
        template.add("agents", agentDescriptions);
        template.add("query", initialInput);

        OrchestratorResponse orchestratorResponse = agentClientFactory.buildOrchestrationChatClient(rootAgent, builder)
            .prompt(template.create())
            .call()
            .entity(OrchestratorResponse.class);

        if (orchestratorResponse == null || orchestratorResponse.subtasks() == null || orchestratorResponse.subtasks().isEmpty()) {
             return "Swarm Orchestrator determined no subtasks were required.";
        }

        String rationale = orchestratorResponse.rationale() != null
                ? orchestratorResponse.rationale().replaceAll("[\r\n]+", " ") : "none";
        java.util.List<String> subtaskAgents = orchestratorResponse.subtasks().stream()
                .map(SwarmSubtask::targetAgentId).toList();
        log.info("orchestration.decision mode=SWARM agent={} subtasks={} targets={} rationale=\"{}\"",
                rootAgent.id(), orchestratorResponse.subtasks().size(), subtaskAgents, rationale);

        java.util.Map<String, Object> decisionPayload = new java.util.HashMap<>();
        decisionPayload.put("mode", "SWARM");
        decisionPayload.put("rootAgentId", rootAgent.id());
        decisionPayload.put("subtaskCount", orchestratorResponse.subtasks().size());
        decisionPayload.put("subtaskAgents", subtaskAgents);
        decisionPayload.put("rationale", rationale);
        publishDecisionEvent(decisionPayload);

        List<String> subtaskResults = new java.util.ArrayList<>();
        String swarmContextId = sessionId + "-swarm-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        final String parentRunId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();
        final String parentUserId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getUserId();
        final String parentOrgId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId();
        final String parentSessionId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getSessionId();
        final Integer parentDepth = ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrchestrationDepth();
        // approvedTools is bound by AgentService.continueRun on the resume path so HITL-approved
        // tools bypass the gate inside members. Fresh VTs do NOT inherit ScopedValues, so we
        // capture a parent-side snapshot here and rebind a fresh per-member copy inside the
        // member task body. Set.copyOf returns an immutable snapshot; the per-member new
        // HashSet<>(snapshot) below gives each member its own mutable view (no aliasing).
        final java.util.Set<String> parentApprovedToolsSnapshot =
                ai.operativus.agentmanager.core.callback.AgentContextHolder.approvedTools.isBound()
                        ? java.util.Set.copyOf(ai.operativus.agentmanager.core.callback.AgentContextHolder.approvedTools.get())
                        : null;

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<RunResponse>> scopeTasks = new java.util.ArrayList<>();
            for (SwarmSubtask subtask : orchestratorResponse.subtasks()) {
                 // F7: reject LLM-hallucinated agent IDs that aren't actual team members
                 if (!validMemberIds.contains(subtask.targetAgentId())) {
                     log.warn("Swarm subtask targets non-member agent '{}' — aborting.", subtask.targetAgentId());
                     throw new ai.operativus.agentmanager.core.exception.BusinessValidationException(
                             "Swarm subtask references non-member agent '" + subtask.targetAgentId() + "'.");
                 }
                 transitionValidator.validate(rootAgent.id(), rootAgent.id(), subtask.targetAgentId());
                 tierEscalationValidator.validate(rootAgent.id(), subtask.targetAgentId(), orgId);

                 eventPublisher.publishEvent(new ai.operativus.agentmanager.core.event.AgentSwitchEvent(
                         this, parentRunId, rootAgent.id(), subtask.targetAgentId(), "SWARM",
                         orchestratorResponse.rationale(), parentDepth));

                 final SwarmSubtask currentSubtask = subtask;
                 // Fresh mutable copy per member so AgentContextHolder.approveTool() mutations
                 // on one member do NOT leak to siblings.
                 final java.util.Set<String> memberApprovedTools = parentApprovedToolsSnapshot != null
                         ? new java.util.HashSet<>(parentApprovedToolsSnapshot)
                         : null;
                 scopeTasks.add(executor.submit(io.micrometer.context.ContextSnapshotFactory.builder().build().captureAll().wrap(() -> {
                     ScopedValue.Carrier carrier = ScopedValue
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.currentRunId, parentRunId)
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.userId, parentUserId)
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, parentOrgId)
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.sessionId, parentSessionId)
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orchestrationDepth, parentDepth != null ? parentDepth : 0)
                         .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.telemetry, new ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator());
                     if (memberApprovedTools != null) {
                         carrier = carrier.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.approvedTools, memberApprovedTools);
                     }
                     return carrier.call(() -> {
                             ai.operativus.agentmanager.core.callback.AgentContextHolder.populateMdcFromScopedValues();
                             try {
                                 // REQ-HR follow-up — pre-dispatch HITL gate on each swarm subtask's target.
                                 AgentDefinition subtaskDef = agentRegistry.findById(currentSubtask.targetAgentId(), orgId);
                                 humanReviewGate.requireApprovalIfConfigured(
                                         subtaskDef, parentRunId, rootAgent.id(), currentSubtask.targetAgentId(), orgId,
                                         java.util.Map.of(
                                                 ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.STRATEGY, "SWARM",
                                                 ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.CURRENT_INPUT,
                                                         currentSubtask.specificQuery() == null ? "" : currentSubtask.specificQuery(),
                                                 ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.SESSION_ID,
                                                         sessionId == null ? "" : sessionId,
                                                 ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.USER_ID,
                                                         userId == null ? "" : userId));
                                 String branchSessionId = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, currentSubtask.targetAgentId());
                                 ephemeralSwarmContext.mergeFrom(swarmContextId, branchSessionId);
                                 return MemberRunGuard.requireNotPaused(
                                         runner.run(currentSubtask.targetAgentId(), currentSubtask.specificQuery(), media, branchSessionId, userId, orgId, false, null),
                                         currentSubtask.targetAgentId());
                             } finally {
                                 ai.operativus.agentmanager.core.callback.AgentContextHolder.clearMdcFromScopedValues();
                             }
                         });
                 })));
            }

            // F11/F12: best-effort partial failure — PAUSED propagates fail-all; other
            // exceptions are logged and surfaced inline so remaining subtask outputs are returned.
            for (int i = 0; i < scopeTasks.size(); i++) {
                SwarmSubtask sub = orchestratorResponse.subtasks().get(i);
                try {
                    subtaskResults.add(scopeTasks.get(i).get().content());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof ai.operativus.agentmanager.core.exception.TeamMemberPausedException tpe) {
                        scopeTasks.forEach(other -> { if (!other.isDone()) other.cancel(true); });
                        throw tpe;
                    }
                    log.warn("Swarm subtask '{}' failed — included as error in output: {}",
                            sub.targetAgentId(), cause.getMessage(), cause);
                    subtaskResults.add("[ERROR: " + cause.getMessage() + "]");
                } catch (InterruptedException e) {
                    scopeTasks.forEach(other -> { if (!other.isDone()) other.cancel(true); });
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Swarm execution interrupted", e);
                }
            }
        }

        StringBuilder aggregatedContent = new StringBuilder("### Swarm Synthesis:\n\n");
        aggregatedContent.append("**Orchestrator Rationale:** ").append(orchestratorResponse.rationale()).append("\n\n");
        for (int i = 0; i < orchestratorResponse.subtasks().size(); i++) {
             SwarmSubtask sub = orchestratorResponse.subtasks().get(i);
             aggregatedContent.append("**Worker [").append(sub.targetAgentId()).append("]**:\n")
                              .append(subtaskResults.get(i)).append("\n\n");
        }

        // Flush the ephemeral swarm context after successful completion
        ephemeralSwarmContext.flush(swarmContextId);

        return aggregatedContent.toString();
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
