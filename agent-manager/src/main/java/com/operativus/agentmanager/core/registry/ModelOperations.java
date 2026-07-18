package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.ModelDTO;
import com.operativus.agentmanager.core.model.ModelRequest;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Registry contract to access Model operations from the Control plane.
 */
public interface ModelOperations {
    List<ModelDTO> getAllModels();
    org.springframework.data.domain.Page<ModelDTO> getAllModels(org.springframework.data.domain.Pageable pageable);
    Optional<com.operativus.agentmanager.core.entity.ModelEntity> getModelEntityById(String id);
    ModelDTO createModel(ModelRequest request);
    ModelDTO updateModel(String id, ModelRequest request);
    void deleteModel(String id);
    void testConnection(ModelRequest request);
    /**
     * @summary Pings an existing saved model by ID — operator-fired liveness check.
     *     Returns a structured result rather than throwing, so the UI can surface latency
     *     and error message without parsing exception messages.
     */
    com.operativus.agentmanager.core.model.ModelPingResult pingExistingModel(String id);

    /**
     * @summary Clones an existing model into a new row with a fresh UUID. The encrypted
     *     {@code apiKey} blob is round-tripped through the OutboundApiKeyConverter (decrypt
     *     on read of source, re-encrypt on save of clone). Liveness state ({@code available},
     *     {@code lastPingedAt}) is intentionally NOT carried — clones are unprobed until
     *     pinged. {@code newName} is optional; null falls back to {@code "<source> (Clone)"}.
     */
    ModelDTO cloneModel(String sourceId, String newName);

    /**
     * @summary §6 M-12 Phase 4: explicitly clear a model's per-model rate-limit override
     *     by setting {@code rate_limit_rpm = NULL} and evicting the in-memory rate-limiter
     *     state. Idempotent — clearing a model whose RPM is already null is a no-op.
     *     Throws {@link com.operativus.agentmanager.core.exception.ResourceNotFoundException}
     *     if the model id is unknown.
     */
    void clearRateLimit(String id);
}
