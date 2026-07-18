package com.operativus.agentmanager.compute.api;

import com.operativus.agentmanager.compute.security.PiiAuditLogDTO;
import com.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import com.operativus.agentmanager.compute.security.PiiPolicyDTO;
import com.operativus.agentmanager.compute.security.PiiPolicyService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: REST Administration controller for managing a tenant's PII policy
 * dictionary and per-agent policy bindings. Provides standard CRUD operations for the
 * Security settings panel.
 *
 * <p><b>Authz:</b> All endpoints require {@code ROLE_ADMIN}. Tenant scoping is layered on
 * top: every call resolves the caller's orgId via
 * {@link AgentContextHolder#getOrgId()} and the {@code PiiPolicyService} filters all
 * reads/writes by that orgId. A foreign-org admin cannot observe or mutate another
 * tenant's policies — cross-tenant access returns 404 (existence-leak protection,
 * matching the convention used by {@code ComplianceController.requireSameOrgOrSelf}).
 *
 * <p>The pre-this-architecture state (PR #968) gated by role only — the policy table was
 * global, so any admin could read/poison/delete any tenant's policies. Changeset 101
 * narrowed the dictionary to per-tenant; this controller's class-level annotation
 * remains as the role gate.
 *
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/pii-policies")
@PreAuthorize("hasRole('ADMIN')")
public class PiiAdminController {

    private static final Logger log = LoggerFactory.getLogger(PiiAdminController.class);

    private final PiiPolicyService policyService;
    private final PiiAuditLogRepository auditLogRepository;

    public PiiAdminController(PiiPolicyService policyService, PiiAuditLogRepository auditLogRepository) {
        this.policyService = policyService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * @summary Lists all PII policies belonging to the caller's tenant.
     * @logic Returns the tenant's dictionary for the administration UI; other tenants'
     *        policies are not visible.
     */
    @GetMapping
    public ResponseEntity<List<PiiPolicyDTO>> listAllPolicies() {
        String orgId = AgentContextHolder.getOrgId();
        log.debug("API Request: List PII policies for org '{}'", orgId);
        return ResponseEntity.ok(policyService.findAllForOrg(orgId));
    }

    /**
     * @summary Creates a new PII policy in the caller's tenant dictionary.
     * @logic Validates the regex pattern and persists the policy stamped with the caller's
     *        orgId. Per-tenant name uniqueness is enforced by the {@code (org_id, name)}
     *        composite unique constraint (changeset 101).
     */
    @PostMapping
    public ResponseEntity<PiiPolicyDTO> createPolicy(@RequestBody PiiPolicyDTO dto) {
        String orgId = AgentContextHolder.getOrgId();
        log.info("API Request: Create PII policy '{}' in org '{}'", dto.name(), orgId);
        PiiPolicyDTO created = policyService.createPolicy(dto, orgId);
        return ResponseEntity.status(201).body(created);
    }

    /**
     * @summary Deletes a PII policy from the caller's tenant dictionary.
     * @logic Cross-tenant ids return 404 (existence-leak protection). Cascading deletes
     *        in the DB handle agent bindings automatically.
     */
    @DeleteMapping("/{policyId}")
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID policyId) {
        String orgId = AgentContextHolder.getOrgId();
        log.warn("API Request: Delete PII policy id={} (org '{}')", policyId, orgId);
        policyService.deletePolicy(policyId, orgId);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Tenant-scoped PII-scrub audit log for the Security admin viewer.
     * @logic Resolves the caller's orgId and returns ONLY that tenant's entries (newest first),
     *        optionally narrowed to one agent. The {@code org_id} filter is enforced in the repo
     *        query — a foreign {@code agentId} yields zero rows, so there is no cross-tenant
     *        existence leak. A null-orgId principal (stale JWT) fails closed to an empty list.
     */
    @GetMapping("/audit-log")
    public ResponseEntity<List<PiiAuditLogDTO>> getAuditLog(@RequestParam(required = false) String agentId) {
        String orgId = AgentContextHolder.getOrgId();
        log.debug("API Request: PII audit log for org '{}' (agentId filter='{}')", orgId, agentId);
        List<PiiAuditLogDTO> entries = ((agentId == null || agentId.isBlank())
                ? auditLogRepository.findByOrgIdOrderByCreatedAtDesc(orgId)
                : auditLogRepository.findByOrgIdAndAgentIdOrderByCreatedAtDesc(orgId, agentId))
                .stream().map(PiiAuditLogDTO::from).toList();
        return ResponseEntity.ok(entries);
    }

    // --- Agent-Policy Binding Endpoints ---

    /**
     * @summary Lists the policy IDs currently bound to a specific agent, scoped to the
     *          caller's tenant.
     */
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<List<UUID>> getAgentBindings(@PathVariable String agentId) {
        String orgId = AgentContextHolder.getOrgId();
        log.debug("API Request: Get PII policy bindings for agent '{}' (org '{}')", agentId, orgId);
        return ResponseEntity.ok(policyService.findBoundPolicyIds(agentId, orgId));
    }

    /**
     * @summary Binds a PII policy to a specific agent within the caller's tenant.
     */
    @PostMapping("/agents/{agentId}/bind/{policyId}")
    public ResponseEntity<Void> bindPolicy(@PathVariable String agentId, @PathVariable UUID policyId) {
        String orgId = AgentContextHolder.getOrgId();
        log.info("API Request: Bind PII policy {} to agent '{}' (org '{}')", policyId, agentId, orgId);
        policyService.bindPolicyToAgent(agentId, policyId, orgId);
        return ResponseEntity.ok().build();
    }

    /**
     * @summary Removes a PII policy binding from a specific agent within the caller's tenant.
     */
    @DeleteMapping("/agents/{agentId}/unbind/{policyId}")
    public ResponseEntity<Void> unbindPolicy(@PathVariable String agentId, @PathVariable UUID policyId) {
        String orgId = AgentContextHolder.getOrgId();
        log.info("API Request: Unbind PII policy {} from agent '{}' (org '{}')", policyId, agentId, orgId);
        policyService.unbindPolicyFromAgent(agentId, policyId, orgId);
        return ResponseEntity.noContent().build();
    }

}
