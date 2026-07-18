package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.dto.RoutingDecisionResponse;
import com.operativus.agentmanager.control.repository.RoutingDecisionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.TenantConstants;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Read-only admin surface over {@code routing_decisions}. Admins
 *     filter by strategy to debug "why did the universal dispatcher pick agent X?" within
 *     their own org. Append-only data — there are no mutation verbs. Class-level
 *     {@code hasRole('ADMIN')} per the sibling-controller pattern.
 *
 *     <p>ADMIN is a per-org tenant role (see {@code RoleHierarchyConfig}: SUPER_ADMIN &gt;
 *     ADMIN), so every read is scoped to the caller's org — both the list and the
 *     get-by-id path. A cross-org id returns 404, never 200, matching the existence-leak
 *     contract enforced by the {@code *TenantIsolationRuntimeTest} suite. Cross-org routing
 *     observability, if ever needed, belongs in a separate SUPER_ADMIN-gated endpoint, not
 *     a runtime {@code orgId} parameter on this ADMIN surface.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/admin/routing-decisions")
@PreAuthorize("hasRole('ADMIN')")
public class RoutingDecisionAdminController {

    private final RoutingDecisionRepository repository;

    public RoutingDecisionAdminController(RoutingDecisionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Page<RoutingDecisionResponse>> list(
            @RequestParam(required = false) RoutingDecisionEntity.StrategyUsed strategy,
            Pageable pageable) {
        String org = callerOrgId();
        Page<RoutingDecisionEntity> rows = strategy == null
                ? repository.findAllByOrgIdOrderByCreatedAtDesc(org, pageable)
                : repository.findAllByOrgIdAndStrategyUsedOrderByCreatedAtDesc(org, strategy, pageable);
        return ResponseEntity.ok(rows.map(RoutingDecisionResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoutingDecisionResponse> get(@PathVariable String id) {
        return repository.findByIdAndOrgId(id, callerOrgId())
                .map(RoutingDecisionResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("RoutingDecision", id));
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }
}
