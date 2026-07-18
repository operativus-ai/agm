package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.ModelProviderNames;
import com.operativus.agentmanager.core.registry.ModelOperations;
import com.operativus.agentmanager.core.registry.SettingsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Domain Responsibility: Centralized, single-source-of-truth resolver for determining which LLM ModelEntity an Agent should use at execution time.
 * State: Stateless
 *
 * @architecture This service owns the entire model resolution hierarchy:
 *   1. Agent-level explicit modelId (from AgentDefinition) → ModelEntity DB lookup.
 *   2. Role-based default (Router/Classifier vs. Heavy) → SettingsService DB/properties lookup → ModelEntity DB lookup.
 *   3. Unregistered/inline model ID (e.g., "gemini:gemini-2.5-pro") → Spring-managed ChatModel bean resolution.
 *
 * No other class in the system should contain model-selection branching logic or hardcoded fallback model strings. 
 * AgentClientFactory delegates exclusively to this service.
 */
@Service
public class AgentModelResolverService {

    private static final Logger log = LoggerFactory.getLogger(AgentModelResolverService.class);

    private final SettingsOperations settingsService;
    private final ModelOperations modelService;
    @SuppressWarnings("unused")
    private final org.springframework.core.env.Environment environment;
    /** Concrete provider strategies keyed by provider name (OPENAI / ANTHROPIC / GOOGLE / OLLAMA).
     *  Used to build ChatModels with DB-resolved API keys, bypassing the auto-configured
     *  Spring AI beans which ship with the {@code dummy-key-to-pass-validation} sentinel.
     *  See {@link com.operativus.agentmanager.compute.DynamicProviderInitializer}. */
    private final java.util.Map<String, com.operativus.agentmanager.compute.provider.DynamicModelProvider> dynamicProviders;

    public AgentModelResolverService(SettingsOperations settingsService,
                                      ModelOperations modelService,
                                      org.springframework.core.env.Environment environment,
                                      java.util.List<com.operativus.agentmanager.compute.provider.DynamicModelProvider> providers) {
        this.settingsService = settingsService;
        this.modelService = modelService;
        this.environment = environment;

        java.util.Map<String, com.operativus.agentmanager.compute.provider.DynamicModelProvider> reg = new java.util.HashMap<>();
        for (com.operativus.agentmanager.compute.provider.DynamicModelProvider p : providers) {
            for (String key : p.getProviderKeys()) {
                if (key != null) reg.put(key.toUpperCase(), p);
            }
        }
        this.dynamicProviders = java.util.Collections.unmodifiableMap(reg);
    }

    /**
     * @summary Builds a ChatModel via the DynamicModelProvider chain for a given model id.
     * @logic Looks up the {@link ModelEntity} by id, picks the registered provider strategy,
     *     and delegates to {@code buildChatModel} — which goes through
     *     {@code AbstractDynamicModelProvider.resolveApiKey} (per-model override →
     *     ProviderCredential default for caller orgId or DEFAULT_SYSTEM_ORG → throw).
     *     Returns null when no row matches or no provider is registered or key resolution fails;
     *     callers should treat null as "fall back to legacy bean" or "disable feature".
     */
    private org.springframework.ai.chat.model.ChatModel buildViaDynamicProvider(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        Optional<ModelEntity> meOpt = modelService.getModelEntityById(modelId);
        if (meOpt.isEmpty()) return null;
        ModelEntity me = meOpt.get();
        if (me.getProvider() == null) return null;
        com.operativus.agentmanager.compute.provider.DynamicModelProvider provider =
                dynamicProviders.get(me.getProvider().toUpperCase());
        if (provider == null) {
            log.debug("No DynamicModelProvider registered for provider '{}' on model '{}'", me.getProvider(), modelId);
            return null;
        }
        try {
            return provider.buildChatModel(me, null);
        } catch (Exception e) {
            log.warn("DynamicModelProvider build failed for model '{}': {}", modelId, e.getMessage());
            return null;
        }
    }

