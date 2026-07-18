package com.operativus.agentmanager.compute.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: Data access for the tenant-scoped PII policy dictionary.
 * Provides lookups for a tenant's enabled policies and agent-specific policy bindings.
 * Every query is scoped by {@code orgId} — there is no cross-tenant lookup path.
 * State: Stateless (Spring Data Interface)
 */
@Repository
public interface PiiPolicyRepository extends JpaRepository<PiiPolicyEntity, UUID> {

    /**
     * @summary Lists all policies belonging to the given tenant.
     * @logic Admin listing path — returns enabled and disabled policies for the tenant's
     *        dictionary view.
     */
    List<PiiPolicyEntity> findByOrgId(String orgId);

    /**
     * @summary Lookup for tenant-scoped operations (delete, bind, unbind) that must reject
     *          cross-tenant access with the same 404 shape as missing rows.
     */
    Optional<PiiPolicyEntity> findByIdAndOrgId(UUID id, String orgId);

    /**
     * @summary Retrieves the enabled policies for a tenant — used as the runtime fallback
     *          when an agent has no explicit agent_pii_policies bindings.
     */
    List<PiiPolicyEntity> findByOrgIdAndEnabledTrue(String orgId);

    /**
     * @summary Retrieves the enabled policies explicitly bound to an agent, restricted to
     *          a tenant.
     * @logic Native join against {@code agent_pii_policies}. The {@code orgId} filter on
     *        {@code pii_policies} is sufficient because the join table inherits the agent's
     *        tenant (agents are tenant-scoped) and a policy can only be bound to an agent
     *        whose org_id matches the policy's — enforced at the service-layer bind path.
     */
    @Query(value = """
            SELECT p.* FROM pii_policies p
            INNER JOIN agent_pii_policies ap ON ap.policy_id = p.id
            WHERE ap.agent_id = :agentId
              AND p.org_id = :orgId
              AND p.enabled = true
            """, nativeQuery = true)
    List<PiiPolicyEntity> findByAgentIdAndOrgId(@Param("agentId") String agentId,
                                                @Param("orgId") String orgId);
}
