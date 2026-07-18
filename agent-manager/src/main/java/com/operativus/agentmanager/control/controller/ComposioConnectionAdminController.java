package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.security.CallerContext;
import com.operativus.agentmanager.control.dto.composio.ComposioConnectionConfigResponse;
import com.operativus.agentmanager.control.dto.composio.ComposioConnectionConfigUpsertRequest;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.control.service.ComposioConfigService;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Admin REST surface for the per-org {@code composio_connection_config}
 *   row. Connection config is per-tenant (each org provisions its own bundled-OAuth
 *   {@code connectionId} via the Composio dashboard), so this controller is ROLE_ADMIN-gated
 *   and scopes every operation to the caller's own org. The action catalog lives in a sibling
 *   controller ({@link ComposioAdminController}) and is SUPER_ADMIN-gated because it is global.
 *
 * <p><strong>DB &gt; properties precedence (callback side):</strong> when {@code ComposioToolCallback}
 *   resolves a connection at tool-call time, it queries this table first via
 *   {@code ComposioConnectionConfigRepository.findByOrgId} and only falls back to the
 *   {@code agent.tools.composio.connection-ids.<orgId>} property when the DB has no row. So
 *   creating the first row here flips that org from properties-driven to DB-driven on the
 *   next tool call. There's no in-memory cache to invalidate — the callback re-reads each
 *   time — which is why connection mutations (unlike action mutations) do NOT publish
 *   {@code ComposioConfigChangedEvent}.
 *
 * <p><strong>Tenant safety:</strong> the request DTO has no {@code orgId} field. The
 *   service stamps the caller's resolved {@code orgId} server-side, so an authenticated admin
 *   in org A cannot upsert into org B by hand-rolling a payload.
 *
 * State: Stateless.
 */
@RestController
@RequestMapping("/api/admin/composio/connection")
@PreAuthorize("hasRole('ADMIN')")
public class ComposioConnectionAdminController {

    private static final Logger log = LoggerFactory.getLogger(ComposioConnectionAdminController.class);

    private final ComposioConfigService service;

    public ComposioConnectionAdminController(ComposioConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ComposioConnectionConfigResponse> getConnection() {
        return ResponseEntity.ok(service.getConnectionForOrg(requireCallerOrgId()));
    }

    @PutMapping
    public ResponseEntity<ComposioConnectionConfigResponse> upsertConnection(
            @Valid @RequestBody ComposioConnectionConfigUpsertRequest request) {
        ComposioConnectionConfigResponse saved = service.upsertConnectionForOrg(
                request, requireCallerOrgId(), callerUsername());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteConnection() {
        service.deleteConnectionForOrg(requireCallerOrgId(), callerUsername());
        return ResponseEntity.noContent().build();
    }

    private String requireCallerOrgId() {
        String orgId = CallerContext.resolveCallerOrgId();
        if (orgId == null) {
            // Never expected past the @PreAuthorize gate, but treat as 400 rather than NPE
            // to make the failure surface explicit if a principal lacks an org claim.
            throw new BusinessValidationException(
                    "Authenticated principal is missing org_id; cannot resolve target tenant");
        }
        return orgId;
    }

    private String callerUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getUsername();
        }
        return "system";
    }
}