    /**
     * @summary Builds an {@code EmbeddingModel} via the DynamicModelProvider chain for a given
     *     model id — the embedding analog of {@link #buildViaDynamicProvider(String)}.
     * @logic Looks up the {@link ModelEntity}, resolves the registered provider strategy by its
     *     (case-insensitive) provider key, and delegates to {@code buildEmbeddingModel} — which
     *     resolves the API key the same way as the chat path (per-model override → ProviderCredential
     *     for the caller org / DEFAULT_SYSTEM_ORG). Returns null when no row matches, no provider is
     *     registered, the provider does not support embeddings, or the build fails (e.g. no key at
     *     startup) — callers treat null as "fall back to auto-config / NoOp". Centralizing this here
     *     keeps provider selection out of {@code EmbeddingModelFactory} (this class is the single
     *     home for model-selection logic) and lets ANY embedding-capable provider (OpenAI, Gemini,
     *     Ollama) back the vector store, not just OpenAI.
     */
    public org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        Optional<ModelEntity> meOpt = modelService.getModelEntityById(modelId);
        if (meOpt.isEmpty()) return null;
        ModelEntity me = meOpt.get();
        if (me.getProvider() == null) return null;
        com.operativus.agentmanager.compute.provider.DynamicModelProvider provider =
                dynamicProviders.get(me.getProvider().toUpperCase());
        if (provider == null) {
            log.debug("No DynamicModelProvider registered for embedding provider '{}' on model '{}'", me.getProvider(), modelId);
            return null;
        }
        try {
            return provider.buildEmbeddingModel(me);
        } catch (UnsupportedOperationException e) {
            log.warn("Provider '{}' does not support embeddings (model '{}')", me.getProvider(), modelId);
            return null;
        } catch (Exception e) {
            log.warn("DynamicModelProvider embedding build failed for model '{}': {}", modelId, e.getMessage());
            return null;
        }
    }

    /**
     * Resolved model context returned to the factory. Contains everything the factory
     * needs to instantiate the correct ChatModel without additional branching logic.
     *
     * @param modelEntity  The resolved ModelEntity from the database (null if using an unregistered inline ID).
     * @param effectiveModelId The final model ID string (e.g., "gemini-2.5-pro") for bean lookup or options binding.
     * @param resolvedVia  Human-readable description of how the model was resolved (for logging).
     */
    public record ResolvedModel(
            ModelEntity modelEntity,
            String effectiveModelId,
            String resolvedVia
    ) {
        /**
         * @return true if the resolution yielded a full ModelEntity from the DB (custom provider instantiation path).
         */
        public boolean hasCustomModel() {
            return modelEntity != null;
        }
    }

    /**
     * @summary Resolves the definitive model for the given agent, applying the full resolution chain.
     * @logic
     * 1. If the agent has an explicit modelId, attempt to find a matching ModelEntity in the database.
     *    - If found, return it (custom model path).
     *    - If NOT found, return an unregistered inline ResolvedModel for Spring bean resolution.
     * 2. If the agent has NO modelId, determine the correct default based on team mode:
     *    - ROUTER or CLASSIFIER → settingsService.getDefaultModelRouter()
     *    - All others → settingsService.getDefaultModelHeavy()
     * 3. Look up the default model string in the ModelEntity database.
     *    - If found, return it (custom model path with default config).
     *    - If NOT found, return an unregistered inline ResolvedModel for Spring bean resolution.
     */
    public ResolvedModel resolveModel(AgentDefinition def) {
        String modelId = def.modelId();

        // --- Phase 1: Explicit Agent Model ---
        if (modelId != null && !modelId.isBlank()) {
            Optional<ModelEntity> explicitModel = modelService.getModelEntityById(modelId);
            if (explicitModel.isPresent()) {
                ModelEntity me = explicitModel.get();
                log.info("Agent '{}': Resolved explicit ModelEntity '{}' (provider={}, modelName={})",
                        def.id(), me.getId(), me.getProvider(), me.getModelName());
                return new ResolvedModel(me, me.getModelName(), "explicit-agent-config");
            }
            // Not in DB — treat as unregistered inline model ID (e.g., "gemini:gemini-2.5-pro")
            log.info("Agent '{}': modelId '{}' not found in ModelEntity DB. Using as inline model ID for Spring bean resolution.",
                    def.id(), modelId);
            return new ResolvedModel(null, modelId, "inline-model-id");
        }

        // --- Phase 2: Role-Based Default Resolution ---
        String defaultModelId;
        String resolvedVia;
        if (def.isTeam() && isRouterOrClassifier(def.teamMode())) {
            defaultModelId = settingsService.getDefaultModelRouter();
            resolvedVia = "default-router (teamMode=" + def.teamMode() + ")";
            log.info("Agent '{}': No model specified. Resolved default ROUTER model: {}", def.id(), defaultModelId);
        } else {
            defaultModelId = settingsService.getDefaultModelHeavy();
            resolvedVia = "default-heavy";
            log.info("Agent '{}': No model specified. Resolved default HEAVY model: {}", def.id(), defaultModelId);
        }

        // --- Phase 3: Lookup Default in ModelEntity DB ---
        Optional<ModelEntity> defaultModel = modelService.getModelEntityById(defaultModelId);
        if (defaultModel.isPresent()) {
            ModelEntity me = defaultModel.get();
            log.info("Agent '{}': Default model '{}' resolved to ModelEntity (provider={}, modelName={})",
                    def.id(), defaultModelId, me.getProvider(), me.getModelName());
            return new ResolvedModel(me, me.getModelName(), resolvedVia);
        }

        // Default model string not registered as a ModelEntity — use as inline for Spring bean resolution
        log.info("Agent '{}': Default model '{}' not found in ModelEntity DB. Using as inline model ID for Spring bean resolution.",
                def.id(), defaultModelId);
        return new ResolvedModel(null, defaultModelId, resolvedVia);
    }

    /**
     * @summary Validates that the resolved model supports the capabilities required by the agent definition.
     * @logic
     * Inspects the Boolean capability flags on the ModelEntity. Throws a BusinessValidationException if an agent requires tools but the model explicitly does not support them, or if the agent requires system instructions and the model does not support them.
     * Unregistered inline models without a ModelEntity are skipped from validation.
     */
    public void validateCapabilities(AgentDefinition def, ResolvedModel resolved) {
        if (!resolved.hasCustomModel()) {
            // Unregistered inline models don't have capability metadata — skip validation
            return;
        }

        ModelEntity model = resolved.modelEntity();

        if (Boolean.FALSE.equals(model.getSupportsTools()) &&
                ((def.tools() != null && !def.tools().isEmpty()) || def.isTeam())) {
            throw new BusinessValidationException(
                    String.format("Model '%s' does not support tool calling, but agent '%s' requires tools or is a team.",
                            model.getName(), def.id())
            );
        }

        if (Boolean.FALSE.equals(model.getSupportsSystemInstructions()) &&
                def.instructions() != null && !def.instructions().isBlank()) {
            throw new BusinessValidationException(
                    String.format("Model '%s' does not support system instructions, which are required for agent '%s'.",
                            model.getName(), def.id())
            );
        }
    }

    private boolean isRouterOrClassifier(String teamMode) {
        return "ROUTER".equalsIgnoreCase(teamMode) || "CLASSIFIER".equalsIgnoreCase(teamMode);
    }

    /**
     * @summary Centralized resolution of the 'fast routing model' used for compression, reflection, and background jobs.
     * @return The bound ChatModel or null if no active fast provider is configured.
     */
    public org.springframework.ai.chat.model.ChatModel resolveFastRoutingModel() {
        String fastModelId = settingsService.getDefaultModelFast();

        // Build via DynamicModelProvider so the API key is resolved from the DB
        // (per-model override → ProviderCredential default for caller orgId or
        // DEFAULT_SYSTEM_ORG). The previously-used `chatModels` map of Spring AI
        // auto-configured beans is intentionally NOT consulted: those beans were
        // constructed at boot with `dummy-key-to-pass-validation` injected by
        // DynamicProviderInitializer, so they 400 with "API key not valid" against
        // any real provider. Returning null when the DB path can't build the model
        // lets callers like ReflectionService disable cleanly instead of crashing
        // every minute with a stack trace.
        org.springframework.ai.chat.model.ChatModel built = buildViaDynamicProvider(fastModelId);
        if (built == null) {
            log.warn("Fast routing model unavailable — fastModelId='{}' could not be built via the DynamicProvider chain. "
                    + "Configure either a per-model api_key on the ModelEntity or a provider_credentials row for "
                    + "(DEFAULT_SYSTEM_ORG, <provider>). Background features depending on this model (reflection, "
                    + "summarization) will be disabled.", fastModelId);
        }
        return built;
    }

    /**
     * @summary Resolves a specific ChatModel for agent-sovereign optimization tasks. Fallbacks to global fast router if null.
     */
    public org.springframework.ai.chat.model.ChatModel resolveOptimizationModel(String optimizationModelId) {
        if (optimizationModelId == null || optimizationModelId.isBlank()) {
            return resolveFastRoutingModel();
        }

        // Same DB-only policy as resolveFastRoutingModel — see that method's doc.
        org.springframework.ai.chat.model.ChatModel built = buildViaDynamicProvider(optimizationModelId);
        if (built != null) return built;
        return resolveFastRoutingModel();
    }
}
