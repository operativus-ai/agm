package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes agent execution requests between production and canary agent variants
 * based on configured traffic split percentages.
 *
 * When an agent has a canary variant configured (canaryBaseAgentId + canaryPercentage),
 * this service probabilistically routes a percentage of traffic to the canary.
 */
@Service
public class CanaryRoutingService {

    private static final Logger log = LoggerFactory.getLogger(CanaryRoutingService.class);

    private final AgentRepository agentRepository;

    public CanaryRoutingService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * Resolves the effective agent ID for execution.
     * If the requested agent has active canary variants, probabilistically routes to one.
     *
     * @param requestedAgentId The agent ID from the user's request
     * @return The effective agent ID to execute (either the original or a canary)
     */
    public String resolveEffectiveAgent(String requestedAgentId) {
        // Find active canary variants pointing to this agent as their base
        List<AgentEntity> canaries = agentRepository.findByActiveTrue().stream()
                .filter(a -> requestedAgentId.equals(a.getCanaryBaseAgentId()))
                .filter(a -> a.getCanaryPercentage() != null && a.getCanaryPercentage() > 0)
                .toList();

        if (canaries.isEmpty()) {
            return requestedAgentId;
        }

        // Calculate total canary traffic percentage
        int totalCanaryPercentage = canaries.stream()
                .mapToInt(AgentEntity::getCanaryPercentage)
                .sum();
        totalCanaryPercentage = Math.min(totalCanaryPercentage, 100);

        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < totalCanaryPercentage) {
            // Route to a canary — distribute proportionally among canaries
            int cumulative = 0;
            for (AgentEntity canary : canaries) {
                cumulative += canary.getCanaryPercentage();
                if (roll < cumulative) {
                    log.info("Canary routing: {} -> {} ({}% traffic, roll={})", requestedAgentId, canary.getId(), canary.getCanaryPercentage(), roll);
                    return canary.getId();
                }
            }
        }

        return requestedAgentId;
    }

    /**
     * Returns canary deployment status for a given base agent.
     */
    public java.util.Map<String, Object> getCanaryStatus(String baseAgentId) {
        List<AgentEntity> canaries = agentRepository.findByActiveTrue().stream()
                .filter(a -> baseAgentId.equals(a.getCanaryBaseAgentId()))
                .toList();

        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("baseAgentId", baseAgentId);
        status.put("canaryCount", canaries.size());
        // LinkedHashMap (not Map.of) for the inner per-canary entries because Map.of rejects
        // null values: AgentEntity.name is @Column(nullable=false) so persisted canaries
        // always have a name, but transient / partially-fetched entities could trip Map.of
        // with NullPointerException, breaking the entire status endpoint for a single bad row.
        status.put("canaries", canaries.stream().map(c -> {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", c.getId());
            entry.put("name", c.getName() != null ? c.getName() : c.getId());
            entry.put("percentage", c.getCanaryPercentage() != null ? c.getCanaryPercentage() : 0);
            return entry;
        }).toList());

        int totalPercentage = canaries.stream().mapToInt(c -> c.getCanaryPercentage() != null ? c.getCanaryPercentage() : 0).sum();
        status.put("totalCanaryPercentage", totalPercentage);
        status.put("productionPercentage", 100 - Math.min(totalPercentage, 100));

        return status;
    }
}
