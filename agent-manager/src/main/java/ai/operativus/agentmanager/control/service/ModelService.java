package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.model.ModelDTO;
import ai.operativus.agentmanager.core.model.ModelRequest;
import ai.operativus.agentmanager.control.repository.ModelRepository;
import ai.operativus.agentmanager.control.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Domain Responsibility: Manages the configuration, metadata, and connectivity testing for external LLM entities.
 * State: Stateless (Caches configured models)
 */
import ai.operativus.agentmanager.core.registry.ModelOperations;
import ai.operativus.agentmanager.compute.provider.DynamicModelProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
import java.util.HashMap;

@Service
public class ModelService implements ModelOperations {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    /** Rolling window for the {@code runCount} usage stat surfaced in {@link ModelDTO}.
     *  30 days matches the dashboard convention for cost-slice charts; if a model has
     *  zero runs in that window the column shows 0 (not "never used"). */
    static final int USAGE_WINDOW_DAYS = 30;
    private final ModelRepository modelRepository;
    private final ai.operativus.agentmanager.control.repository.AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final SettingsService settingsService;
    private final Map<String, DynamicModelProvider> providerRegistry = new HashMap<>();
    /** §6 M-12 Phase 4: lazy provider for the per-model rate-limit guard. Lazy because
     *  ModelRateLimitGuard depends on ModelOperations (this service) — direct injection
     *  would create a Spring init cycle. {@code clearRateLimit} consults
     *  {@link ai.operativus.agentmanager.compute.security.ModelRateLimitGuard#invalidate}
     *  to evict the cached limiter (Anti-pattern A1 mitigation in
     *  {@code docs/plans-execute-whats-left.md}). */
    private final org.springframework.beans.factory.ObjectProvider<ai.operativus.agentmanager.compute.security.ModelRateLimitGuard> rateLimitGuardProvider;

    public ModelService(ModelRepository modelRepository,
                        ai.operativus.agentmanager.control.repository.AgentRepository agentRepository,
                        RunRepository runRepository,
                        SettingsService settingsService,
                        List<DynamicModelProvider> providers,
                        org.springframework.beans.factory.ObjectProvider<ai.operativus.agentmanager.compute.security.ModelRateLimitGuard> rateLimitGuardProvider) {
        this.modelRepository = modelRepository;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.settingsService = settingsService;
        this.rateLimitGuardProvider = rateLimitGuardProvider;
        if (providers != null) {
            for (DynamicModelProvider provider : providers) {
                for (String key : provider.getProviderKeys()) {
                    providerRegistry.put(key.toUpperCase(), provider);
                }
            }
        }
    }

    /**
     * @summary Retrieves all active model configurations, leveraging a global cache.
     * @logic Queries the repository for all ModelEntities, maps them to immutable DTOs, and caches the entire list under the 'models:all' key.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Cacheable(value = "models", key = "'all'")
    public List<ModelDTO> getAllModels() {
        Map<String, Long> agentCounts = loadAgentCounts();
        Map<String, Long> runCounts = loadRunCounts();
        return modelRepository.findAll().stream()
                .map(e -> mapToDTO(e,
                        agentCounts.getOrDefault(e.getId(), 0L),
                        runCounts.getOrDefault(e.getId(), 0L)))
                .collect(Collectors.toList());
    }

    /**
     * @summary Retrieves a paginated set of model configurations.
     * @logic Delegates to the JPA repository's {@code findAll(Pageable)} and maps entities to DTOs via {@code Page.map()}.
     *        Note: Not cached — paginated queries generate variable keys. The unpaginated variant and single-model cache remain active.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public org.springframework.data.domain.Page<ModelDTO> getAllModels(org.springframework.data.domain.Pageable pageable) {
        Map<String, Long> agentCounts = loadAgentCounts();
        Map<String, Long> runCounts = loadRunCounts();
        return modelRepository.findAll(pageable).map(e -> mapToDTO(e,
                agentCounts.getOrDefault(e.getId(), 0L),
                runCounts.getOrDefault(e.getId(), 0L)));
    }

    /**
     * @summary Retrieves a raw ModelEntity for internal orchestration use (bypassing DTO cache).
     * @logic Directly queries the repository by ID.
     */
    public Optional<ModelEntity> getModelEntityById(String id) {
        return modelRepository.findById(id);
    }

