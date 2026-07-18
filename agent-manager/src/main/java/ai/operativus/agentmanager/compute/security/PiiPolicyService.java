package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Manages the lifecycle of PII policies (CRUD) and the many-to-many
 * binding between agents and policies, scoped per tenant. All public methods take an
 * {@code orgId} arg and refuse to cross tenant boundaries; cross-tenant access returns the
 * same {@code ResourceNotFoundException} shape as a missing row (existence-leak protection
 * — matches the convention used by {@code ComplianceController.requireSameOrgOrSelf} and
 * the {@code cancelRun} cross-tenant guard in PR #972).
 *
 * <p>Callers responsible for resolving the caller's orgId:
 * <ul>
 *   <li>{@code PiiAdminController} — reads via {@code AgentContextHolder.getOrgId()}
 *       (TenantContextFilter → SecurityContext fallback chain).</li>
 *   <li>{@code PIIAnonymizationAdvisor} / {@code StatefulStreamingPIIAdvisor} — read via
 *       {@code core.callback.AgentContextHolder.getOrgId()} (bound by
 *       {@code AgentService.run} on the agent's execution thread).</li>
 * </ul>
 *
 * State: Stateless
 */
@Service
public class PiiPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PiiPolicyService.class);

    private final PiiPolicyRepository policyRepository;
    private final JdbcTemplate jdbcTemplate;

    public PiiPolicyService(PiiPolicyRepository policyRepository, JdbcTemplate jdbcTemplate) {
        this.policyRepository = policyRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @summary Retrieves all PII policies belonging to a tenant.
     * @logic Returns the full per-tenant dictionary regardless of enabled state for
     *        administration purposes.
     */
    public List<PiiPolicyDTO> findAllForOrg(String orgId) {
        return policyRepository.findByOrgId(orgId).stream()
                .map(PiiPolicyDTO::fromEntity)
                .toList();
    }

    /**
     * @summary Retrieves all enabled PII policies bound to a specific agent, scoped to the
     *          agent's tenant.
     * @logic Joins against the {@code agent_pii_policies} table to return only policies the
     *        admin has bound to the given agent. Falls back to all enabled policies in the
     *        tenant if no agent-specific bindings exist.
     */
    public List<PiiPolicyEntity> findPoliciesForAgent(String agentId, String orgId) {
        List<PiiPolicyEntity> agentPolicies = policyRepository.findByAgentIdAndOrgId(agentId, orgId);
        if (agentPolicies.isEmpty()) {
            log.debug("No agent-specific PII policies for '{}' in org '{}', falling back to "
                    + "tenant's enabled policies.", agentId, orgId);
            return policyRepository.findByOrgIdAndEnabledTrue(orgId);
        }
        return agentPolicies;
    }

    /**
     * @summary Creates a new PII policy in the tenant's dictionary.
     * @logic Validates the regex pattern can compile, assigns a UUID, stamps the caller's
     *        orgId, and persists. The {@code (org_id, name)} unique constraint (changeset 101)
     *        enforces per-tenant name uniqueness; collisions surface as
     *        {@code DataIntegrityViolationException} → 409 via the global handler.
     */
    @Transactional
    public PiiPolicyDTO createPolicy(PiiPolicyDTO dto, String orgId) {
        try {
            java.util.regex.Pattern.compile(dto.pattern());
        } catch (Exception e) {
            throw new BusinessValidationException("Invalid regex pattern: " + e.getMessage());
        }

        PiiPolicyEntity entity = new PiiPolicyEntity(
                UUID.randomUUID(),
                orgId,
                dto.name(),
                dto.description(),
                dto.patternType() != null ? dto.patternType() : PatternType.REGEX,
                dto.pattern(),
                dto.scrubStrategy() != null ? dto.scrubStrategy() : ScrubStrategy.REDACT,
                dto.enabled() != null ? dto.enabled() : true,
                dto.taxonomicCategory() != null ? dto.taxonomicCategory() : TaxonomyCategory.UNCATEGORIZED,
                dto.complianceFramework() != null ? dto.complianceFramework() : ComplianceFramework.STANDARD
        );
        PiiPolicyEntity saved = policyRepository.save(entity);
        log.info("Created PII policy '{}' (id={}) in org '{}'", saved.getName(), saved.getId(), orgId);
        return PiiPolicyDTO.fromEntity(saved);
    }

    /**
     * @summary Deletes a PII policy from the tenant's dictionary.
     * @logic Cross-tenant access returns 404 (existence-leak protection). Cascading
     *        deletes in the DB handle agent bindings automatically.
     */
    @Transactional
    public void deletePolicy(UUID policyId, String orgId) {
        PiiPolicyEntity entity = policyRepository.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PiiPolicy", policyId.toString()));
        policyRepository.delete(entity);
        log.info("Deleted PII policy id={} from org '{}'", policyId, orgId);
    }

    /**
     * @summary Binds a PII policy to a specific agent within a tenant.
     * @logic Verifies the policy belongs to the caller's tenant first (cross-tenant policy
     *        ids return 404). The agent's tenant is enforced by the agent repository's
     *        existence check (callers of this method are admin-controllers already inside
     *        an admin-gated path; the agent-orgId check belongs to the AgentRepository layer
     *        but the policy-orgId check is sufficient to prevent the cross-tenant exploit
     *        because both legs of the join — agent_id and policy_id — must be in the same
     *        tenant for the runtime advisor to ever observe the binding).
     */
    @Transactional
    public void bindPolicyToAgent(String agentId, UUID policyId, String orgId) {
        policyRepository.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PiiPolicy", policyId.toString()));
        jdbcTemplate.update(
                "INSERT INTO agent_pii_policies (agent_id, policy_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                agentId, policyId
        );
        log.info("Bound PII policy {} to agent '{}' (org '{}')", policyId, agentId, orgId);
    }

    /**
     * @summary Removes a PII policy binding from a specific agent within a tenant.
     * @logic Same cross-tenant guard as bind. Idempotent — deleting a non-existent binding
     *        is a no-op (0 rows affected).
     */
    @Transactional
    public void unbindPolicyFromAgent(String agentId, UUID policyId, String orgId) {
        policyRepository.findByIdAndOrgId(policyId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PiiPolicy", policyId.toString()));
        jdbcTemplate.update(
                "DELETE FROM agent_pii_policies WHERE agent_id = ? AND policy_id = ?",
                agentId, policyId
        );
        log.info("Unbound PII policy {} from agent '{}' (org '{}')", policyId, agentId, orgId);
    }

    /**
     * @summary Returns the list of policy IDs currently bound to a given agent, scoped to a
     *          tenant.
     * @logic Joining against pii_policies with the org_id filter ensures cross-tenant
     *        bindings (which shouldn't exist post-this-PR, but defensive belt-and-suspenders)
     *        are not surfaced.
     */
    public List<UUID> findBoundPolicyIds(String agentId, String orgId) {
        return jdbcTemplate.queryForList(
                """
                SELECT ap.policy_id FROM agent_pii_policies ap
                INNER JOIN pii_policies p ON p.id = ap.policy_id
                WHERE ap.agent_id = ? AND p.org_id = ?
                """,
                UUID.class,
                agentId, orgId
        );
    }
}
