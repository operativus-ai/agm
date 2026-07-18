package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.ModelDTO;
import ai.operativus.agentmanager.core.model.ModelRequest;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Domain Responsibility: REST API controller for interacting with LLM configurations.
 * State: Stateless
 * Dependencies: ModelOperations
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final ModelOperations modelService;

    public ModelController(ModelOperations modelService) {
        this.modelService = modelService;
    }

    /**
     * @summary Retrieves a paginated list of all configured LLM definitions across distinct providers.
     * @logic Accepts Spring Data Pageable and delegates to ModelService.
     */
    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<ModelDTO>> listModels(@org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        return ResponseEntity.ok(modelService.getAllModels(pageable));
    }

    /**
     * @summary Registers a new LLM provider target definition.
     * @logic
     * - Takes a payload with Provider, API Key, and URL identifiers.
     * - Stores in underlying configuration database/store via Service layer.
     */
    @PostMapping
    public ResponseEntity<ModelDTO> createModel(@Valid @RequestBody ModelRequest request) {
        ModelDTO created = modelService.createModel(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * @summary Explicitly tests connectivity and authorization limits for an LLM provider structure.
     * @logic
     * - Forwards configuration request to Service to fire a test ping/completion towards the LLM API.
     */
    @PostMapping("/test")
    public ResponseEntity<Void> testConnection(@RequestBody ModelRequest request) {
        modelService.testConnection(request);
        return ResponseEntity.ok().build();
    }

    /**
     * @summary Pings an existing saved model by ID and returns a structured liveness result.
     *     Always responds 200 OK with a {@link ai.operativus.agentmanager.core.model.ModelPingResult}
     *     body — failure is encoded in the response body's {@code available=false} +
     *     {@code errorMessage} fields rather than as an HTTP error code, so the UI can
     *     render rich diagnostics without parsing exception messages.
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<ai.operativus.agentmanager.core.model.ModelPingResult> testExistingModel(@PathVariable("id") String id) {
        return ResponseEntity.ok(modelService.pingExistingModel(id));
    }

    /**
     * @summary Clones an existing model into a new row, optionally renaming the clone.
     * @logic Source apiKey + provider config + capability flags are carried over; liveness
     *     state and default-slot assignment are not (clones are unprobed and unassigned).
     *     The optional {@code newName} query parameter overrides the default
     *     {@code "<source name> (Clone)"} suffix. Returns {@code 201 Created} with the new
     *     ModelDTO. {@code ResourceNotFoundException} → {@code 404} via the global handler;
     *     {@code BusinessValidationException} (name collision, unsupported provider) →
     *     {@code 400} via the global handler — matches the {@code createModel} contract.
     */
    @PostMapping("/{id}/clone")
    public ResponseEntity<ModelDTO> cloneModel(
            @PathVariable("id") String id,
            @RequestParam(value = "newName", required = false) String newName) {
        ModelDTO clone = modelService.cloneModel(id, newName);
        return ResponseEntity.status(HttpStatus.CREATED).body(clone);
    }

    /**
     * @summary Updates an existing LLM configuration.
     * @logic
     * - Intercepts a PATCH payload to modify aspects of a registered LLM profile setup.
     * - Safely returns 404 if the target ID to patch is invalid.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ModelDTO> updateModel(@PathVariable("id") String id, @Valid @RequestBody ModelRequest request) {
        try {
            ModelDTO updated = modelService.updateModel(id, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * @summary Performs a hard delete on a configured Agentic LLM target structure.
     * @logic
     * - Drops record out of local metadata repository setup payload via Service mapping.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable("id") String id) {
        try {
            modelService.deleteModel(id);
            return ResponseEntity.noContent().build();
        } catch (BusinessValidationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * @summary §6 M-12 Phase 4: explicitly clear a model's per-model rate-limit override.
     * @logic Dedicated DELETE verb because PUT /api/models/{id} treats null in the
     *     {@code rateLimitRpm} field as "keep existing" (no sentinel to express clear).
     *     Returns 204 on success regardless of whether the column was already null
     *     ({@link ModelService#clearRateLimit} is idempotent). 404 if the model id is
     *     unknown; 403 if the caller is not ROLE_ADMIN (enforced at the service layer
     *     via {@code @PreAuthorize}).
     */
    @DeleteMapping("/{id}/rate-limit")
    public ResponseEntity<Void> clearRateLimit(@PathVariable("id") String id) {
        modelService.clearRateLimit(id);
        return ResponseEntity.noContent().build();
    }
}