    /**
     * @summary Registers a new LLM provider configuration and invalidates model caches.
     * @logic Validates required fields (name, provider, modelName) strictly. Constructs a new ModelEntity with a generated UUID, populates configuration and capability flags, persists the entity, and evicts the 'models' cache.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = "models", allEntries = true)
    public ModelDTO createModel(ModelRequest request) {
        String providerKey = request.provider().toUpperCase();
        if (!providerRegistry.containsKey(providerKey)) {
            throw new BusinessValidationException("Unsupported provider: " + request.provider());
        }
        if (modelRepository.existsByName(request.name())) {
            throw new BusinessValidationException("A model named '" + request.name() + "' already exists");
        }

        log.info("Creating new model configuration: {}", request.name());
        ModelEntity entity = new ModelEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setProvider(request.provider());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKey(request.apiKey());
        entity.setModelName(request.modelName());
        
        if (request.supportsTools() != null) entity.setSupportsTools(request.supportsTools());
        if (request.supportsVision() != null) entity.setSupportsVision(request.supportsVision());
        if (request.supportsSystemInstructions() != null) entity.setSupportsSystemInstructions(request.supportsSystemInstructions());
        if (request.maxContextTokens() != null) entity.setMaxContextTokens(request.maxContextTokens());
        if (request.maxOutputTokens() != null) entity.setMaxOutputTokens(request.maxOutputTokens());
        if (request.thinkingBudgetTokens() != null) entity.setThinkingBudgetTokens(request.thinkingBudgetTokens());
        if (request.modelType() != null) entity.setModelType(request.modelType());
        if (request.rateLimitRpm() != null) entity.setRateLimitRpm(request.rateLimitRpm());

        ModelEntity savedEntity = modelRepository.save(entity);
        if (request.defaultSlot() != null) {
            settingsService.updateSettings(Map.of(request.defaultSlot().toSettingsKey(), savedEntity.getId()));
        }
        return mapToDTO(savedEntity, 0L, 0L);
    }

    /**
     * @summary Modifies an existing LLM configuration and invalidates model caches.
     * @logic Fetches the target entity by ID, conditionally applies updates for all provided non-null fields from the request, persists the modified entity, and evicts the 'models' cache globally.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = "models", allEntries = true)
    public ModelDTO updateModel(String id, ModelRequest request) {
        if (request.provider() != null && !providerRegistry.containsKey(request.provider().toUpperCase())) {
            throw new BusinessValidationException("Unsupported provider: " + request.provider());
        }
        if (request.name() != null && modelRepository.existsByNameAndIdNot(request.name(), id)) {
            throw new BusinessValidationException("A model named '" + request.name() + "' already exists");
        }
        log.info("Updating model configuration: {}", id);
        return modelRepository.findById(id).map(entity -> {
            Optional.ofNullable(request.name()).ifPresent(entity::setName);
            Optional.ofNullable(request.provider()).ifPresent(entity::setProvider);
            Optional.ofNullable(request.baseUrl()).ifPresent(entity::setBaseUrl);
            Optional.ofNullable(request.apiKey()).filter(s -> !s.isBlank()).ifPresent(entity::setApiKey);
            Optional.ofNullable(request.modelName()).ifPresent(entity::setModelName);
            Optional.ofNullable(request.supportsTools()).ifPresent(entity::setSupportsTools);
            Optional.ofNullable(request.supportsVision()).ifPresent(entity::setSupportsVision);
            Optional.ofNullable(request.supportsSystemInstructions()).ifPresent(entity::setSupportsSystemInstructions);
            Optional.ofNullable(request.maxContextTokens()).ifPresent(entity::setMaxContextTokens);
            Optional.ofNullable(request.maxOutputTokens()).ifPresent(entity::setMaxOutputTokens);
            Optional.ofNullable(request.thinkingBudgetTokens()).ifPresent(entity::setThinkingBudgetTokens);
            Optional.ofNullable(request.modelType()).ifPresent(entity::setModelType);
            // Update semantics for rateLimitRpm: present non-null = replace; absent (null) = keep.
            // Operators clear an override by setting it to null in the request body? No — null is
            // the "absent" sentinel. To clear, the caller would need a sentinel value. For Phase 1
            // we keep the simpler "any null = keep existing" rule and defer "explicit clear" to
            // Phase 3 (UI form will offer a Clear button that issues an explicit clear endpoint).
            Optional.ofNullable(request.rateLimitRpm()).ifPresent(entity::setRateLimitRpm);

            ModelEntity updatedEntity = modelRepository.save(entity);
            if (request.defaultSlot() != null) {
                settingsService.updateSettings(Map.of(request.defaultSlot().toSettingsKey(), updatedEntity.getId()));
            }
            return mapToDTO(updatedEntity,
                    agentRepository.countByModelId(updatedEntity.getId()),
                    runCountFor(updatedEntity.getId()));
        }).orElseThrow(() -> new ResourceNotFoundException("Model", id));
    }

    /**
     * @summary §6 M-12 Phase 4: explicitly clear a model's per-model rate-limit override.
     * @logic Sets {@code rate_limit_rpm = NULL} on the row (PUT update semantics treat null
     *     as "keep existing", so PUT can't express clear; this dedicated DELETE verb is the
     *     affordance). Must invalidate the cached {@link ai.operativus.agentmanager.compute.security.ModelRateLimitGuard}
     *     limiter inside the same {@code @Transactional} boundary that nulls the column —
     *     without that hook the in-memory Resilience4j RateLimiter would linger for the JVM
     *     lifetime and a subsequent re-set of the same RPM could race against the stale
     *     instance (Anti-pattern A1 in {@code docs/plans-execute-whats-left.md}).
     *     Idempotent: clearing a model whose RPM is already null is a 204 no-op.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = "models", allEntries = true)
    public void clearRateLimit(String id) {
        ModelEntity entity = modelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Model", id));
        if (entity.getRateLimitRpm() != null) {
            log.info("Clearing per-model rate limit for model {} (was {} rpm)", id, entity.getRateLimitRpm());
            entity.setRateLimitRpm(null);
            modelRepository.save(entity);
        }
        // Always invalidate the guard, even if the column was already null — a leftover
        // limiter from a recently-cleared row that didn't go through this method (e.g. a
        // direct DB edit) would otherwise persist.
        ai.operativus.agentmanager.compute.security.ModelRateLimitGuard guard =
                rateLimitGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.invalidate(id);
        }
    }

    /**
     * @summary Clones an existing model row into a new one with a fresh UUID and "(Clone)"
     *     suffixed name. Carries provider config + capability flags + encrypted apiKey;
     *     does NOT carry liveness state (clones start unprobed until pinged) or default-slot
     *     assignment (slots live in SettingsService and reference exactly one modelId at a
     *     time, so the source keeps its slot).
     * @logic Loads the source entity, validates the resolved clone name is unique, builds a
     *     new entity with field-by-field copy, and persists. The {@code apiKey} field uses
     *     the {@link ai.operativus.agentmanager.control.security.OutboundApiKeyConverter}
     *     JPA converter which decrypts on entity load and re-encrypts on save — so the
     *     plaintext lives only in memory between {@code source.getApiKey()} and
     *     {@code modelRepository.save(clone)}.
     */
    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = "models", allEntries = true)
    public ModelDTO cloneModel(String sourceId, String newName) {
        ModelEntity source = modelRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Model", sourceId));

        String resolvedName = (newName != null && !newName.isBlank())
                ? newName
                : source.getName() + " (Clone)";
        if (modelRepository.existsByName(resolvedName)) {
            throw new BusinessValidationException("A model named '" + resolvedName + "' already exists");
        }

        log.info("Cloning model {} as '{}'", sourceId, resolvedName);
        ModelEntity clone = new ModelEntity();
        clone.setId(UUID.randomUUID().toString());
        clone.setName(resolvedName);
        clone.setProvider(source.getProvider());
        clone.setBaseUrl(source.getBaseUrl());
        clone.setApiKey(source.getApiKey());
        clone.setModelName(source.getModelName());
        clone.setSupportsTools(source.getSupportsTools());
        clone.setSupportsVision(source.getSupportsVision());
        clone.setSupportsSystemInstructions(source.getSupportsSystemInstructions());
        clone.setMaxContextTokens(source.getMaxContextTokens());
        clone.setMaxOutputTokens(source.getMaxOutputTokens());
        clone.setThinkingBudgetTokens(source.getThinkingBudgetTokens());
        clone.setModelType(source.getModelType());
        clone.setRateLimitRpm(source.getRateLimitRpm());

        ModelEntity saved = modelRepository.save(clone);
        return mapToDTO(saved, 0L, 0L);
    }

    /**
     * @summary Removes a model configuration from the system and invalidates caches.
     * @logic Executes a hard delete via the repository and evicts the 'models' cache globally.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = "models", allEntries = true)
    public void deleteModel(String id) {
        List<ai.operativus.agentmanager.core.entity.AgentEntity> dependents = agentRepository.findByModelId(id);
        if (!dependents.isEmpty()) {
            String names = dependents.stream()
                    .map(ai.operativus.agentmanager.core.entity.AgentEntity::getName)
                    .collect(Collectors.joining(", "));
            throw new BusinessValidationException(
                    "Cannot delete model: " + dependents.size() + " agent(s) still reference it: " + names);
        }
        log.info("Deleting model configuration: {}", id);
        modelRepository.deleteById(id);
    }

    /**
     * @summary Performs a live connectivity check directly against the configured LLM provider API.
     * @logic Interrogates the requested provider and type (Chat/Embedding), instantiates a standalone Spring AI client using the provided baseUrl and apiKey, executes a minimal test prompt or embedding request, and logs success or throws a BusinessValidationException on failure.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public void testConnection(ModelRequest request) {
        log.info("Testing connection for model provider: {}", request.provider());

        String modelName = request.modelName();
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessValidationException("Model name (modelName) is required to test a connection.");
        }

        try {
            ModelEntity tempEntity = new ModelEntity();
            tempEntity.setProvider(request.provider());
            tempEntity.setModelName(modelName);
            tempEntity.setBaseUrl(request.baseUrl());
            tempEntity.setApiKey(request.apiKey());

            DynamicModelProvider provider = providerRegistry.get(request.provider().toUpperCase());
            if (provider == null) {
                throw new BusinessValidationException("Test connection failed: No provider registered for " + request.provider());
            }

            if (ai.operativus.agentmanager.core.entity.ModelType.EMBEDDING.equals(request.modelType())) {
                org.springframework.ai.embedding.EmbeddingModel embeddingModel = provider.buildEmbeddingModel(tempEntity);
                embeddingModel.embed("Test connection");
            } else {
                ChatModel chatModel = provider.buildChatModel(tempEntity, null);
                ChatResponse response = chatModel.call(new Prompt("Respond with the word 'OK'"));
                log.debug("Test response: {}", response.toString());
            }

            log.info("Test connection successful for provider: {}", request.provider());
        } catch (Exception e) {
            log.error("Test connection failed: {}", e.getMessage());
            throw new BusinessValidationException("Connection test failed: " + e.getMessage());
        }
    }

    /**
     * @summary Pings an existing saved model by ID and returns a structured liveness result.
     *     Operator-facing manual check — does NOT update any persistent {@code available}
     *     flag (that's the future scheduled-poller's job per agm-leftover.md §7).
     * @logic Loads the entity, dispatches to its provider's chat or embedding builder, runs
     *     a single tiny inference, and times the round-trip. Catches every exception so the
     *     caller can render the error message rather than a 500.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ai.operativus.agentmanager.core.model.ModelPingResult pingExistingModel(String id) {
        ModelEntity entity = modelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Model", id));
        return pingEntity(entity);
    }

    /**
     * @summary Internal ping primitive — runs the actual provider round-trip and returns a structured result.
     * @logic Same logic that powers {@link #pingExistingModel(String)} but without the {@code @PreAuthorize}
     *     gate, so it can be invoked from server-side schedulers (e.g. {@code ModelAvailabilityPoller}) that
     *     have no Spring SecurityContext bound. Callers that go through HTTP MUST use {@link #pingExistingModel}
     *     so the admin role check applies.
     */
    public ai.operativus.agentmanager.core.model.ModelPingResult pingEntity(ModelEntity entity) {
        String id = entity.getId();
        long start = System.currentTimeMillis();
        try {
            DynamicModelProvider provider = providerRegistry.get(entity.getProvider().toUpperCase());
            if (provider == null) {
                long elapsed = System.currentTimeMillis() - start;
                return new ai.operativus.agentmanager.core.model.ModelPingResult(
                        id, false, elapsed, "No provider registered for " + entity.getProvider());
            }
            if (ai.operativus.agentmanager.core.entity.ModelType.EMBEDDING.equals(entity.getModelType())) {
                org.springframework.ai.embedding.EmbeddingModel embeddingModel = provider.buildEmbeddingModel(entity);
                embeddingModel.embed("ping");
            } else {
                ChatModel chatModel = provider.buildChatModel(entity, null);
                chatModel.call(new Prompt("Respond with the word 'OK'"));
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("Ping success for model {} ({}ms)", id, elapsed);
            return new ai.operativus.agentmanager.core.model.ModelPingResult(id, true, elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Ping failed for model {} ({}ms): {}", id, elapsed, e.getMessage());
            return new ai.operativus.agentmanager.core.model.ModelPingResult(id, false, elapsed, e.getMessage());
        }
    }

    /**
     * @summary Translates an internal ModelEntity into an immutable ModelDTO.
     * @logic Extracts all scalar fields, including generation limits and capability flags, into a new ModelDTO instance.
     */
    private Map<String, Long> loadAgentCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : agentRepository.countAgentsGroupedByModelId()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** Loads the run-count map keyed by model_id over the rolling
     *  {@link #USAGE_WINDOW_DAYS} window. The aggregated query surfaces
     *  {@code 'unknown'} for runs whose agents have a null {@code model_id};
     *  drop those rows since they don't correspond to a real ModelEntity. */
    private Map<String, Long> loadRunCounts() {
        LocalDateTime since = LocalDateTime.now().minusDays(USAGE_WINDOW_DAYS);
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : runRepository.findRunCountByModel(since, null)) {
            String modelId = (String) row[0];
            if ("unknown".equals(modelId)) continue;
            counts.put(modelId, ((Number) row[1]).longValue());
        }
        return counts;
    }

    private long runCountFor(String modelId) {
        return runRepository.countRunsByModelId(modelId, LocalDateTime.now().minusDays(USAGE_WINDOW_DAYS));
    }

    private ModelDTO mapToDTO(ModelEntity entity, long agentCount, long runCount) {
        return new ModelDTO(
                entity.getId(),
                entity.getName(),
                entity.getProvider(),
                entity.getBaseUrl(),
                entity.getModelName(),
                entity.getSupportsTools(),
                entity.getSupportsVision(),
                entity.getSupportsSystemInstructions(),
                entity.getMaxContextTokens(),
                entity.getMaxOutputTokens(),
                entity.getThinkingBudgetTokens(),
                entity.getModelType(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                agentCount,
                entity.getAvailable(),
                entity.getLastPingedAt(),
                runCount,
                entity.getRateLimitRpm(),
                entity.getApiKey() != null && !entity.getApiKey().isBlank()
        );
    }
}
