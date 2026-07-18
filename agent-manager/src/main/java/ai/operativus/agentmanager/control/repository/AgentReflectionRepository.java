package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentReflectionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Persistence gateway for agent reflection trace nodes.
 * Provides query methods for retrieving reflection chains by run, session, or agent.
 * State: Stateless (Spring Data JPA Interface)
 */
@Repository
public interface AgentReflectionRepository extends JpaRepository<AgentReflectionEntity, UUID> {

    List<AgentReflectionEntity> findByRunIdOrderByStepIndexAsc(String runId);

    List<AgentReflectionEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    Page<AgentReflectionEntity> findByAgentIdOrderByCreatedAtDesc(String agentId, Pageable pageable);

    List<AgentReflectionEntity> findByParentReflectionId(UUID parentReflectionId);
}
