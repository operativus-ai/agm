package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.dto.composio.ComposioActionConfigCreateRequest;
import com.operativus.agentmanager.control.dto.composio.ComposioActionConfigResponse;
import com.operativus.agentmanager.control.dto.composio.ComposioActionConfigUpdateRequest;
import com.operativus.agentmanager.control.dto.composio.ComposioConnectionConfigResponse;
import com.operativus.agentmanager.control.dto.composio.ComposioConnectionConfigUpsertRequest;
import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import com.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import com.operativus.agentmanager.core.entity.ComposioConnectionConfig;
import com.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.exception.StaleDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain Responsibility: Service layer for the DB-backed Composio action-config
 *   admin surface. Each mutation publishes {@link ComposioConfigChangedEvent} so
 *   {@code ComposioActionRegistry} can hot-reload without app restart, and writes a
 *   row to {@code system_audits} for forensic attribution.
 *
 * <p><strong>Identity contract:</strong> the entity {@code id} is derived from
 *   {@code actionName.toLowerCase()} so two rows can never differ only by ID case.
 *   Caller-supplied {@code id} is rejected at the DTO layer (the request DTO has no
 *   id field). Same derivation rule applies to {@code llmToolName}
 *   ({@code "composio_" + actionName.toLowerCase()}).
 *
 * State: Stateless. Optimistic locking on {@code @Version} for concurrent operator
 *   edits — surfaces as 409 via {@link GlobalExceptionHandler}.
 */
@Service
public class ComposioConfigService {

    private static final Logger log = LoggerFactory.getLogger(ComposioConfigService.class);
    private static final String AUDIT_RESOURCE_TYPE = "composio_action_config";
    private static final String CONNECTION_AUDIT_RESOURCE_TYPE = "composio_connection_config";
    private static final String CONNECTION_BASE_PATH = "/api/admin/composio/connection";

    private final ComposioActionConfigRepository repository;
    private final ComposioConnectionConfigRepository connectionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemAuditService systemAuditService;

