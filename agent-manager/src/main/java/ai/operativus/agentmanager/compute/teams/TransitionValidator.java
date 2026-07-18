package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Validates that an agent-to-agent transition (handoff or delegation) is permitted
 * by the DAG constraints defined for a given team. If no edges are configured for a team, all transitions
 * are permitted (backward-compatible unconstrained mode).
 * State: Stateless
 */
@Service
public class TransitionValidator {

    private static final Logger log = LoggerFactory.getLogger(TransitionValidator.class);

    private final TransitionEdgeRepository edgeRepository;

    public TransitionValidator(TransitionEdgeRepository edgeRepository) {
        this.edgeRepository = edgeRepository;
    }

    /**
     * @summary Validates that a source→target agent transition is allowed within the team's DAG constraints.
     * @logic
     * 1. Counts edges defined for the team. If zero, the team is unconstrained — all transitions are permitted.
     * 2. If edges exist, checks specifically if a source→target edge is registered.
     * 3. Throws BusinessValidationException if the transition is illegal.
     */
    public void validate(String teamId, String sourceAgentId, String targetAgentId) {
        if (teamId == null) {
            log.debug("TransitionValidator: No team context — skipping DAG validation.");
            return;
        }

        long edgeCount = edgeRepository.countByTeamId(teamId);
        if (edgeCount == 0) {
            log.debug("TransitionValidator: Team '{}' has no DAG edges — unconstrained mode.", teamId);
            return;
        }

        boolean allowed = edgeRepository.existsByTeamIdAndSourceAgentIdAndTargetAgentId(
                teamId, sourceAgentId, targetAgentId);

        if (!allowed) {
            log.warn("TransitionValidator: Illegal transition blocked. Team='{}', Source='{}', Target='{}'",
                    teamId, sourceAgentId, targetAgentId);
            throw new BusinessValidationException(
                    "DAG constraint violation: Agent '" + sourceAgentId
                            + "' is not permitted to route to Agent '" + targetAgentId
                            + "' within Team '" + teamId + "'. Check configured transition edges.");
        }

        log.debug("TransitionValidator: Transition '{}' → '{}' is valid in Team '{}'.",
                sourceAgentId, targetAgentId, teamId);
    }
}
