package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for AI Model definitions (e.g. GPT-4, Claude 3).
 * State: Stateless
 */
@Repository
public interface ModelRepository extends JpaRepository<ModelEntity, String> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);
}
