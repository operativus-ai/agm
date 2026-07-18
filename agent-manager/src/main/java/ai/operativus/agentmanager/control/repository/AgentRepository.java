package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Agent configurations.
 * State: Stateless
 */
@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, String> {
    
    /**
     * @summary Retrieves a paginated list of agents filtered by their active status.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    Page<AgentEntity> findAllByActive(boolean active, Pageable pageable);

    /**
     * @summary Retrieves all active agents without pagination.
     */
    java.util.List<AgentEntity> findByActiveTrue();

    @Query(value = "SELECT * FROM agents WHERE knowledge_base_ids @> jsonb_build_array(CAST(:kbId AS text))", nativeQuery = true)
    List<AgentEntity> findByKnowledgeBaseIdsContaining(@Param("kbId") String kbId);

    long countByModelId(String modelId);

    List<AgentEntity> findByModelId(String modelId);

    @Query("SELECT a.modelId, COUNT(a) FROM AgentEntity a WHERE a.modelId IS NOT NULL GROUP BY a.modelId")
    List<Object[]> countAgentsGroupedByModelId();

    // ── Org-scoped reads (tenant isolation) ────────────────────────────────
    Page<AgentEntity> findAllByOrgId(String orgId, Pageable pageable);
    Page<AgentEntity> findAllByOrgIdAndActive(String orgId, boolean active, Pageable pageable);
    List<AgentEntity> findByOrgIdAndActiveTrue(String orgId);
    Optional<AgentEntity> findByIdAndOrgId(String id, String orgId);
    boolean existsByIdAndOrgId(String id, String orgId);

    @Query("SELECT DISTINCT a.orgId FROM AgentEntity a WHERE a.orgId IS NOT NULL")
    List<String> findDistinctOrgIds();
}
