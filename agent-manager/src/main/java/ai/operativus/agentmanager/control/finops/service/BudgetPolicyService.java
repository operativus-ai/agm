package ai.operativus.agentmanager.control.finops.service;

import ai.operativus.agentmanager.control.repository.BudgetPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Domain Responsibility: Resolves the active budget ceiling for a given org/agent pair from
 *     the {@code budget_policies} table. Called by {@code AgentService.run()} just before the
 *     ScopedValue chain so the ceiling can be bound into
 *     {@code control.security.AgentContextHolder.CONTEXT}, making it visible to
 *     {@code GenAiMetricsAdvisor.resolveBudgetCeiling()} during inference.
 *
 * <p>Precedence: agent-scoped policy wins over org-wide; returns empty when no active policy
 *     exists (no enforcement applied on that run).
 * State: Stateless; holds only a repository reference.
 */
@Service
public class BudgetPolicyService {

    private static final Logger log = LoggerFactory.getLogger(BudgetPolicyService.class);

    private final BudgetPolicyRepository budgetPolicyRepository;

    public BudgetPolicyService(BudgetPolicyRepository budgetPolicyRepository) {
        this.budgetPolicyRepository = budgetPolicyRepository;
    }

    /**
     * Returns the active budget ceiling (USD) for the given org/agent. Returns empty when
     * no active policy exists — callers treat empty as "no enforcement for this run".
     */
    @Transactional(readOnly = true)
    public Optional<Double> findActiveCeiling(String orgId, String agentId) {
        if (orgId == null) return Optional.empty();
        try {
            return budgetPolicyRepository
                    .findActivePolicyForAgentOrOrg(orgId, agentId)
                    .map(p -> p.getCeilingUsd().doubleValue());
        } catch (Exception e) {
            log.warn("budget.policy.resolve.failed orgId={} agentId={}", orgId, agentId, e);
            return Optional.empty();
        }
    }
}
