package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Domain Responsibility: Implements the Plan-and-Solve orchestration pattern. Unlike the static
 * {@link SequentialOrchestrator}, this orchestrator dynamically decomposes a complex user request
 * into an ordered execution plan using an LLM, then sequentially delegates each planned step to
 * the most appropriate member agent.
 *
 * State: Stateless (Orchestration Strategy)
 *
 * @architecture This follows the same {@code @Component + supports()} auto-registration pattern
 *               used by all other orchestrators. The planning LLM call uses a dedicated ChatClient
 *               built from the injected builder — it does NOT reuse any agent-specific client.
 *               Execution of planned steps uses synchronous blocking calls on Virtual Threads.
 */
@Component
public non-sealed class PlannerOrchestrator implements OrchestrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlannerOrchestrator.class);

    private final ChatClient.Builder builder;
    private final ai.operativus.agentmanager.compute.service.AgentClientFactory agentClientFactory;
    private final AgentRegistry agentRegistry;
    private final TransitionValidator transitionValidator;
    private final TierEscalationValidator tierEscalationValidator;
    private final ai.operativus.agentmanager.core.event.AgentRunEventBus eventBus;
    private final OrchestratorMembers orchestratorMembers;
    private final TeamMemberHumanReviewGate humanReviewGate;

    public PlannerOrchestrator(ChatClient.Builder builder,
                               AgentRegistry agentRegistry,
                               TransitionValidator transitionValidator,
                               TierEscalationValidator tierEscalationValidator,
                               ai.operativus.agentmanager.core.event.AgentRunEventBus eventBus,
                               ai.operativus.agentmanager.compute.service.AgentClientFactory agentClientFactory,
                               OrchestratorMembers orchestratorMembers,
                               TeamMemberHumanReviewGate humanReviewGate) {
        this.builder = builder;
        this.agentClientFactory = agentClientFactory;
        this.agentRegistry = agentRegistry;
        this.transitionValidator = transitionValidator;
        this.tierEscalationValidator = tierEscalationValidator;
        this.eventBus = eventBus;
        this.orchestratorMembers = orchestratorMembers;
        this.humanReviewGate = humanReviewGate;
    }

    @Override
    public boolean supports(String teamMode) {
        return "PLANNER".equalsIgnoreCase(teamMode);
    }

    @Override
    public String getStrategyName() {
        return "PLANNER";
    }

    /**
     * Structured record for a single planned execution step.
     * The LLM generates a JSON array of these during the planning phase.
     */
    public record PlannedStep(int stepNumber, String targetAgentId, String taskDescription) {}

    /**
     * Wrapper record for the LLM's structured plan output. Using a wrapper ensures
     * Spring AI's BeanOutputConverter can reliably parse the top-level JSON object.
     */
    public record ExecutionPlan(List<PlannedStep> steps) {}

    /**
     * @summary Orchestrates a Plan-and-Solve execution lifecycle.
     * @logic
     * Phase 1 (Plan): Sends the user's prompt alongside descriptions of all available team members
     * to an LLM, requesting a structured {@link ExecutionPlan} as the response. The LLM decomposes
     * the complex request into discrete, ordered steps mapped to specific agent IDs.
     *
     * Phase 2 (Solve): Iterates over the planned steps sequentially. Each step delegates execution
     * to the target agent via the {@link AgentOperations} runner, passing the step's task description
     * augmented with accumulated context from prior steps. Governance validators ensure each
     * delegation respects transition and tier escalation constraints.
     *
     * Phase 3 (Synthesize): Aggregates all step outputs and generates a final cohesive summary
     * using the planner LLM.
     */
    @Override
    @io.micrometer.observation.annotation.Observed(name = MetricConstants.ORCHESTRATION_OBSERVATION, contextualName = "planner")
    public String execute(AgentDefinition rootAgent, String initialInput, List<Media> media,
                          String sessionId, String userId, String orgId,
                          Boolean generateFollowups, AgentOperations runner) {

        // REQ-DR-2: resolve members via OrchestratorMembers so the MemberResolver SPI can
        // filter the roster per request under agm.member-resolver.enabled=true. Flag-off
        // path is byte-identical to the pre-REQ-DR-2 inline filter.
        // --- Phase 1: Plan ---
        List<AgentDefinition> members = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);

        if (members.isEmpty()) {
            return "Planner failed: No valid, active members available for task decomposition.";
        }

        ExecutionPlan plan = generatePlan(rootAgent, initialInput, members);

        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            log.warn("Planner LLM returned an empty or null plan. Falling back to direct response.");
            return "I was unable to decompose this request into actionable steps. Please try rephrasing.";
        }

        String planSummary = plan.steps().stream()
                .map(s -> {
                    String task = s.taskDescription() != null
                            ? s.taskDescription().replaceAll("[\r\n]+", " ")
                            : "";
                    if (task.length() > 120) task = task.substring(0, 120) + "...";
                    return s.stepNumber() + ":" + s.targetAgentId() + "(" + task + ")";
                })
                .collect(java.util.stream.Collectors.joining(" | "));
        log.info("orchestration.plan mode=PLANNER agent={} steps={} plan=[{}]",
                rootAgent.id(), plan.steps().size(), planSummary);

        java.util.List<String> stepAgents = plan.steps().stream()
                .map(PlannedStep::targetAgentId).toList();
        java.util.Map<String, Object> decisionPayload = new java.util.HashMap<>();
        decisionPayload.put("mode", "PLANNER");
        decisionPayload.put("rootAgentId", rootAgent.id());
        decisionPayload.put("stepCount", plan.steps().size());
        decisionPayload.put("stepAgents", stepAgents);
        publishDecisionEvent(decisionPayload);

        // --- Phase 2: Solve ---
        // F7: build immutable set of valid member IDs from the resolved+filtered member list.
        // Steps that reference any other agent ID are rejected before dispatch.
        var validMemberIds = members.stream()
                .map(AgentDefinition::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<String> stepOutputs = new ArrayList<>();
        StringBuilder accumulatedContext = new StringBuilder();

        for (PlannedStep step : plan.steps()) {
            log.info("Executing Plan Step {}/{}: agent='{}', task='{}'",
                    step.stepNumber(), plan.steps().size(), step.targetAgentId(), step.taskDescription());

            // F7: reject LLM-hallucinated agent IDs that aren't actual team members
            if (!validMemberIds.contains(step.targetAgentId())) {
                log.warn("Planner step {} targets non-member agent '{}' — aborting plan.",
                        step.stepNumber(), step.targetAgentId());
                throw new ai.operativus.agentmanager.core.exception.BusinessValidationException(
                        "Planner step " + step.stepNumber() + " references non-member agent '" + step.targetAgentId() + "'.");
            }

            // Governance: Validate transition and tier escalation
            transitionValidator.validate(rootAgent.id(), rootAgent.id(), step.targetAgentId());
            tierEscalationValidator.validate(rootAgent.id(), step.targetAgentId(), orgId);

            // Construct the step's input, augmenting with prior step outputs for context continuity
            String stepInput = step.taskDescription();
            if (!accumulatedContext.isEmpty()) {
                stepInput = "Context from previous steps:\n" + accumulatedContext + "\n\nCurrent Task: " + step.taskDescription();
            }

            try {
                // REQ-HR follow-up — pre-dispatch HITL gate on each planned step's target.
                // Persist the full plan in cursor extras so resume can re-enter at
                // memberIndex without regenerating it (the planner LLM is non-deterministic).
                AgentDefinition memberDef = agentRegistry.findById(step.targetAgentId(), orgId);
                String teamRunId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();
                java.util.Map<String, Object> cursor = new java.util.HashMap<>();
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.STRATEGY, "PLANNER");
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.CURRENT_INPUT, stepInput == null ? "" : stepInput);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.SESSION_ID, sessionId == null ? "" : sessionId);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.USER_ID, userId == null ? "" : userId);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.MEMBER_INDEX, step.stepNumber());
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.PLAN, encodePlan(plan));
                humanReviewGate.requireApprovalIfConfigured(
                        memberDef, teamRunId, rootAgent.id(), step.targetAgentId(), orgId, cursor);

                String memberSession = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, step.targetAgentId());
                RunResponse stepResponse = MemberRunGuard.requireNotPaused(
                        runner.run(step.targetAgentId(), stepInput, media, memberSession, userId, orgId, false, null),
                        step.targetAgentId());
                String output = stepResponse.content();
                stepOutputs.add("Step " + step.stepNumber() + " (" + step.targetAgentId() + "): " + output);
                accumulatedContext.append("\n[Step ").append(step.stepNumber()).append(" Result]: ").append(output);

                // Only pass media to the first agent in the plan
                media = null;
            } catch (ai.operativus.agentmanager.core.exception.TeamMemberPausedException tpe) {
                throw tpe;
            } catch (Exception e) {
                log.error("Plan Step {} failed for agent '{}': {}", step.stepNumber(), step.targetAgentId(), e.getMessage(), e);
                stepOutputs.add("Step " + step.stepNumber() + " (" + step.targetAgentId() + "): FAILED - " + e.getMessage());
            }
        }

        // --- Phase 3: Synthesize ---
        return synthesizeResult(rootAgent, initialInput, stepOutputs);
    }

    /**
     * @summary Invokes the planning LLM to decompose the user request into an ordered execution plan.
     * @logic Constructs a system prompt listing all available agents with their descriptions and tools,
     *        then requests a structured {@link ExecutionPlan} response. Uses Spring AI's native
     *        {@code .entity()} structured output parsing.
     */
    private ExecutionPlan generatePlan(AgentDefinition rootAgent, String userRequest, List<AgentDefinition> members) {
        String agentDescriptions = members.stream()
                .map(a -> {
                    String toolsInfo = (a.tools() != null && !a.tools().isEmpty())
                            ? " [Tools: " + String.join(", ", a.tools()) + "]"
                            : "";
                    return String.format("- %s (ID: %s): %s%s", a.name(), a.id(), a.description(), toolsInfo);
                })
                .collect(java.util.stream.Collectors.joining("\n"));

        String planningPrompt = """
            You are an intelligent task planner. Your goal is to decompose a complex user request into
            an ordered sequence of discrete steps, each assigned to the most capable agent.
            
            Available Agents:
            {agents}
            
            User Request: "{query}"
            
            Rules:
            1. Each step must map to exactly one agent ID from the list above.
            2. Order steps logically — dependencies must be resolved before dependent steps.
            3. Keep the plan minimal. Do not create unnecessary steps.
            4. If only one agent is needed, create a single-step plan.
            """;

        org.springframework.ai.chat.prompt.PromptTemplate template =
                new org.springframework.ai.chat.prompt.PromptTemplate(planningPrompt);
        template.add("agents", agentDescriptions);
        template.add("query", userRequest);

        try {
            return agentClientFactory.buildOrchestrationChatClient(rootAgent, builder)
                    .prompt(template.create())
                    .call()
                    .entity(ExecutionPlan.class);
        } catch (Exception e) {
            log.error("Planning phase failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * @summary Aggregates all step outputs into a cohesive final response using the planner LLM.
     * @logic Passes the original user request and all step results to the LLM for synthesis,
     *        producing a unified answer that integrates all sub-task outputs.
     */
    private String synthesizeResult(AgentDefinition rootAgent, String originalRequest, List<String> stepOutputs) {
        String collectedOutputs = String.join("\n\n", stepOutputs);

        String synthesisPrompt = """
            You are a result synthesizer. The user asked: "{query}"
            
            The following sub-tasks were executed and produced these results:
            
            {results}
            
            Synthesize these results into a single, comprehensive, well-structured response that directly
            answers the user's original request. Do not mention the internal planning or step structure.
            """;

        org.springframework.ai.chat.prompt.PromptTemplate template =
                new org.springframework.ai.chat.prompt.PromptTemplate(synthesisPrompt);
        template.add("query", originalRequest);
        template.add("results", collectedOutputs);

        try {
            return agentClientFactory.buildOrchestrationChatClient(rootAgent, builder)
                    .prompt(template.create())
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Synthesis phase failed. Returning raw step outputs.", e);
            return collectedOutputs;
        }
    }

    /** Encode a generated plan to a List of Map entries suitable for storage in the
     *  human_review_pending.options JSONB column. Decoded by {@link #decodePlan}. */
    static java.util.List<java.util.Map<String, Object>> encodePlan(ExecutionPlan plan) {
        if (plan == null || plan.steps() == null) return java.util.List.of();
        return plan.steps().stream()
                .map(s -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("stepNumber", s.stepNumber());
                    m.put("targetAgentId", s.targetAgentId());
                    m.put("taskDescription", s.taskDescription());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** Decode a persisted plan from the cursor options map. */
    public static ExecutionPlan decodePlan(Object raw) {
        if (!(raw instanceof java.util.List<?> list)) return new ExecutionPlan(java.util.List.of());
        java.util.List<PlannedStep> steps = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> m)) continue;
            Object nRaw = m.get("stepNumber");
            int n = nRaw instanceof Number nn ? nn.intValue() : Integer.parseInt(String.valueOf(nRaw));
            String target = String.valueOf(m.get("targetAgentId"));
            String task = m.get("taskDescription") == null ? "" : String.valueOf(m.get("taskDescription"));
            steps.add(new PlannedStep(n, target, task));
        }
        return new ExecutionPlan(steps);
    }

    /**
     * Resume entry point invoked by {@code TeamMemberDispatchResumeHandler} when an
     * operator decides on a PLANNER pause. Re-enters the plan at {@code fromStepNumber}
     * (the step number, NOT a zero-based index) using the plan that was persisted in
     * the cursor at pause time. Skips plan regeneration entirely so the resumed run
     * sees the SAME plan the operator approved against.
     *
     * <p>Behavior mirrors {@link SequentialOrchestrator#resumeAt}: caller binds
     * {@code AgentContextHolder.approvedTeamMembers} if appropriate.
     */
    public String resumeAt(AgentDefinition rootAgent, ExecutionPlan plan, int fromStepNumber,
                           List<Media> media, String sessionId, String userId, String orgId,
                           AgentOperations runner) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return "Plan unavailable on resume.";
        }
        List<AgentDefinition> members = orchestratorMembers.resolveActive(rootAgent, agentRegistry, orgId, userId, null);
        if (members.isEmpty()) {
            return "Planner failed: No valid, active members available for resume.";
        }
        var validMemberIds = members.stream()
                .map(AgentDefinition::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<String> stepOutputs = new ArrayList<>();
        StringBuilder accumulatedContext = new StringBuilder();
        String teamRunId = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();

        for (PlannedStep step : plan.steps()) {
            if (step.stepNumber() < fromStepNumber) continue;
            if (!validMemberIds.contains(step.targetAgentId())) {
                log.warn("Resume: plan step {} targets non-member '{}' — skipping",
                        step.stepNumber(), step.targetAgentId());
                continue;
            }
            transitionValidator.validate(rootAgent.id(), rootAgent.id(), step.targetAgentId());
            tierEscalationValidator.validate(rootAgent.id(), step.targetAgentId(), orgId);
            String stepInput = step.taskDescription();
            if (!accumulatedContext.isEmpty()) {
                stepInput = "Context from previous steps:\n" + accumulatedContext + "\n\nCurrent Task: " + step.taskDescription();
            }
            try {
                AgentDefinition memberDef = agentRegistry.findById(step.targetAgentId(), orgId);
                java.util.Map<String, Object> cursor = new java.util.HashMap<>();
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.STRATEGY, "PLANNER");
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.CURRENT_INPUT, stepInput);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.SESSION_ID, sessionId == null ? "" : sessionId);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.USER_ID, userId == null ? "" : userId);
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.MEMBER_INDEX, step.stepNumber());
                cursor.put(ai.operativus.agentmanager.core.model.TeamMemberDispatchExtraOptions.PLAN, encodePlan(plan));
                humanReviewGate.requireApprovalIfConfigured(
                        memberDef, teamRunId, rootAgent.id(), step.targetAgentId(), orgId, cursor);
                String memberSession = OrchestrationMemoryScopes.memberConversationId(rootAgent, sessionId, step.targetAgentId());
                RunResponse stepResponse = MemberRunGuard.requireNotPaused(
                        runner.run(step.targetAgentId(), stepInput, media, memberSession, userId, orgId, false, null),
                        step.targetAgentId());
                String output = stepResponse.content();
                stepOutputs.add("Step " + step.stepNumber() + " (" + step.targetAgentId() + "): " + output);
                accumulatedContext.append("\n[Step ").append(step.stepNumber()).append(" Result]: ").append(output);
                media = null;
            } catch (ai.operativus.agentmanager.core.exception.TeamMemberPausedException tpe) {
                throw tpe;
            } catch (Exception e) {
                log.error("Resume plan step {} failed: {}", step.stepNumber(), e.getMessage(), e);
                stepOutputs.add("Step " + step.stepNumber() + " (" + step.targetAgentId() + "): FAILED - " + e.getMessage());
            }
        }
        return String.join("\n", stepOutputs);
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
