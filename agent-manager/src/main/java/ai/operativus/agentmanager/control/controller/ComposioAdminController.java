package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigCreateRequest;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigResponse;
import ai.operativus.agentmanager.control.dto.composio.ComposioActionConfigUpdateRequest;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.control.service.ComposioConfigService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Domain Responsibility: Admin REST surface for managing the DB-backed
 *   Composio action catalog. Action config is system-wide (one set of enabled
 *   actions across all tenants), so endpoints are SUPER_ADMIN-gated. Connection
 *   config (per-org Composio connection IDs) lands in a separate controller in PR-C.
 *
 * <p><strong>DB > properties precedence:</strong> as soon as one row exists in
 *   {@code composio_action_config}, {@code ComposioActionRegistry} switches from
 *   the property-bound fallback to the DB list (no merge). Each mutation here
 *   publishes {@link ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent}
 *   and the registry hot-reloads.
 *
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/admin/composio/actions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ComposioAdminController {

    private static final Logger log = LoggerFactory.getLogger(ComposioAdminController.class);

    private final ComposioConfigService service;

    public ComposioAdminController(ComposioConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ComposioActionConfigResponse>> listActions() {
        return ResponseEntity.ok(service.listActions());
    }

    @PostMapping
    public ResponseEntity<ComposioActionConfigResponse> createAction(
            @Valid @RequestBody ComposioActionConfigCreateRequest request) {
        ComposioActionConfigResponse created = service.createAction(
                request, callerOrgId(), callerUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ComposioActionConfigResponse> updateAction(
            @PathVariable String id,
            @Valid @RequestBody ComposioActionConfigUpdateRequest request) {
        ComposioActionConfigResponse updated = service.updateAction(
                id, request, callerOrgId(), callerUsername());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAction(@PathVariable String id) {
        service.deleteAction(id, callerOrgId(), callerUsername());
        return ResponseEntity.noContent().build();
    }

    private String callerOrgId() {
        // Reuse the existing helper to keep one fix-point for org resolution.
        return CallerContext.resolveCallerOrgId();
    }

    private String callerUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getUsername();
        }
        return "system";
    }
}
