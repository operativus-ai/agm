package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.SystemTool;
import com.operativus.agentmanager.core.model.ModelFamily;
import com.operativus.agentmanager.core.model.ModelProviderNames;
import com.operativus.agentmanager.compute.advisor.AgentLoggingAdvisor;
import com.operativus.agentmanager.compute.advisor.ContentSafetyAdvisor;
import com.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor;
import com.operativus.agentmanager.compute.advisor.DocumentReRanker;
import com.operativus.agentmanager.compute.advisor.LlmDocumentReRanker;
import com.operativus.agentmanager.compute.advisor.PassthroughDocumentReRanker;
import com.operativus.agentmanager.compute.advisor.PIIAnonymizationAdvisor;
import com.operativus.agentmanager.compute.advisor.PromptInjectionAdvisor;
import com.operativus.agentmanager.compute.advisor.StatefulStreamingPIIAdvisor;
import com.operativus.agentmanager.compute.monitoring.GenAiMetricsAdvisor;
import com.operativus.agentmanager.compute.skill.SkillInjector;
import com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import com.operativus.agentmanager.compute.mcp.McpConnectionPool;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import com.operativus.agentmanager.compute.advisor.VectorStoreCacheAdvisor;
import com.operativus.agentmanager.compute.advisor.AdvancedRagAdvisor;
import com.operativus.agentmanager.control.service.KnowledgeService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.operativus.agentmanager.core.registry.UserProvider;
import com.operativus.agentmanager.core.registry.ToolRegistry;
import com.operativus.agentmanager.compute.security.AgentIdentityService;
import com.operativus.agentmanager.core.entity.AgentCredential;

/**
 * Domain Responsibility: Encapsulates the complete ChatClient building lifecycle for agents. It handles tool resolution, injects dynamic context (user rules, system environment), configures prompt/security advisors (Rag, PII, Prompt Injection), and delegates model selection to AgentModelResolverService.
 * State: Stateful (maintains caches for custom ChatModels and dynamically resolved ToolCallbacks)
 *
 * @architecture This factory is a "dumb factory" — it does NOT contain model selection logic.
 *               All model resolution is delegated to AgentModelResolverService.
 */
