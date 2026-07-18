package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.service.IncidentResponseService;
import com.operativus.agentmanager.core.model.HaltAllRunsResponse;
import com.operativus.agentmanager.core.model.QuarantineRequest;
import com.operativus.agentmanager.core.model.QuarantineResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: C01 — atomic incident-response HTTP surface. Three endpoints:
 *   <ul>
 *     <li>{@code POST /api/v1/admin/agents/{id}/quarantine} — ADMIN; atomic agent shutdown.</li>
 *     <li>{@code POST /api/v1/admin/agents/{id}/unquarantine} — ADMIN; reverses the above.</li>
 *     <li>{@code POST /api/v1/admin/incident/halt-all-runs} — SUPER_ADMIN only; cross-tenant
 *         global cancellation of every RUNNING agent run.</li>
 *   </ul>
 *   The controller forwards the {@link Authentication}-resolved username to the service as
 *   the {@code actor} for audit-row attribution.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class IncidentResponseController {

    private final IncidentResponseService incidentResponseService;

    public IncidentResponseController(IncidentResponseService incidentResponseService) {
        this.incidentResponseService = incidentResponseService;
    }

    @PostMapping("/agents/{agentId}/quarantine")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuarantineResponse> quarantine(
            @PathVariable("agentId") String agentId,
            @Valid @RequestBody QuarantineRequest request,
            Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        QuarantineResponse response = incidentResponseService.quarantineAgent(agentId, request.reason(), actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/agents/{agentId}/unquarantine")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuarantineResponse> unquarantine(
            @PathVariable("agentId") String agentId,
            @Valid @RequestBody QuarantineRequest request,
            Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        QuarantineResponse response = incidentResponseService.unquarantineAgent(agentId, request.reason(), actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/incident/halt-all-runs")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<HaltAllRunsResponse> haltAllRuns(
            @Valid @RequestBody QuarantineRequest request,
            Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : "unknown";
        HaltAllRunsResponse response = incidentResponseService.haltAllRuns(request.reason(), actor);
        return ResponseEntity.ok(response);
    }
}
