package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.TransitionEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Provides Spring Data JPA persistence operations for TransitionEdge entities, enabling DAG-based routing validation queries.
 * State: Stateless (Repository Interface)
 */
@Repository
public interface TransitionEdgeRepository extends JpaRepository<TransitionEdge, String> {

    /**
     * Retrieves all transition edges defined for a specific team.
     */
    List<TransitionEdge> findByTeamId(String teamId);

    /**
     * Retrieves all allowed target edges from a specific source agent within a team.
     */
    List<TransitionEdge> findByTeamIdAndSourceAgentId(String teamId, String sourceAgentId);

    /**
     * Checks if a specific source→target edge exists for a team.
     */
    boolean existsByTeamIdAndSourceAgentIdAndTargetAgentId(String teamId, String sourceAgentId, String targetAgentId);

    /**
     * Counts edges for a team to determine if DAG validation is active (unconstrained if 0).
     */
    long countByTeamId(String teamId);

    /**
     * Deletes all edges for a specific team (cascade cleanup).
     */
    void deleteByTeamId(String teamId);
}
