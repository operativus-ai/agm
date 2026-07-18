package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.RunOptions;

import java.util.List;

/**
 * Domain Responsibility: Cross-module SPI seam for resolving a team's effective member
 *     roster at run time. Implementations dispatch on the team's
 *     {@code member_resolver_type} (STATIC / ORG_TIER / FEATURE_FLAG / future) and
 *     return the agent ids that should participate in this single run.
 *
 *     <p>Contract:
 *     <ul>
 *       <li>Order matters — orchestrators may iterate members in the returned order
 *           (e.g., Sequential).</li>
 *       <li>Result MUST be deterministic for the same {@code (teamAgentId, orgId,
 *           userId, options)} tuple — caching layers above the SPI memoize per-run.</li>
 *       <li>Returns the empty list (NOT null) when no members are configured or all
 *           members were filtered out by the strategy.</li>
 *       <li>Implementations must NOT side-effect (no audit emission here — that lives
 *           on the orchestrator after the roster is resolved).</li>
 *     </ul>
 * State: Stateless contract.
 */
public interface MemberResolver {

    /**
     * Resolves the member roster for a team agent at run time.
     *
     * @param teamAgentId   the id of the team agent whose members are being resolved
     * @param orgId         caller's organization id (never null/blank)
     * @param userId        authenticated principal id (may be null for service callers)
     * @param options       per-request options (may be null)
     * @return ordered list of member agent ids; empty when no members resolve, never null
     */
    List<String> resolveMembers(String teamAgentId, String orgId, String userId, RunOptions options);
}
