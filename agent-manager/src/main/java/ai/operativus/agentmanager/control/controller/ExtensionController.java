package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.model.ExtensionRegistrationDTO;
import ai.operativus.agentmanager.core.model.McpTransport;
import ai.operativus.agentmanager.core.model.ValidationResponseDTO;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.security.SsrfGuard;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/extensions")
public class ExtensionController {

    private final ExtensionRegistrationRepository repository;
    private final StringRedisTemplate redisTemplate;

    /**
     * When true, the write-time SSRF guard permits loopback / RFC-1918 / site-local extension URLs.
     * Defaults to {@code false} (secure: production rejects them). Enabled only in test/dev profiles
     * where webhook fixtures point at a local WireMock server. Field-injected so a directly
     * constructed controller (unit tests) keeps the secure default.
     */
    @org.springframework.beans.factory.annotation.Value("${agm.extensions.ssrf.allow-loopback:false}")
    private boolean ssrfAllowLoopback;

    public ExtensionController(ExtensionRegistrationRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    /** Caller's tenant, falling back to the system org when no context is bound (#1132). */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }

    @GetMapping
    public ResponseEntity<List<ExtensionRegistrationDTO>> getExtensions() {
        // 1. Load this org's database-registered extensions (WEBHOOK, MCP) — tenant-scoped (#1132)
        List<ExtensionRegistrationDTO> dtos = new java.util.ArrayList<>(
                repository.findByOrgId(callerOrgId()).stream()
                        .map(this::toDto)
                        .collect(Collectors.toList())
        );

        // 2. Merge native Java SPI extensions as read-only NATIVE_SPI entries
        for (ai.operativus.agentmanager.core.spi.AgentHookExtension spiHook :
                java.util.ServiceLoader.load(ai.operativus.agentmanager.core.spi.AgentHookExtension.class)) {
            String spiId = spiHook.getExtensionId();
            // Avoid duplicates if an SPI extension was also manually registered in the DB
            boolean alreadyRegistered = dtos.stream().anyMatch(d -> spiId.equals(d.id()));
            if (!alreadyRegistered) {
                dtos.add(new ExtensionRegistrationDTO(
                        spiId,
                        spiId, // Use extensionId as the display name
                        "NATIVE_SPI",
                        null, // No URL for compiled Java plugins
                        "Native Java SPI Extension (read-only)",
                        true
                ));
            }
        }

        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ExtensionRegistrationDTO> registerExtension(@RequestBody ExtensionRegistrationDTO data) {
        rejectSsrfUrlIfPresent(data);
        ExtensionRegistrationEntity entity = new ExtensionRegistrationEntity();
        entity.setId(data.id() != null ? data.id() : UUID.randomUUID().toString());
        entity.setName(data.name());
        entity.setType(data.type());
        entity.setUrl(data.url());
        entity.setDescription(data.description());
        entity.setActive(data.active());
        entity.setOrgId(callerOrgId());
        entity.setTransport(parseTransportOrThrow(data.transport()).name());
        if (data.auth() != null && !data.auth().isBlank()) {
            entity.setAuthSecret(data.auth());
        }

        entity = repository.save(entity);

        // Publish Redis PubSub event for cluster-wide MCP synchronization. Body is the
        // extension id ONLY — peers re-load the row (incl. transport + decrypted auth) from
        // the DB in McpConnectionPool.connect, so the outbound secret never crosses the wire.
        if ("MCP".equalsIgnoreCase(entity.getType()) && entity.getUrl() != null) {
            redisTemplate.convertAndSend("extension:registered", entity.getId());
        }

        return ResponseEntity.ok(toDto(entity));
    }

    /**
     * Updates an existing extension with client-known-version conflict detection.
     * The {@code version} field on the inbound DTO is REQUIRED and is compared against
     * the currently-loaded entity's version; on mismatch the call returns 409 via
     * {@link GlobalExceptionHandler}'s handler for
     * {@link ObjectOptimisticLockingFailureException}. JPA's {@code @Version} provides
     * defense-in-depth at the DB layer if two requests race past the equality check.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ExtensionRegistrationDTO> updateExtension(
            @PathVariable String id,
            @RequestBody ExtensionRegistrationDTO data) {
        ExtensionRegistrationEntity entity = repository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "extension '" + id + "' not found"));
        if (data.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "version is required for PUT — supply the version returned by GET or POST");
        }
        if (!Objects.equals(data.version(), entity.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(
                    ExtensionRegistrationEntity.class, id);
        }
        rejectSsrfUrlIfPresent(data);
        entity.setName(data.name());
        entity.setType(data.type());
        entity.setUrl(data.url());
        entity.setDescription(data.description());
        entity.setActive(data.active());
        entity.setTransport(parseTransportOrThrow(data.transport()).name());
        // auth is write-only: null leaves the stored secret untouched (the masked preview
        // round-trips without the raw value); a blank string clears it; non-blank replaces it.
        if (data.auth() != null) {
            entity.setAuthSecret(data.auth().isBlank() ? null : data.auth());
        }
        entity = repository.saveAndFlush(entity);
        // No pool re-sync here: connect() is a no-op for an already-connected id. Operators
        // apply a changed transport/auth via POST /api/mcp/servers/{id}/reconnect, which
        // disconnects then reconnects, re-reading the row from the DB.
        return ResponseEntity.ok(toDto(entity));
    }

    // Write-time SSRF guard: reject extension URLs pointing at loopback / RFC-1918 /
    // 169.254 cloud-metadata / non-http(s) schemes. Applies to both MCP and WEBHOOK
    // types (WEBHOOK runtime is also separately guarded in WebhookWorkflowStepExecutor;
    // MCP runtime is separately guarded in McpConnectionPool.connect — see #1013).
    // NATIVE_SPI entries have no URL and are merged into the GET response from the
    // ServiceLoader, never persisted via this controller, so they are not affected.
    private void rejectSsrfUrlIfPresent(ExtensionRegistrationDTO data) {
        if (data.url() == null || data.url().isBlank()) {
            return;
        }
        String reason = SsrfGuard.validate(data.url(), ssrfAllowLoopback);
        if (reason != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "extension URL rejected by SSRF guard: " + reason);
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteExtension(@PathVariable String id) {
        // Tenant-scoped: a cross-org id simply isn't found, so the delete is a silent no-op
        // (204) — same non-existence-leaking contract as a genuinely missing id (#1132).
        repository.findByIdAndOrgId(id, callerOrgId()).ifPresent(ext -> {
            // Publish Redis PubSub event before deletion for cluster-wide MCP cleanup
            if ("MCP".equalsIgnoreCase(ext.getType())) {
                redisTemplate.convertAndSend("extension:deleted", id);
            }
            repository.deleteById(id);
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ValidationResponseDTO> validateConnection(@RequestBody ExtensionRegistrationDTO data) {
        // True validation using actual presence instead of mock '42'
        boolean exists = data.id() != null && repository.existsByIdAndOrgId(data.id(), callerOrgId());
        return ResponseEntity.ok(new ValidationResponseDTO(exists, exists ? "Validation successful" : "Not found", exists ? 200 : 404));
    }
    
    private ExtensionRegistrationDTO toDto(ExtensionRegistrationEntity entity) {
        return new ExtensionRegistrationDTO(
            entity.getId(),
            entity.getName(),
            entity.getType(),
            entity.getUrl(),
            entity.getDescription(),
            Boolean.TRUE.equals(entity.getActive()),
            entity.getVersion(),
            entity.getTransport() != null ? entity.getTransport() : McpTransport.SSE.name(),
            null,                              // auth is write-only — never echoed
            maskTail(entity.getAuthSecret())   // masked hint only
        );
    }

    /**
     * Validates the inbound transport against {@link McpTransport}, defaulting null/blank to SSE.
     * Unknown values are a client error (400) rather than reaching the connect-time switch.
     */
    private static McpTransport parseTransportOrThrow(String transport) {
        try {
            return McpTransport.from(transport);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown transport '" + transport + "' — expected SSE or STREAMABLE_HTTP");
        }
    }

    /** Masked preview of the stored auth secret ({@code ****last4}), or null when none is set. */
    private static String maskTail(String secret) {
        if (secret == null || secret.isBlank()) return null;
        if (secret.length() < 4) return "****";
        return "****" + secret.substring(secret.length() - 4);
    }
}
