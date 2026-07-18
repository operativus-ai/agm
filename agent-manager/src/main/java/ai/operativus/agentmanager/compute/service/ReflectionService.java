package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.control.service.MemoryService;
import ai.operativus.agentmanager.control.service.SettingsService;
import ai.operativus.agentmanager.control.repository.AgentReflectionRepository;
import ai.operativus.agentmanager.core.entity.AgentReflectionEntity;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Domain Responsibility: Implements the Learning Machine logic (Reflection). Analyzes completed agent runs to extract user rules and preferences, storing them in long-term memory.
 * State: Stateless
 */
@Service
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private final ChatClient chatClient;
    private final MemoryService memoryService;
    @SuppressWarnings("unused")
    private final SettingsService settingsService;
    private final AgentReflectionRepository reflectionRepository;

    public ReflectionService(
            ai.operativus.agentmanager.compute.service.AgentModelResolverService modelResolver,
            MemoryService memoryService,
            SettingsService settingsService,
            AgentReflectionRepository reflectionRepository) {
            
        org.springframework.ai.chat.model.ChatModel fastModelTarget = modelResolver.resolveFastRoutingModel();

        if (fastModelTarget != null) {
            this.chatClient = ChatClient.builder(fastModelTarget).build();
        } else {
            this.chatClient = null;
            log.warn("ReflectionService could not resolve a fast model target. Reflection will be disabled.");
        }
        
        this.memoryService = memoryService;
        this.settingsService = settingsService;
        this.reflectionRepository = reflectionRepository;
    }


    /**
     * @summary Extracts explicit user rules and preferences from a completed conversation run and persists a reflection trace.
     * @logic
     * Managed asynchronously via Spring @Async. Prompts the LLM with the raw conversation
     * history to extract permanent rules or facts. If valid rules are found, they are passed to the MemoryService
     * for vectorization. Regardless of rule extraction outcome, a reflection trace node is persisted to the
     * AgentReflectionRepository for offline analysis and debugging of Agent heuristic decisions.
     */
    @org.springframework.scheduling.annotation.Async
    public void reflectOnRun(String userInput, String agentOutput, String userId, String runId, String agentId, String sessionId) {
        if (userInput == null || userInput.isBlank()) {
            return;
        }

        try {
            log.info("Starting background episodic reflection for user: {}", userId);
            
            String systemPrompt = """
                You are a cognitive reflection engine.
                Analyze the following conversation between a User and an AI Agent.
                Extract ONLY explicit, permanent user preferences, rules, or facts about the user that should be remembered for future interactions.
                Do NOT summarize the conversation as a whole. Do NOT extract temporary state, questions, or context.
                If no explicit preferences or rules are stated, return exactly the string "NO_RULES_FOUND".
                Otherwise, return the extracted rules as a concise bulleted list.
                """;

            String userPrompt = "User Input: " + userInput + "\n\nAgent Output: " + agentOutput;

            if (chatClient == null) {
                log.warn("Skipping reflection, ChatClient is uninitialized due to missing fast model target.");
                return;
            }

            String extractedRules = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            boolean rulesFound = extractedRules != null
                    && !extractedRules.trim().equals("NO_RULES_FOUND")
                    && !extractedRules.trim().isEmpty();

            if (rulesFound) {
                log.info("Reflection engine extracted new semantic memory rule: {}", extractedRules);
                memoryService.addMemory(extractedRules, userId);
            } else {
                log.debug("Reflection engine found no new rules to extract.");
            }

            // Persist the reflection trace node for offline analysis
            persistReflectionTrace(runId, agentId, userId, sessionId, userInput, extractedRules, rulesFound);

        } catch (Exception e) {
            log.error("Error during background reflection generation: {}", e.getMessage(), e);
        }
    }

    /**
     * @summary Persists a reflection trace node capturing the reasoning and outcome of a reflection cycle.
     * @logic Creates an AgentReflectionEntity with the input/output summaries, correction flag,
     *        and contextual metadata, then saves it via the AgentReflectionRepository.
     */
    private void persistReflectionTrace(String runId, String agentId, String userId, String sessionId,
                                        String inputSummary, String outputSummary, boolean correctionApplied) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("Skipping reflection trace persist for run {} — no valid sessionId", runId);
            return;
        }
        // §5.21 / Gap 18: run_id is a NOT-NULL FK to agent_runs(id). A null/blank runId
        // would violate fk_agent_reflections_run on insert, so skip persistence entirely
        // rather than synthesize a placeholder row that references no real run.
        if (runId == null || runId.isBlank()) {
            log.debug("Skipping reflection trace persist — no runId supplied (session={})", sessionId);
            return;
        }
        try {
            AgentReflectionEntity trace = new AgentReflectionEntity();
            trace.setReflectionId(UUID.randomUUID());
            trace.setRunId(runId);
            trace.setSessionId(sessionId);
            trace.setAgentId(agentId != null ? agentId : "unknown");
            trace.setUserId(userId);
            trace.setStepIndex(0);
            trace.setPhase("REFLECTION");
            trace.setInputSummary(inputSummary != null && inputSummary.length() > 500 ? inputSummary.substring(0, 500) : inputSummary);
            trace.setOutputSummary(outputSummary != null && outputSummary.length() > 500 ? outputSummary.substring(0, 500) : outputSummary);
            trace.setCorrectionApplied(correctionApplied);
            trace.setOrchestrationDepth(AgentContextHolder.getOrchestrationDepth());

            reflectionRepository.save(trace);
            log.debug("Persisted reflection trace {} for run {}", trace.getReflectionId(), runId);
        } catch (Exception e) {
            log.warn("Failed to persist reflection trace for run {}: {}", runId, e.getMessage());
        }
    }
}
