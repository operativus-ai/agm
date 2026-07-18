package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgenticMemoryRepository extends JpaRepository<AgenticMemoryEntity, UUID> {

    List<AgenticMemoryEntity> findByUserId(String userId);

    List<AgenticMemoryEntity> findByUserIdAndMemoryTier(String userId, AgenticMemoryEntity.MemoryTier memoryTier);

    List<AgenticMemoryEntity> findByVectorIdIn(List<String> vectorIds);

    List<AgenticMemoryEntity> findByOrgId(String orgId);

    List<AgenticMemoryEntity> findByUserIdAndOrgId(String userId, String orgId);

    Optional<AgenticMemoryEntity> findByMemoryIdAndOrgId(UUID memoryId, String orgId);
}
