package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.dto.composio.ConfigDriftResponse;
import ai.operativus.agentmanager.control.service.ComposioConfigDriftService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Read-only SUPER_ADMIN surface that exposes a point-in-time
 *   config-drift snapshot — live registry vs DB action configs vs per-org connection
 *   coverage. Consumed by the frontend Config Audit (B4) panel.
 * State: Stateless — delegates entirely to {@link ComposioConfigDriftService}.
 */
@RestController
@RequestMapping("/api/admin/composio/config-drift")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ComposioConfigDriftController {

    private final ComposioConfigDriftService driftService;

    public ComposioConfigDriftController(ComposioConfigDriftService driftService) {
        this.driftService = driftService;
    }

    @GetMapping
    public ResponseEntity<ConfigDriftResponse> getConfigDrift() {
        return ResponseEntity.ok(driftService.buildDriftSnapshot());
    }
}
