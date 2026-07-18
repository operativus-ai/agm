package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.BudgetPolicy;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Domain Responsibility: Data-access seam for {@link BudgetPolicy} rows.
 *     Provides the active-policy lookup used by {@code BudgetPolicyService} to resolve
 *     the per-run spend ceiling before the ScopedValue chain is bound in {@code AgentService}.
 * State: Stateless (Spring Data proxy).
 */
public interface BudgetPolicyRepository extends JpaRepository<BudgetPolicy, String> {

    /**
     * Returns the most restrictive active policy for the given agent: agent-scoped policy
     * first (most specific), then org-wide (agentId IS NULL). Returns empty if no active
     * policy exists for the org.
     *
     * Agent-scoped wins because it carries the tighter per-agent contract from the operator;
     * the {@link Limit} parameter + ORDER BY collapses both tiers into a single round-trip
     * without tripping {@code NonUniqueResultException} when both tiers exist for the same org.
     */
    default Optional<BudgetPolicy> findActivePolicyForAgentOrOrg(String orgId, String agentId) {
        return findActivePolicyForAgentOrOrg(orgId, agentId, Limit.of(1)).stream().findFirst();
    }

    @Query("""
            SELECT p FROM BudgetPolicy p
            WHERE p.orgId = :orgId
              AND (p.agentId = :agentId OR p.agentId IS NULL)
              AND p.active = true
            ORDER BY p.agentId NULLS LAST
            """)
    java.util.List<BudgetPolicy> findActivePolicyForAgentOrOrg(
            @Param("orgId") String orgId,
            @Param("agentId") String agentId,
            Limit limit);
}