@Component
public class AgentClientFactory implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentClientFactory.class);

    private final AgentRegistry agentRegistry;
    private final com.operativus.agentmanager.core.callback.AugmentedToolCallbackProvider callbackProvider;
    @SuppressWarnings("unused")
    private final VectorStore vectorStore;
    private final Map<String, org.springframework.ai.chat.model.ChatModel> chatModels;
    private final AgentLoggingAdvisor agentLoggingAdvisor;
    private final PromptInjectionAdvisor promptInjectionAdvisor;
    private final PIIAnonymizationAdvisor piiAnonymizationAdvisor;
    private final ContentSafetyAdvisor contentSafetyAdvisor;
    private final AgentModelResolverService modelResolverService;
    private final org.springframework.core.env.Environment environment;
    private final UserProvider userRepository;
    private final com.operativus.agentmanager.core.registry.MemoryOperations memoryService;
    private final KnowledgeService knowledgeService;
    private final com.operativus.agentmanager.control.service.PersistentChatMemory persistentChatMemory;
    private final GenAiMetricsAdvisor genAiMetricsAdvisor;
    private final StatefulStreamingPIIAdvisor statefulStreamingPIIAdvisor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final int structuredOutputMaxRetries;

    private final Map<String, ChatModel> customChatModelsCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ToolCallback> availableTools;
    private final Map<String, com.operativus.agentmanager.compute.provider.DynamicModelProvider> modelProviderRegistry = new java.util.concurrent.ConcurrentHashMap<>();
    private final int maxOrchestrationTurns;

    /** RAG reranker gate (agent.rag.reranker.enabled, default false). When true, the AdvancedRagAdvisor
     *  uses the LLM {@link com.operativus.agentmanager.compute.advisor.LlmDocumentReRanker} to refine the
     *  RRF pool; otherwise the {@link com.operativus.agentmanager.compute.advisor.PassthroughDocumentReRanker}. */
    private final boolean rerankerEnabled;

    private final VectorStoreCacheAdvisor cacheAdvisor;
    private final ExtensionRegistrationRepository extensionRepository;
    private final org.springframework.web.reactive.function.client.WebClient extensionWebClient;
    @SuppressWarnings("unused")
    private final McpConnectionPool mcpConnectionPool;
    private final AgentIdentityService agentIdentityService;
    private final com.operativus.agentmanager.control.service.SettingsService settingsService;
    private final Optional<SkillInjector> skillInjector;

    /** Edition add-on advisors (core/spi seam). Empty in Core — populated only when an add-on
     *  artifact (e.g. agm-enterprise) registers {@code EnterpriseAdvisorContributor} beans. */
    private final List<com.operativus.agentmanager.core.spi.EnterpriseAdvisorContributor> advisorContributors;

    /** Flattens contributor advisors in {@code order()} sequence; null/empty contributions are
     *  skipped. Static so the merge contract is unit-testable without constructing the factory. */
    static List<org.springframework.ai.chat.client.advisor.api.Advisor> contributedAdvisors(
            List<com.operativus.agentmanager.core.spi.EnterpriseAdvisorContributor> contributors,
            AgentDefinition def) {
        if (contributors == null || contributors.isEmpty()) {
            return List.of();
        }
        return contributors.stream()
                .sorted(java.util.Comparator.comparingInt(
                        com.operativus.agentmanager.core.spi.EnterpriseAdvisorContributor::order))
                .flatMap(c -> {
                    List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors = c.contribute(def);
                    return advisors == null ? java.util.stream.Stream.empty() : advisors.stream();
                })
                .toList();
    }

    /**
     * @summary Initializes the factory, dynamically registering all available tools and dynamic model providers.
     * @logic 
     * Iterates through all static and dynamically provided Spring ToolCallbackProvider beans and registers them into the internal tools map.
     * It also configures the modelProviderRegistry so that custom, database-defined models can be instantiated correctly at runtime.
     */
    public AgentClientFactory(AgentRegistry agentRegistry,
                              com.operativus.agentmanager.core.callback.AugmentedToolCallbackProvider callbackProvider,
                              VectorStore vectorStore,
                              List<ToolCallback> allTools,
                              List<org.springframework.ai.tool.ToolCallbackProvider> toolProviders,
                              Map<String, org.springframework.ai.chat.model.ChatModel> chatModels,
                              List<com.operativus.agentmanager.compute.provider.DynamicModelProvider> dynamicModelProviders,
                              AgentLoggingAdvisor agentLoggingAdvisor,
                              PromptInjectionAdvisor promptInjectionAdvisor,
                              PIIAnonymizationAdvisor piiAnonymizationAdvisor,
                              ContentSafetyAdvisor contentSafetyAdvisor,
                              VectorStoreCacheAdvisor cacheAdvisor,
                              AgentModelResolverService modelResolverService,
                              org.springframework.core.env.Environment environment,
                              UserProvider userRepository,
                              com.operativus.agentmanager.core.registry.MemoryOperations memoryService,
                              KnowledgeService knowledgeService,
                              com.operativus.agentmanager.control.service.PersistentChatMemory persistentChatMemory,
                              GenAiMetricsAdvisor genAiMetricsAdvisor,
                              StatefulStreamingPIIAdvisor statefulStreamingPIIAdvisor,
                              ExtensionRegistrationRepository extensionRepository,
                              McpConnectionPool mcpConnectionPool,
                              AgentIdentityService agentIdentityService,
                              com.operativus.agentmanager.control.service.SettingsService settingsService,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry,
                              Optional<SkillInjector> skillInjector,
                              List<com.operativus.agentmanager.core.spi.EnterpriseAdvisorContributor> advisorContributors,
                              @org.springframework.beans.factory.annotation.Value("${agent.orchestration.max-turns:5}") int maxOrchestrationTurns,
                              @org.springframework.beans.factory.annotation.Value("${agent.guardrails.structured-output.max-retries:3}") int structuredOutputMaxRetries,
                              @org.springframework.beans.factory.annotation.Value("${agent.rag.reranker.enabled:false}") boolean rerankerEnabled) {
        this.agentRegistry = agentRegistry;
        this.callbackProvider = callbackProvider;
        this.vectorStore = vectorStore;
        this.chatModels = chatModels;
        this.agentLoggingAdvisor = agentLoggingAdvisor;
        this.promptInjectionAdvisor = promptInjectionAdvisor;
        this.piiAnonymizationAdvisor = piiAnonymizationAdvisor;
        this.contentSafetyAdvisor = contentSafetyAdvisor;
        this.cacheAdvisor = cacheAdvisor;
        this.modelResolverService = modelResolverService;
        this.environment = environment;
        this.userRepository = userRepository;
        this.memoryService = memoryService;
        this.knowledgeService = knowledgeService;
        this.persistentChatMemory = persistentChatMemory;
        this.genAiMetricsAdvisor = genAiMetricsAdvisor;
        this.statefulStreamingPIIAdvisor = statefulStreamingPIIAdvisor;
        this.extensionRepository = extensionRepository;
        this.extensionWebClient = org.springframework.web.reactive.function.client.WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
        this.mcpConnectionPool = mcpConnectionPool;
        this.agentIdentityService = agentIdentityService;
        this.settingsService = settingsService;
        this.meterRegistry = meterRegistry;
        this.skillInjector = skillInjector;
        this.advisorContributors = advisorContributors == null ? List.of() : advisorContributors;
        this.maxOrchestrationTurns = maxOrchestrationTurns;
        this.structuredOutputMaxRetries = structuredOutputMaxRetries;
        this.rerankerEnabled = rerankerEnabled;

        if (dynamicModelProviders != null) {
            for (com.operativus.agentmanager.compute.provider.DynamicModelProvider provider : dynamicModelProviders) {
                for (String key : provider.getProviderKeys()) {
                    this.modelProviderRegistry.put(key.toUpperCase(), provider);
                }
            }
        }

        this.availableTools = allTools.stream()
                .collect(Collectors.toMap(
                        t -> t.getToolDefinition().name(),
                        Function.identity()
                ));

        // Merge dynamically provided tools (e.g. from MCP servers via ToolCallbackProvider)
        if (toolProviders != null) {
            for (org.springframework.ai.tool.ToolCallbackProvider provider : toolProviders) {
                if (provider != null && provider.getToolCallbacks() != null) {
                    for (ToolCallback tc : provider.getToolCallbacks()) {
                        this.availableTools.putIfAbsent(tc.getToolDefinition().name(), tc);
                    }
                }
            }
        }

        // MCP tools are deliberately NOT merged into availableTools here. That map is a
        // process-wide singleton shared across every tenant, so a global merge would expose one
        // org's MCP tools to every other org's agents (#1132). MCP tools are instead resolved
        // per-run, scoped to the agent's org, in resolveTools(...) via
        // mcpConnectionPool.getToolCallbacksForOrg(orgId).

        log.info("AgentClientFactory initialized with {} native/Composio tools (MCP tools resolved per-org at request time).", this.availableTools.size());
    }

    public Map<String, ToolCallback> getAvailableTools() {
        return this.availableTools;
    }

    /**
     * @summary Resolves the specific tools requested by an agent definition against the available tools in the registry.
     * @logic 
     * Iterates over the agent's requested tool names, checking against the available tools. Normalizes snake_case to camelCase to match Spring standards.
     * It also dynamically injects 'delegate_to_agent' for COORDINATOR modes and 'hand_off_to_agent' for SWARM modes, ensuring the necessary tools are present for team orchestration.
     */
    public List<ToolCallback> resolveTools(AgentDefinition def) {
        List<ToolCallback> agentTools = new ArrayList<>();

        // Tenant-scoped MCP tools (#1132): an agent only sees MCP tools from extensions in its
        // own org. Resolved per-run from the pool (fail-closed → empty when no org is bound) and
        // overlaid on the shared native/Composio tool map WITHOUT mutating that singleton.
        Map<String, ToolCallback> effective = availableTools;
        List<ToolCallback> mcpTools = mcpConnectionPool.getToolCallbacksForOrg(AgentContextHolder.getOrgId());
        if (!mcpTools.isEmpty()) {
            effective = new HashMap<>(availableTools);
            for (ToolCallback mcp : mcpTools) {
                effective.putIfAbsent(mcp.getToolDefinition().name(), mcp);
            }
        }

        if (def.tools() != null) {
            for (String toolName : def.tools()) {
                ToolCallback tool = effective.get(toolName);
                if (tool == null) {
                    // Try normalizing snake_case to camelCase
                    String normalized = toCamelCase(toolName);
                    tool = effective.get(normalized);
                }

                if (tool != null) {
                    agentTools.add(tool);
                } else {
                    log.warn("Security/Initialization Warning: Agent '{}' requested unknown tool '{}'. (Normalized check: '{}'). This tool does not exist in the Application Context and was ignored.", 
                            def.id(), toolName, toCamelCase(toolName));
                }
            }
        }



        if (def.isTeam() && "COORDINATOR".equalsIgnoreCase(def.teamMode())) {
            boolean hasDelegation = agentTools.stream().anyMatch(t -> t.getToolDefinition().name().equals(SystemTool.DELEGATE_TO_AGENT.getToolName()));
            if (!hasDelegation) {
                ToolCallback dtCallback = availableTools.get(SystemTool.DELEGATE_TO_AGENT.getToolName());
                if (dtCallback != null) {
                    agentTools.add(dtCallback);
                }
            }
        }

        if (def.isTeam() && "SWARM".equalsIgnoreCase(def.teamMode())) {
            boolean hasHandoff = agentTools.stream().anyMatch(t -> t.getToolDefinition().name().equals(SystemTool.HAND_OFF_TO_AGENT.getToolName()));
            if (!hasHandoff) {
                ToolCallback hoCallback = availableTools.get(SystemTool.HAND_OFF_TO_AGENT.getToolName());
                if (hoCallback != null) {
                    agentTools.add(hoCallback);
                } else {
                    log.warn("hand_off_to_agent tool not found in available tools list!");
                }
            }
        }

        // REQ-TT-3/7 — TASKS-mode coordinators get the TaskManagementTool surface so the
        // LLM can enqueue, update, query, and inspect tasks via @Tool calls. Without this,
        // TasksOrchestrator's worker loop has nothing to drain.
        if (def.isTeam() && "TASKS".equalsIgnoreCase(def.teamMode())) {
            for (SystemTool taskTool : new SystemTool[]{
                    SystemTool.TASK_CREATE, SystemTool.TASK_UPDATE_STATUS,
                    SystemTool.TASK_QUERY, SystemTool.TASK_GET}) {
                String name = taskTool.getToolName();
                boolean already = agentTools.stream()
                        .anyMatch(t -> t.getToolDefinition().name().equals(name));
                if (already) continue;
                ToolCallback cb = availableTools.get(name);
                if (cb != null) {
                    agentTools.add(cb);
                } else {
                    log.warn("TASKS mode requested but tool '{}' not found in available tools — "
                            + "verify TaskManagementTool bean is registered.", name);
                }
            }
        }
        log.debug("Resolved {} tools for agent '{}'.", agentTools.size(), def.id());
        return agentTools;
    }

    private String toCamelCase(String snake) {
        if (snake == null || !snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * @summary Resolves and mints JIT tokens for all enabled credentials of the given agent.
     * @logic Returns a map of provider name -> bearer token. Callers bind this to AgentIdentityContext
     *        ScopedValue before executing the agent run, so tool callbacks can read it.
     */
    public Map<String, String> resolveAgentTokens(String agentId) {
        Map<String, AgentCredential> credentials = agentIdentityService.resolveAllCredentials(agentId);
        if (credentials.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> tokens = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, AgentCredential> entry : credentials.entrySet()) {
            try {
                String token = agentIdentityService.mintToken(entry.getValue());
                tokens.put(entry.getKey(), token);
            } catch (Exception e) {
                log.warn("Failed to mint token for agent '{}', provider '{}': {}", agentId, entry.getKey(), e.getMessage());
            }
        }
        log.info("Resolved {} agent identity tokens for agent '{}'", tokens.size(), agentId);
        return tokens;
    }

    /**
     * @summary Constructs a fully configured Spring AI ChatClient for the given AgentDefinition.
     * @logic
     * 1. Delegates model resolution and validation to AgentModelResolverService.
     * 2. Resolves and wraps required tools (including Swarm/Coordinator dynamic tools) using the callbackProvider.
     * 3. Constructs a dynamic system prompt block containing critical runtime context (system time, user rules, team directives, reasoning instructions).
     * 4. Instantiates the physical ChatModel implementation for the active provider.
     * 5. Binds default advisors (Prompt Injection, PII, Content Safety, Memory, and AdvancedRagAdvisor) and provider-specific ChatOptions to the ChatClient.Builder.
     */
    public ChatClient buildChatClient(AgentDefinition def, String sessionId, String userId, String orgId, RunOptions runOptions) {
        // --- UNIFIED MODEL RESOLUTION (Delegated to AgentModelResolverService) ---
        AgentModelResolverService.ResolvedModel resolved = modelResolverService.resolveModel(def);
        modelResolverService.validateCapabilities(def, resolved);

        List<ToolCallback> agentTools = resolveTools(def);

        String baseInstructions = (def.instructions() != null) ? def.instructions() : "";

        // Apply system prompt override from RunOptions if provided
        if (runOptions != null && runOptions.systemPrompt() != null && !runOptions.systemPrompt().isBlank()) {
            log.info("Applying runtime system prompt override for agent '{}'", def.id());
            baseInstructions = runOptions.systemPrompt();
        }

        // Skill injection — folds active Skills' tools + system-prompt snippets into the
        // agent's effective tool set and instructions. Bean is present only when
        // agm.skills.enabled=true; absent otherwise (Optional.empty()).
        if (skillInjector.isPresent() && def.id() != null) {
            SkillInjector.InjectionResult skillResult = skillInjector.get().inject(
                    def.id(),
                    def.tools() != null ? def.tools() : List.of(),
                    baseInstructions,
                    availableTools.keySet());

            Set<String> existingToolNames = new HashSet<>();
            for (ToolCallback tc : agentTools) {
                existingToolNames.add(tc.getToolDefinition().name());
            }
            for (String name : skillResult.tools()) {
                if (existingToolNames.add(name)) {
                    ToolCallback cb = availableTools.get(name);
                    if (cb != null) agentTools.add(cb);
                    // If cb == null, SkillInjector already soft-skipped at the name level;
                    // a name that survives the inject() call but has no callback here is
                    // a registry race we surface as a noisy log inside the injector, not a
                    // hard failure for the agent run.
                }
            }
            baseInstructions = skillResult.systemPrompt();
        }

        List<ToolCallback> wrappedTools = callbackProvider.wrap(agentTools, def);
        
        String capabilityAwareness = "INTERNAL RULE: You are strictly limited to the capabilities provided by your assigned tools. " +
                "If your tools include web search or URL scraping capabilities, YOU MUST ACTIVELY USE THEM to fetch live internet data when requested, and never apologize or claim you cannot browse the internet. " +
                "Only if you lack a specifically matching tool for a complex action should you state you lack the capability. Do not hallucinate capabilities you don't have.\n\n";
        
        String systemPrompt = capabilityAwareness + baseInstructions;

        // --- Build Dynamic Context Block ---
        StringBuilder dynamicContext = new StringBuilder("\n\n--- CURRENT EXECUTION CONTEXT ---\n");
        dynamicContext.append("Current System Date/Time: ").append(java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)).append("\n");

        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles != null && activeProfiles.length > 0) {
            dynamicContext.append("System Environment Profiles: ").append(String.join(", ", activeProfiles)).append("\n");
        }

        if (userId != null && !userId.isBlank()) {
            try {
                java.util.UUID userUuid = java.util.UUID.fromString(userId);
                userRepository.findById(userUuid).ifPresentOrElse(
                        user -> dynamicContext.append("Active User ID: ").append(userId).append(" (Username: ").append(user.getUsername()).append(")\n"),
                        () -> dynamicContext.append("Active User ID: ").append(userId).append("\n")
                );
            } catch (IllegalArgumentException e) {
                dynamicContext.append("Active User ID: ").append(userId).append("\n");
            }
        }

        if (orgId != null && !orgId.isBlank()) dynamicContext.append("Active Organization ID: ").append(orgId).append("\n");
        if (sessionId != null && !sessionId.isBlank()) dynamicContext.append("Conversation Session ID: ").append(sessionId).append("\n");

        dynamicContext.append("Your Agent ID: ").append(def.id()).append("\n");
        dynamicContext.append("Assigned Model: ").append(resolved.effectiveModelId()).append(" (resolved via: ").append(resolved.resolvedVia()).append(")\n");
        
        // --- Semantic Logic has been shifted natively downstream to AgenticMemoryAdvisor ---


        if (def.isTeam() && def.members() != null) {
            dynamicContext.append("Max Orchestration Turns: ").append(maxOrchestrationTurns).append("\n");
        }

        if (def.knowledgeBaseIds() != null && !def.knowledgeBaseIds().isEmpty()) {
            dynamicContext.append("Attached Knowledge Bases: ").append(String.join(", ", def.knowledgeBaseIds())).append("\n");
        }

        if (def.supportedLocales() != null && !def.supportedLocales().isEmpty()) {
            dynamicContext.append("Supported Locales (Language/Region): ").append(String.join(", ", def.supportedLocales())).append("\n");
        }

        dynamicContext.append("---------------------------------\n");
        dynamicContext.append("Always use the context above when fulfilling requests. Do not ask the user for this information if it is present.");

        // PHASE 5: DYNAMIC REASONING FALLBACK
        String effectiveModelId = resolved.effectiveModelId();
        ModelFamily monitoringFamily = ModelFamily.fromModelId(effectiveModelId);
        boolean isHighReasoningModel = (monitoringFamily == ModelFamily.ANTHROPIC) || (effectiveModelId != null && (effectiveModelId.contains("o1") || effectiveModelId.contains("o3")));
        
        if (def.monitoringEnabled() && !isHighReasoningModel) {
            dynamicContext.append("\n\nCRITICAL REASONING INSTRUCTION:\n");
            dynamicContext.append("You are operating in High-Reasoning mode. Before providing your final answer, you MUST deeply analyze the problem step-by-step.\n");
            dynamicContext.append("Enclose your internal reasoning process within <think>...</think> XML tags before outputting the final response.\n");
        }

        systemPrompt += dynamicContext.toString();

        if (def.isTeam() && "COORDINATOR".equalsIgnoreCase(def.teamMode())) {
            StringBuilder teamContext = new StringBuilder("\n\nYou are the leader of a team. You have the following agents available to delegate work to:\n");
            if (def.members() != null) {
                for (String memberId : def.members()) {
                    AgentDefinition member = agentRegistry.findById(memberId, orgId);
                    if (member != null) {
                        teamContext.append(String.format("- %s (ID: %s): %s\n", member.name(), member.id(), member.description()));
                    }
                }
            }
            teamContext.append("\nUse the 'delegate_to_agent' tool to assign tasks to them by their ID.");
            systemPrompt += teamContext.toString();
        } else if (def.isTeam() && "SWARM".equalsIgnoreCase(def.teamMode())) {
            StringBuilder teamContext = new StringBuilder("\n\nYou are part of a swarm team. You can autonomously hand off the conversation to another specialist if their skills are better suited. Available teammates:\n");
            if (def.members() != null) {
                for (String memberId : def.members()) {
                    AgentDefinition member = agentRegistry.findById(memberId, orgId);
                    if (member != null && !member.id().equals(def.id())) {
                        teamContext.append(String.format("- %s (ID: %s): %s\n", member.name(), member.id(), member.description()));
                    }
                }
            }
            teamContext.append("\nUse the 'hand_off_to_agent' tool to shift context to them dynamically.");
            systemPrompt += teamContext.toString();
        }

        // --- UNIFIED CHATMODEL INSTANTIATION ---
        org.springframework.ai.chat.model.ChatModel targetModel = instantiateChatModel(def, resolved);

        if (targetModel == null) {
            log.error("CRITICAL: No ChatModel could be resolved for agent '{}'. effectiveModelId='{}', resolvedVia='{}'",
                    def.id(), resolved.effectiveModelId(), resolved.resolvedVia());
            throw new BusinessValidationException("No ChatModel configured or Active Provider available for model ID: '" + resolved.effectiveModelId()
                    + "'. Please verify application.properties has valid API keys and the provider is enabled.");
        }

        log.info("Resolved ChatModel for agent '{}'. effectiveModelId='{}', resolvedVia='{}', ChatModel={}",
                def.id(), resolved.effectiveModelId(), resolved.resolvedVia(), targetModel.getClass().getSimpleName());

        ChatClient.Builder builder = ChatClient.builder(targetModel)
                .defaultSystem(systemPrompt);

        if (def.enforceJsonOutput()) {
            systemPrompt += "\n\nCRITICAL INSTRUCTION: You must return your entire response STRICTLY as a valid JSON object. Do not include any conversational text, markdown formatting blocks (e.g. ```json), or explanations outside of the bare JSON block.";
            builder.defaultSystem(systemPrompt);
        }

        // Prevent cross-provider Option crashes caused by effectiveModel preserving the failing provider's specific model string (e.g., throwing "gpt-4" at Anthropic via fallback)
        ModelFamily requestedFamily = ModelFamily.fromModelId(resolved.effectiveModelId());
        String safeEffectiveModelId = resolved.effectiveModelId();
        if (targetModel instanceof org.springframework.ai.openai.OpenAiChatModel && requestedFamily != ModelFamily.OPENAI) {    
             safeEffectiveModelId = null;
        } else if (targetModel.getClass().getSimpleName().toLowerCase().contains("google") && requestedFamily != ModelFamily.GOOGLE) {
             safeEffectiveModelId = null;
        } else if (targetModel instanceof org.springframework.ai.anthropic.AnthropicChatModel && requestedFamily != ModelFamily.ANTHROPIC) {
             safeEffectiveModelId = null;
        }

        org.springframework.ai.chat.prompt.ChatOptions.Builder<?> options = buildChatOptions(targetModel, safeEffectiveModelId, def, runOptions);

        if (wrappedTools != null && !wrappedTools.isEmpty()) {
            List<String> toolNames = wrappedTools.stream().map(t -> t.getToolDefinition().name()).toList();
            log.info("Binding {} tools to ChatClient for agent {}: {}", wrappedTools.size(), def.id(), toolNames);
            builder.defaultToolCallbacks(wrappedTools);
        } else {
            log.warn("No tools bound to ChatClient for agent {}.", def.id());
        }

        builder.defaultOptions(options);

        builder.defaultAdvisors(
                // Run FIRST — populates request.context() with per-run agentId/sessionId so
                // downstream advisors (PII redaction, streaming PII guard) can attribute work
                // to the originating agent regardless of which Reactor thread executes them.
                new com.operativus.agentmanager.compute.advisor.AgentIdInjectionAdvisor(def.id(), sessionId),
                this.agentLoggingAdvisor,
                this.cacheAdvisor,
                this.promptInjectionAdvisor,
                this.piiAnonymizationAdvisor,
                this.statefulStreamingPIIAdvisor,
                this.contentSafetyAdvisor,
                this.genAiMetricsAdvisor
        );

        // --- Edition add-on advisors (core/spi seam; empty in Core) ---
        // After the static safety chain (contributors observe redacted prompts, cannot displace
        // a safety advisor), before the per-agent conditional advisors.
        List<org.springframework.ai.chat.client.advisor.api.Advisor> contributed =
                contributedAdvisors(advisorContributors, def);
        if (!contributed.isEmpty()) {
            log.info("Binding {} edition advisor(s) for agent '{}'", contributed.size(), def.id());
            builder.defaultAdvisors(contributed.toArray(
                    org.springframework.ai.chat.client.advisor.api.Advisor[]::new));
        }

        // --- Extension Hook Advisor (Opt-In per agent) ---
        boolean hasPreHooks = def.preHooks() != null && !def.preHooks().isEmpty();
        boolean hasPostHooks = def.postHooks() != null && !def.postHooks().isEmpty();
        if (hasPreHooks || hasPostHooks) {
            log.info("Binding ExtensionHookAdvisor for agent '{}': preHooks={}, postHooks={}", def.id(), def.preHooks(), def.postHooks());
            builder.defaultAdvisors(new ExtensionHookAdvisor(def.preHooks(), def.postHooks(),
                    this.extensionRepository, this.extensionWebClient,
                    this.piiAnonymizationAdvisor, this.meterRegistry));
        }

        // --- Structured Output Retry Advisor (Opt-In for JSON-enforced agents) ---
        if (def.enforceJsonOutput()) {
            log.info("Binding StructuredOutputRetryAdvisor for JSON-enforced agent '{}' (maxRetries={})", def.id(), structuredOutputMaxRetries);
            builder.defaultAdvisors(new com.operativus.agentmanager.compute.advisor.StructuredOutputRetryAdvisor(structuredOutputMaxRetries, meterRegistry));
        }

        if (userId != null && !userId.isBlank()) {
            builder.defaultAdvisors(new com.operativus.agentmanager.compute.advisor.AgenticMemoryAdvisor(this.memoryService, userId));
        }

        // --- Chat Memory (gated by agent/team memoryEnabled flag) ---
        boolean isMemoryEnabled = def.memoryEnabled() == null || Boolean.TRUE.equals(def.memoryEnabled());
        boolean addHistory = def.addHistoryToMessages() == null || Boolean.TRUE.equals(def.addHistoryToMessages());
        if (isMemoryEnabled && addHistory) {
            // Spring AI 2.0-SNAPSHOT bug pin: spec.param("chat_memory_conversation_id", sessionId)
            // inside defaultAdvisors(Consumer) does NOT propagate into the per-request context
            // map. MessageChatMemoryAdvisor.after then throws "conversationId cannot be null"
            // AFTER the LLM call completes — operators billed, no response delivered.
            //
            // Workaround: register a tiny ConversationIdInjectionAdvisor that runs at
            // HIGHEST_PRECEDENCE and stuffs the sessionId into request.context() before
            // MessageChatMemoryAdvisor reads it. Both advisors registered via the
            // Advisor[] overload so we don't depend on the Consumer-param plumbing at all.
            builder.defaultAdvisors(
                    new com.operativus.agentmanager.compute.advisor.ConversationIdInjectionAdvisor(sessionId),
                    MessageChatMemoryAdvisor.builder(persistentChatMemory).build(),
                    new com.operativus.agentmanager.compute.advisor.ConversationIdResponseAdvisor());
        }

        if (def.tools() != null && def.tools().contains(SystemTool.SEARCH_KNOWLEDGE_BASE.getToolName())) {
            log.info("Adding AdvancedRagAdvisor for agent: {}", def.id());

            org.springframework.ai.vectorstore.SearchRequest.Builder searchBuilder = SearchRequest.builder();
            if (def.knowledgeBaseIds() != null && !def.knowledgeBaseIds().isEmpty()) {
                var expressionBuilder = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
                searchBuilder.filterExpression(expressionBuilder.in("knowledge_base_id", def.knowledgeBaseIds()).build());
            }

            // Re-ranker: by default the AdvancedRagAdvisor takes RRF's top N (PassthroughDocumentReRanker).
            // When agent.rag.reranker.enabled=true, refine the RRF-ordered pool with an LLM bulk-scoring
            // pass using the agent's resolved model; the advisor fails open to RRF order on any error.
            DocumentReRanker reranker = rerankerEnabled
                    ? new LlmDocumentReRanker(targetModel)
                    : new PassthroughDocumentReRanker();
            builder.defaultAdvisors(new AdvancedRagAdvisor(knowledgeService, searchBuilder.build(), reranker, meterRegistry));
        }

        return builder.build();
    }

    /**
     * @summary Builds a minimal ChatClient for orchestrator routing/planning calls.
     * @logic Chains the minimum security and compliance advisors (prompt injection, PII
     *        anonymization, content safety, metrics) onto the Spring-autoconfigured builder
     *        without RAG, memory, tools, or agent-specific config. Orchestrators must use
     *        this instead of bare builder.build() so user input is not sent raw to the LLM.
     */
    public ChatClient buildOrchestrationChatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(
                        this.promptInjectionAdvisor,
                        this.piiAnonymizationAdvisor,
                        this.contentSafetyAdvisor,
                        this.genAiMetricsAdvisor)
                .build();
    }

    /**
     * @summary Builds the orchestration ChatClient honoring the team's {@code def.modelId()}.
     * @logic Resolves the model via {@link AgentModelResolverService}, instantiates the
     *        team-specific {@code ChatModel}, and attaches the same minimum orchestration
     *        advisors as the no-arg overload. Falls back to the Spring-autoconfigured
     *        {@code fallbackBuilder} when the team has no model set or resolution fails —
     *        preserves the pre-Bug-#5 behavior so single-provider deployments still work.
     *        Router / Planner / Swarm call this on every execute so per-team model overrides
     *        take effect (singleton-cached ChatClient was the regression source).
     */
    public ChatClient buildOrchestrationChatClient(AgentDefinition def, ChatClient.Builder fallbackBuilder) {
        if (def == null || def.modelId() == null || def.modelId().isBlank()) {
            return buildOrchestrationChatClient(fallbackBuilder);
        }
        try {
            AgentModelResolverService.ResolvedModel resolved = modelResolverService.resolveModel(def);
            org.springframework.ai.chat.model.ChatModel chatModel = instantiateChatModel(def, resolved);
            if (chatModel == null) {
                log.warn("Orchestration: no ChatModel for team '{}' model='{}' (resolvedVia={}); falling back to default orchestration client.",
                        def.id(), def.modelId(), resolved.resolvedVia());
                return buildOrchestrationChatClient(fallbackBuilder);
            }
            return ChatClient.builder(chatModel)
                    .defaultAdvisors(
                            this.promptInjectionAdvisor,
                            this.piiAnonymizationAdvisor,
                            this.contentSafetyAdvisor,
                            this.genAiMetricsAdvisor)
                    .build();
        } catch (com.operativus.agentmanager.core.exception.MissingProviderKeyException e) {
            // A team model configured with no usable key must fail loudly. Falling back to the
            // default client here would silently run a DIFFERENT model — typically the auto-config
            // bean built from a possibly-invalid env key — masking the misconfiguration behind a
            // confusing downstream provider error (e.g. a 400 from the provider). Surface the
            // actionable "configure a valid <provider> key" message instead.
            log.warn("Orchestration: team '{}' model '{}' has no usable key; failing fast: {}",
                    def.id(), def.modelId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Orchestration: model resolution failed for team '{}' model='{}'; falling back to default orchestration client. Reason: {}",
                    def.id(), def.modelId(), e.getMessage());
            return buildOrchestrationChatClient(fallbackBuilder);
        }
    }

    /**
     * @summary Instantiates the correct ChatModel implementation based on the resolved model.
     * @logic
     * Evaluates the ResolvedModel. If it is a custom database-driven model, it invokes instantiateCustomChatModel.
     * Otherwise, it resolves a Spring-managed ChatModel bean by matching the exact model ID prefix against autowired clients.
     */
    private org.springframework.ai.chat.model.ChatModel instantiateChatModel(AgentDefinition def, AgentModelResolverService.ResolvedModel resolved) {
        if (resolved.hasCustomModel()) {
            return instantiateCustomChatModel(def, resolved.modelEntity());
        }
        return resolveSpringManagedChatModel(resolved.effectiveModelId(), def.fallbackModelIds());
    }

    /**
     * Resolves a Spring-managed ChatModel for {@code modelId} if its provider is active,
     * or null if the provider is disabled or unknown.
     */
    private org.springframework.ai.chat.model.ChatModel resolveFromModelId(String modelId) {
        if (modelId == null) return null;
        ModelFamily family = ModelFamily.fromModelId(modelId);
        if (family == ModelFamily.OPENAI) {
            if (Boolean.parseBoolean(environment.getProperty("agent.provider.openai.active", "false"))) {
                return chatModels.get(ModelProviderNames.OPENAI);
            }
        } else if (family == ModelFamily.ANTHROPIC) {
            if (Boolean.parseBoolean(environment.getProperty("agent.provider.anthropic.active", "false"))) {
                return chatModels.get(ModelProviderNames.ANTHROPIC);
            }
        } else if (family == ModelFamily.GOOGLE) {
            if (Boolean.parseBoolean(environment.getProperty("agent.provider.google.active", "false"))) {
                org.springframework.ai.chat.model.ChatModel m = chatModels.get(ModelProviderNames.GOOGLE);
                return m != null ? m : chatModels.get(ModelProviderNames.GEMINI_FALLBACK);
            }
        }
        return null;
    }

    /**
     * @summary Resolves a Spring-managed ChatModel bean by matching the effective model ID prefix.
     * @logic Primary → per-agent ordered fallbacks → global active-provider fallback.
     */
    private org.springframework.ai.chat.model.ChatModel resolveSpringManagedChatModel(String modelId, java.util.List<String> agentFallbacks) {
        if (modelId == null) return null;

        org.springframework.ai.chat.model.ChatModel target = resolveFromModelId(modelId);
        if (target != null) return target;

        log.warn("Failed to retrieve primary ChatModel for model ID '{}'. Attempting agent-configured fallbacks...", modelId);

        // Try per-agent fallbacks in order before the global provider fallback.
        if (agentFallbacks != null) {
            for (String fallbackId : agentFallbacks) {
                target = resolveFromModelId(fallbackId);
                if (target != null) {
                    log.info("Using agent-configured fallback model '{}' (primary '{}' unavailable).", fallbackId, modelId);
                    return target;
                }
            }
        }

        log.warn("Agent fallbacks exhausted for model '{}'. Attempting global active-provider fallback...", modelId);

        if (Boolean.parseBoolean(environment.getProperty("agent.provider.google.active", "false"))) {
            target = chatModels.get(ModelProviderNames.GOOGLE);
            if (target == null) target = chatModels.get(ModelProviderNames.GEMINI_FALLBACK);
        } else if (Boolean.parseBoolean(environment.getProperty("agent.provider.anthropic.active", "false"))) {
            target = chatModels.get(ModelProviderNames.ANTHROPIC);
        } else if (Boolean.parseBoolean(environment.getProperty("agent.provider.openai.active", "false"))) {
            target = chatModels.get(ModelProviderNames.OPENAI);
        }

        if (target == null) {
            log.error("CRITICAL: No active AI providers available for fallback.");
        } else {
            log.info("Global fallback successful. Using target model class: {}", target.getClass().getSimpleName());
        }
        return target;
    }

    /**
     * Builds a ChatClient using {@code fallbackModelId} as the model, keeping all other
     * agent configuration (tools, advisors, system prompt) unchanged. Called by
     * {@code AgentStreamManager} when the primary model returns a rate-limit or quota error.
     */
    public ChatClient buildChatClientForFallback(AgentDefinition def, String sessionId, String userId,
            String orgId, RunOptions runOptions, String fallbackModelId) {
        return buildChatClient(def.withModelId(fallbackModelId), sessionId, userId, orgId, runOptions);
    }

    /**
     * @summary Instantiates a dynamic custom ChatModel from a ModelEntity DB record.
     * @logic Provider-based switch using the ModelEntity's provider field.
     *        Model name is taken directly from ModelEntity.getModelName() — no hardcoded fallbacks.
     */
    private org.springframework.ai.chat.model.ChatModel instantiateCustomChatModel(AgentDefinition def, ModelEntity me) {
        return customChatModelsCache.computeIfAbsent(me.getId(), id -> {
            log.info("Instantiating dynamic custom ChatModel for provider: {} (model: {})", me.getProvider(), me.getModelName());

            String modelName = me.getModelName();
            if (modelName == null || modelName.isBlank()) {
                throw new BusinessValidationException(
                        "ModelEntity '" + me.getName() + "' (id=" + me.getId() + ") has no 'modelName' configured. " +
                        "This is a database configuration error — every model must specify the provider's model identifier.");
            }

            com.operativus.agentmanager.compute.provider.DynamicModelProvider provider = modelProviderRegistry.get(me.getProvider().toUpperCase());
            if (provider != null) {
                return provider.buildChatModel(me, def);
            } else {
                throw new BusinessValidationException("Integration Error: No Provider registered for dynamic instantiation of: " + me.getProvider());
            }
        });
    }

    /**
     * @summary Builds ChatOptions for the resolved model, handling generic options.
     * @logic Configures standardized properties like temperature, topP, and effective model name natively via the strictly bound `ChatOptions.Builder`. Note: In Spring AI 2.0.0-SNAPSHOT, provider-specific builders do not implement base ChatOptions.Builder.
     */
    private org.springframework.ai.chat.prompt.ChatOptions.Builder<?> buildChatOptions(
            org.springframework.ai.chat.model.ChatModel targetModel,
            String effectiveModelId,
            AgentDefinition def,
            RunOptions runOptions) {

        // Resolve effective values: RunOptions -> Agent -> GlobalSettings -> hardcoded fallback
        double effectiveTemp = (runOptions != null && runOptions.temperature() != null)
                ? runOptions.temperature()
                : settingsService.resolveTemperature(def.temperature());
        String effectiveModel = (runOptions != null && runOptions.model() != null && !runOptions.model().isBlank()) ? runOptions.model() : effectiveModelId;
        Integer effectiveMaxTokens = (runOptions != null && runOptions.maxTokens() != null) ? runOptions.maxTokens() : null;

        // OpenAI reasoning models (o1/o3/o4 families) reject BOTH `temperature` and `top_p`
        // outright — sending either yields 400 "Unsupported parameter: 'temperature' is not
        // supported with this model." They accept only the API defaults. Detect by the resolved
        // model id and omit both sampling params; everything else keeps the prior behaviour.
        boolean isReasoningModel = isReasoningModel(effectiveModel);

        var builder = org.springframework.ai.chat.prompt.ChatOptions.builder();
        if (!isReasoningModel) {
            builder.temperature(effectiveTemp);
        }

        // Anthropic's API rejects requests that set BOTH temperature and top_p on its newer
        // models (Haiku 4.5+, Sonnet 4.5+, etc.) — 400 invalid_request_error. AGM previously
        // always sent both because resolveTopP returns a non-null default (0.9). Now we only
        // send top_p when (a) the user explicitly configured it on the agent or via RunOptions
        // AND (b) the target model is not an Anthropic model. Temperature always wins for
        // Anthropic; users who want top_p sampling there can blank temperature on the agent.
        boolean isAnthropic = targetModel instanceof org.springframework.ai.anthropic.AnthropicChatModel;
        Double explicitTopP = def.topP();
        if (explicitTopP != null && !isReasoningModel) {
            if (isAnthropic) {
                log.warn("Agent '{}' has top_p={} configured but the target Anthropic model rejects "
                        + "temperature+top_p together; suppressing top_p and using temperature={} only. "
                        + "Clear top_p on the agent to silence this warning.", def.id(), explicitTopP, effectiveTemp);
            } else {
                builder.topP(explicitTopP);
            }
        }
                
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            builder.model(effectiveModel);
        }
        
        if (effectiveMaxTokens != null) {
            builder.maxTokens(effectiveMaxTokens); // Supported natively in base builder as of 1.0.0-M1+
        }

        return builder;
    }

    /**
     * @summary True if the model id is an OpenAI reasoning model (o1/o3/o4 family).
     * @logic Those models reject `temperature` and `top_p` (400 "Unsupported parameter: ... is not
     * supported with this model"), so {@link #buildChatOptions} omits both for them. Matches an "o"
     * directly followed by a digit (o1, o1-mini, o3, o3-mini, o4-mini, …). Standard chat models such
     * as gpt-4o start with "gpt", so they are unaffected.
     */
    static boolean isReasoningModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        return modelId.trim().toLowerCase(java.util.Locale.ROOT).matches("^o[0-9].*");
    }
}
