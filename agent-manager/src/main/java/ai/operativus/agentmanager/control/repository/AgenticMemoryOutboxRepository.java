package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgenticMemoryOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AgenticMemoryOutboxRepository extends JpaRepository<AgenticMemoryOutboxEntity, UUID> {

    /**
     * PostgreSQL specific native query to safely fetch and lock pending events
     * exactly once, securely supporting horizontal multi-pod concurrent polling.
     */
    @Query(value = "SELECT * FROM agentic_memory_outbox " +
                   "WHERE status = 'PENDING' " +
                   "ORDER BY created_at ASC " +
                   "LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<AgenticMemoryOutboxEntity> findPendingEventsAndLock(int limit);

    @Modifying
    @Query("DELETE FROM AgenticMemoryOutboxEntity o WHERE o.memoryId IN :memoryIds")
    int deleteByMemoryIdIn(@Param("memoryIds") Collection<UUID> memoryIds);
}
