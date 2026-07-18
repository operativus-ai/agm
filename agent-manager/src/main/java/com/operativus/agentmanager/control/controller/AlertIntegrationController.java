package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.AlertIntegrationRequest;
import com.operativus.agentmanager.control.service.AlertIntegrationService;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.model.AlertIntegrationTestResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Domain Responsibility: REST API boundary for managing outbound alert integration configs.
 *
 * <p>RBAC: {@code GET /api/alerts/integrations} (list) is open to any authenticated tenant
 * member — the service filters by caller's {@code orgId} so reads are tenant-scoped.
 * Write paths (POST / PUT / DELETE) and the test-fire endpoint are gated to
 * {@code ROLE_ADMIN}, matching the sibling {@link AlertingController} pattern and the
 * SchedulesController / ApprovalsController convention. Without these gates, any
 * authenticated user could plant an attacker-owned webhook URL on their tenant and
 * exfiltrate every subsequent alert (the SsrfGuard on {@code endpointUrl} only blocks
 * RFC-1918 / cloud-metadata targets — a public attacker URL passes).
 *
 * State: Stateless
 */
@RestController
@RequestMapping("/api/alerts/integrations")
public class AlertIntegrationController {

    private final AlertIntegrationService alertIntegrationService;

    public AlertIntegrationController(AlertIntegrationService alertIntegrationService) {
        this.alertIntegrationService = alertIntegrationService;
    }

    @GetMapping
    public ResponseEntity<List<AlertIntegration>> list() {
        return ResponseEntity.ok(alertIntegrationService.listIntegrations());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlertIntegration> create(@Valid @RequestBody AlertIntegrationRequest request) {
        // Build the entity from the DTO. id is left null so AlertIntegrationService.createIntegration
        // generates a fresh UUID — closes the mass-assignment vector that previously let a
        // caller hijack another tenant's integration row by supplying its id. Retry-state
        // fields (retryCount, lastFailureAt, lastError, nextRetryAt, pendingPayload,
        // pendingEventId) are NOT on the DTO — protects the webhook re-dispatch pipeline
        // from arbitrary-payload injection. orgId is server-derived inside the service.
        AlertIntegration entity = applyRequest(new AlertIntegration(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(alertIntegrationService.createIntegration(entity));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlertIntegration> update(@PathVariable String id, @Valid @RequestBody AlertIntegrationRequest request) {
        // AlertIntegrationService.updateIntegration loads-by-id-and-orgId then copies the
        // safe-field subset onto the loaded row, so id/orgId/createdAt/retry-state are
        // already protected at the service layer. Passing the DTO-derived entity (with
        // id=null, retry-state=defaults) makes the contract explicit at the controller
        // boundary too.
        AlertIntegration entity = applyRequest(new AlertIntegration(), request);
        return ResponseEntity.ok(alertIntegrationService.updateIntegration(id, entity));
    }

    // Maps DTO → entity. Centralized so create + update stay in sync. Any new safe field
    // on the DTO is reflected here once. Server-managed fields on AlertIntegration
    // (id, orgId, createdAt, retryCount, lastFailureAt, lastError, nextRetryAt,
    // pendingPayload, pendingEventId) are intentionally NOT set — they remain at their
    // entity-default values (null / 0 / false) on the in-memory object the controller
    // hands to the service.
    private static AlertIntegration applyRequest(AlertIntegration target, AlertIntegrationRequest req) {
        target.setName(req.name());
        target.setType(req.type());
        target.setEndpointUrl(req.endpointUrl());
        target.setEnabled(req.enabled());
        target.setSigningSecret(req.signingSecret());
        return target;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        alertIntegrationService.deleteIntegration(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary §4 P5 T040 — operator-fired test dispatch for a single integration.
     *     Posts a synthetic AlertFiredEvent payload to the integration's webhook and
     *     returns the outcome inline so the UI can render success/error without waiting
     *     for the next real alert.
     * @logic Always responds 200 OK (or 404 if the integration id is unknown);
     *     {@code delivered=false} carries the error in the body so the UI can format it.
     *     ROLE_ADMIN — test-fire spends an outbound HTTP request and could be abused for
     *     a small SSRF probe if exposed broadly.
     */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlertIntegrationTestResult> testFire(@PathVariable String id) {
        return ResponseEntity.ok(alertIntegrationService.testFire(id));
    }
}