    public ComposioConfigService(ComposioActionConfigRepository repository,
                                 ComposioConnectionConfigRepository connectionRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 SystemAuditService systemAuditService) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.eventPublisher = eventPublisher;
        this.systemAuditService = systemAuditService;
    }

    @Transactional(readOnly = true)
    public List<ComposioActionConfigResponse> listActions() {
        return repository.findAll().stream()
                .map(ComposioActionConfigResponse::from)
                .toList();
    }

    @Transactional
    public ComposioActionConfigResponse createAction(ComposioActionConfigCreateRequest request,
                                                     String callerOrgId,
                                                     String callerUsername) {
        Objects.requireNonNull(request.actionName(), "actionName must not be null");
        String actionName = request.actionName().trim().toUpperCase();
        if (actionName.isEmpty()) {
            throw new BusinessValidationException("actionName must not be blank");
        }
        String id = actionName.toLowerCase();
        if (repository.existsById(id)) {
            // Map to BusinessValidationException → 400 by GlobalExceptionHandler;
            // alternatively could surface as 409. Use 400 for "you sent something that
            // would create a duplicate" — matches DELETE/CREATE asymmetry of REST.
            throw new BusinessValidationException("Composio action already exists: " + actionName);
        }

        ComposioActionConfig entity = new ComposioActionConfig();
        entity.setId(id);
        entity.setActionName(actionName);
        entity.setLlmToolName("composio_" + id);
        entity.setTier(request.tier());
        entity.setEnabled(request.enabled());
        entity.setCreatedBy(callerUsername);

        ComposioActionConfig saved = repository.save(entity);
        publishChange("action_create");
        audit(callerOrgId, callerUsername, "COMPOSIO_ACTION_CREATE", saved.getId(),
                "POST", "/api/admin/composio/actions", 201);
        log.info("Created Composio action {} (tier={}, enabled={}) by {}",
                saved.getActionName(), saved.getTier(), saved.isEnabled(), callerUsername);
        return ComposioActionConfigResponse.from(saved);
    }

    @Transactional
    public ComposioActionConfigResponse updateAction(String id,
                                                     ComposioActionConfigUpdateRequest request,
                                                     String callerOrgId,
                                                     String callerUsername) {
        Objects.requireNonNull(request.version(), "version must not be null on update");
        ComposioActionConfig existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ComposioActionConfig", id));

        if (!Objects.equals(existing.getVersion(), request.version())) {
            // Pre-check produces a clean 409 message; the JPA-level @Version check is the
            // safety net (would throw ObjectOptimisticLockingFailureException on save).
            throw new StaleDataException("ComposioActionConfig", id);
        }

        existing.setTier(request.tier());
        existing.setEnabled(request.enabled());
        existing.setUpdatedBy(callerUsername);

        // saveAndFlush so the @Version increment is visible on the returned entity —
        // the response carries the bumped version, which the UI echoes back on the next
        // PUT for optimistic-lock checks. Plain save() would defer the flush to commit
        // and leave the response with the stale pre-update version.
        ComposioActionConfig saved = repository.saveAndFlush(existing);
        publishChange("action_update");
        audit(callerOrgId, callerUsername, "COMPOSIO_ACTION_UPDATE", saved.getId(),
                "PUT", "/api/admin/composio/actions/" + saved.getId(), 200);
        log.info("Updated Composio action {} (tier={}, enabled={}) by {}",
                saved.getActionName(), saved.getTier(), saved.isEnabled(), callerUsername);
        return ComposioActionConfigResponse.from(saved);
    }

    @Transactional
    public void deleteAction(String id, String callerOrgId, String callerUsername) {
        ComposioActionConfig existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ComposioActionConfig", id));
        repository.delete(existing);
        publishChange("action_delete");
        audit(callerOrgId, callerUsername, "COMPOSIO_ACTION_DELETE", existing.getId(),
                "DELETE", "/api/admin/composio/actions/" + existing.getId(), 204);
        log.info("Deleted Composio action {} by {}", existing.getActionName(), callerUsername);
    }

    /**
     * Gap #21 — Composio catalog bulk import. Idempotent: actions already in the
     * DB are skipped (re-running is safe). Pass {@code overwriteExisting=true} to
     * flip existing-but-disabled rows back to {@code enabled=true} — useful when
     * an operator wants the upstream catalog to be the source of truth.
     *
     * <p>Single audit row + single {@link ComposioConfigChangedEvent} per import
     * (not per-row); the registry hot-reloads once at the end. Per-action outcomes
     * land in the returned {@code created} / {@code skipped} / {@code failures}
     * lists for operator visibility.
     *
     * @param actionNames raw names from the upstream catalog; normalized to UPPERCASE
     * @param defaultTier tier applied to newly-created rows; null/blank → "STANDARD"
     */
    @Transactional
    public BulkImportResult bulkImportActions(List<String> actionNames,
                                              boolean overwriteExisting,
                                              Integer defaultTier,
                                              String callerOrgId,
                                              String callerUsername) {
        Objects.requireNonNull(actionNames, "actionNames must not be null");
        // Default to tier 2 (HITL-gated) — safe-by-default for bulk imports of an
        // unknown upstream catalog. Operators can retune per-row after import.
        int tier = (defaultTier == null) ? 2 : defaultTier;
        List<String> created = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        List<java.util.Map.Entry<String, String>> failures = new java.util.ArrayList<>();

        for (String raw : actionNames) {
            if (raw == null) continue;
            String actionName = raw.trim().toUpperCase();
            if (actionName.isEmpty()) continue;
            String id = actionName.toLowerCase();
            try {
                Optional<ComposioActionConfig> existing = repository.findById(id);
                if (existing.isPresent()) {
                    if (overwriteExisting && !existing.get().isEnabled()) {
                        existing.get().setEnabled(true);
                        existing.get().setUpdatedBy(callerUsername);
                        repository.saveAndFlush(existing.get());
                        created.add(actionName);
                    } else {
                        skipped.add(actionName);
                    }
                    continue;
                }
                ComposioActionConfig entity = new ComposioActionConfig();
                entity.setId(id);
                entity.setActionName(actionName);
                entity.setLlmToolName("composio_" + id);
                entity.setTier(tier);
                entity.setEnabled(true);
                entity.setCreatedBy(callerUsername);
                repository.save(entity);
                created.add(actionName);
            } catch (RuntimeException ex) {
                failures.add(java.util.Map.entry(actionName, ex.getMessage()));
            }
        }

        if (!created.isEmpty()) {
            publishChange("action_bulk_import");
        }
        audit(callerOrgId, callerUsername, "COMPOSIO_ACTION_BULK_IMPORT",
                "size=" + actionNames.size() + ",created=" + created.size() + ",skipped=" + skipped.size(),
                "POST", "/api/admin/composio/catalog/import", 200);
        log.info("Composio bulk import by {}: fetched={}, created={}, skipped={}, failures={}",
                callerUsername, actionNames.size(), created.size(), skipped.size(), failures.size());
        return new BulkImportResult(created, skipped, failures);
    }

    /** Per-import-call outcome envelope for {@link #bulkImportActions}. */
    public record BulkImportResult(
            List<String> created,
            List<String> skipped,
            List<java.util.Map.Entry<String, String>> failures
    ) {}

    /**
     * Returns the caller's org's connection row, or 404 if no row has been provisioned yet.
     * Caller-org scoping is applied here (not in the controller) so the service is the only
     * fixpoint that decides "what does 'my org's connection' mean."
     */
    @Transactional(readOnly = true)
    public ComposioConnectionConfigResponse getConnectionForOrg(String callerOrgId) {
        return connectionRepository.findByOrgId(callerOrgId)
                .map(ComposioConnectionConfigResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ComposioConnectionConfig", "org=" + callerOrgId));
    }

    /**
     * Idempotent upsert of the caller's org's connection row. First call (no existing row)
     * creates; subsequent calls update — both share the same endpoint per spec §Wire shape.
     * On update, {@code request.version()} must equal the existing row's {@code @Version}
     * or the call rejects with 409 via {@link StaleDataException}.
     *
     * <p>Connection mutations do <strong>not</strong> publish {@link ComposioConfigChangedEvent}:
     * {@code ComposioToolCallback} reads the connection per-tool-call from this same table,
     * so there's no in-memory cache to refresh.
     */
    @Transactional
    public ComposioConnectionConfigResponse upsertConnectionForOrg(
            ComposioConnectionConfigUpsertRequest request,
            String callerOrgId,
            String callerUsername) {
        Objects.requireNonNull(callerOrgId, "callerOrgId must not be null");
        String connectionId = request.connectionId().trim();
        if (connectionId.isEmpty()) {
            throw new BusinessValidationException("connectionId must not be blank");
        }

        Optional<ComposioConnectionConfig> existing = connectionRepository.findByOrgId(callerOrgId);
        boolean isCreate = existing.isEmpty();
        ComposioConnectionConfig entity;
        String auditAction;

        if (isCreate) {
            // version on the request is ignored on create — first write seeds @Version=0.
            entity = new ComposioConnectionConfig();
            entity.setId(callerOrgId);     // stable id == org id; one row per org by UNIQUE
            entity.setOrgId(callerOrgId);
            entity.setConnectionId(connectionId);
            auditAction = "COMPOSIO_CONNECTION_CREATE";
        } else {
            entity = existing.get();
            if (request.version() == null
                    || !Objects.equals(entity.getVersion(), request.version())) {
                throw new StaleDataException("ComposioConnectionConfig", "org=" + callerOrgId);
            }
            entity.setConnectionId(connectionId);
            auditAction = "COMPOSIO_CONNECTION_UPDATE";
        }

        // saveAndFlush so the response carries the bumped @Version (UI echoes it on the
        // next PUT for optimistic-lock checks). See ComposioActionConfig update path for
        // the same pattern + rationale.
        ComposioConnectionConfig saved = connectionRepository.saveAndFlush(entity);
        auditConnection(callerOrgId, callerUsername, auditAction, saved.getId(),
                "PUT", CONNECTION_BASE_PATH, 200);
        log.info("{} Composio connection for org {} by {}",
                isCreate ? "Created" : "Updated", callerOrgId, callerUsername);
        return ComposioConnectionConfigResponse.from(saved);
    }

    @Transactional
    public void deleteConnectionForOrg(String callerOrgId, String callerUsername) {
        Objects.requireNonNull(callerOrgId, "callerOrgId must not be null");
        ComposioConnectionConfig existing = connectionRepository.findByOrgId(callerOrgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ComposioConnectionConfig", "org=" + callerOrgId));
        connectionRepository.delete(existing);
        auditConnection(callerOrgId, callerUsername, "COMPOSIO_CONNECTION_DELETE",
                existing.getId(), "DELETE", CONNECTION_BASE_PATH, 204);
        log.info("Deleted Composio connection for org {} by {}", callerOrgId, callerUsername);
    }

    private void publishChange(String reason) {
        eventPublisher.publishEvent(new ComposioConfigChangedEvent(reason));
    }

    private void audit(String orgId, String username, String action, String resourceId,
                       String method, String path, int responseStatus) {
        // orgId may be the caller's home-org even though composio_action_config is global —
        // the audit row reflects "operator from org X performed a global mutation Y".
        systemAuditService.record(orgId, username, action, AUDIT_RESOURCE_TYPE,
                resourceId, method, path, responseStatus);
    }

    private void auditConnection(String orgId, String username, String action, String resourceId,
                                 String method, String path, int responseStatus) {
        systemAuditService.record(orgId, username, action, CONNECTION_AUDIT_RESOURCE_TYPE,
                resourceId, method, path, responseStatus);
    }
}
