package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.AlertRuleRequest;
import com.operativus.agentmanager.control.service.AlertingService;
import com.operativus.agentmanager.core.entity.AlertEvent;
import com.operativus.agentmanager.core.entity.AlertRule;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing alert rules and viewing fired alert events.
 *
 * <p>RBAC: tenant isolation is enforced at the {@link AlertingService} layer via
 * {@code AgentContextHolder.getOrgId()} on every method, so reads are safe for any
 * authenticated caller. Write operations (rule CRUD + alert ack) are gated to
 * {@code ROLE_ADMIN} — matching the pattern established for the rest of the
 * controller surface (Schedules, Approvals, Workflows, …).
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertingController {

    private final AlertingService alertingService;

    public AlertingController(AlertingService alertingService) {
        this.alertingService = alertingService;
    }

    // --- Alert Rules ---

    @GetMapping("/rules")
    public ResponseEntity<List<AlertRule>> getAllRules() {
        return ResponseEntity.ok(alertingService.getAllRules());
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<AlertRule> getRule(@PathVariable String id) {
        return ResponseEntity.ok(alertingService.getRule(id));
    }

    @PostMapping("/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlertRule> createRule(@Valid @RequestBody AlertRuleRequest request) {
        // Build the entity from the DTO. id is left null so AlertingService.createRule
        // generates a fresh UUID — closes the mass-assignment vector that previously
        // let a caller hijack another tenant's rule by supplying its id in the body.
        // orgId is server-derived inside the service.
        AlertRule rule = applyRequest(new AlertRule(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(alertingService.createRule(rule));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AlertRule> updateRule(@PathVariable String id, @Valid @RequestBody AlertRuleRequest request) {
        // AlertingService.updateRule loads-by-id-and-orgId then copies only the
        // safe-field subset onto the loaded row, so id/orgId/createdAt are
        // already protected at the service layer. Passing the DTO-derived entity
        // (with id=null) makes the contract explicit at the controller boundary too.
        return ResponseEntity.ok(alertingService.updateRule(id, applyRequest(new AlertRule(), request)));
    }

    // Maps DTO → entity. Centralized so create + update stay in sync — any new
    // safe field on the DTO needs to be reflected here once. Any new field on
    // AlertRule that should NOT be client-settable is simply omitted from the DTO.
    private static AlertRule applyRequest(AlertRule target, AlertRuleRequest req) {
        target.setName(req.name());
        target.setDescription(req.description());
        target.setMetricName(req.metricName());
        target.setCondition(req.condition());
        target.setThreshold(req.threshold());
        target.setWindowSeconds(req.windowSeconds());
        target.setSeverity(req.severity());
        target.setEnabled(req.enabled());
        target.setNotificationChannel(req.notificationChannel());
        return target;
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        alertingService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    // --- Alert Events ---

    @GetMapping("/events")
    public ResponseEntity<Page<AlertEvent>> getActiveAlerts(Pageable pageable) {
        return ResponseEntity.ok(alertingService.getActiveAlerts(pageable));
    }

    @PostMapping("/events/{id}/acknowledge")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> acknowledgeAlert(@PathVariable String id) {
        alertingService.acknowledgeAlert(id);
        return ResponseEntity.noContent().build();
    }
}
