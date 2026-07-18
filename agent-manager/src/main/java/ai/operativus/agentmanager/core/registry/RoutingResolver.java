package ai.operativus.agentmanager.core.registry;

/**
 * Domain Responsibility: Cross-module SPI seam for the universal-dispatch subsystem
 *     ({@code POST /api/runs} with no path-{@code agentId}). Implementations resolve a
 *     target agent id from the caller's context and message. CRUD on the per-org
 *     {@code org_routing_config} row is intentionally NOT on this contract — that
 *     stays on the control-side service (mirrors the {@code SkillOperations} pattern
 *     established in REQ-DR-3).
 *
 *     <p>Resolution strategies compose in priority order inside the implementation:
 *     <ol>
 *       <li>{@code default_router_agent_id} — designated team configured per org</li>
 *       <li>{@code llm_classifier_enabled} — LLM picks from active agents in the org</li>
 *       <li>{@code rule_classifier_enabled} — tag / description match</li>
 *     </ol>
 *     If all strategies miss, the implementation returns {@code fallback_agent_id} when
 *     configured, otherwise {@code null} (caller surfaces as 4xx).
 * State: Stateless contract.
 */
public interface RoutingResolver {

    /**
     * Resolves the agent id that should handle a no-{@code agentId} run for the given
     * org and user message. Returns {@code null} when no strategy matches and no
     * fallback agent is configured.
     *
     * @param orgId   caller's organization id (never null/blank)
     * @param userId  authenticated principal id (may be null for service-to-service callers)
     * @param message the user-supplied message text — basis for LLM-classifier and
     *                rule-classifier matching; never null
     * @return target agent id, or {@code null} if no resolution succeeds
     */
    String resolveAgentId(String orgId, String userId, String message);
}
