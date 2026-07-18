package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.SwarmEscalationException;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Validates security tier boundaries during Swarm handoffs and Coordinator delegations.
 * If a transition moves from a lower security tier to a higher one, a SwarmEscalationException is thrown
 * to trigger Human-in-the-Loop (HITL) approval.
 * State: Stateless
 */
@Service
public class TierEscalationValidator {

    private static final Logger log = LoggerFactory.getLogger(TierEscalationValidator.class);

    private final AgentRegistry agentRegistry;

    public TierEscalationValidator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * @summary Validates that a source→target agent transition does not escalate security tiers without HITL approval.
     * @logic
     * 1. Resolves the AgentDefinition for both source and target agents.
     * 2. Compares their securityTier values (defaulting to 1 if null).
     * 3. If the target tier is strictly higher than the source tier, throws SwarmEscalationException.
     * 4. Same-tier or downward transitions proceed without interruption.
     */
    public void validate(String sourceAgentId, String targetAgentId, String orgId) {
        AgentDefinition sourceDef = agentRegistry.findById(sourceAgentId, orgId);
        AgentDefinition targetDef = agentRegistry.findById(targetAgentId, orgId);

        if (sourceDef == null) {
            log.warn("TierEscalationValidator: Source agent '{}' not found in registry — failing closed.", sourceAgentId);
            throw new BusinessValidationException(
                    "Tier escalation check failed: source agent '" + sourceAgentId + "' is unknown.");
        }
        if (targetDef == null) {
            log.warn("TierEscalationValidator: Target agent '{}' not found in registry — failing closed.", targetAgentId);
            throw new BusinessValidationException(
                    "Tier escalation check failed: target agent '" + targetAgentId + "' is unknown.");
        }

        int sourceTier = sourceDef.securityTier() != null ? sourceDef.securityTier() : 1;
        int targetTier = targetDef.securityTier() != null ? targetDef.securityTier() : 1;

        if (targetTier > sourceTier) {
            log.warn("TierEscalationValidator: Security escalation detected. Source='{}' (Tier {}), Target='{}' (Tier {})",
                    sourceAgentId, sourceTier, targetAgentId, targetTier);
            throw new SwarmEscalationException(sourceAgentId, targetAgentId, sourceTier, targetTier);
        }

        log.debug("TierEscalationValidator: Transition '{}' (Tier {}) → '{}' (Tier {}) is safe.",
                sourceAgentId, sourceTier, targetAgentId, targetTier);
    }
}
