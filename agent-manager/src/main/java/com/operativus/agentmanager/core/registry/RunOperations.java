package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.entity.AgentRun;
import java.time.LocalDateTime;
import java.util.Optional;
import com.operativus.agentmanager.core.model.enums.RunStatus;

public interface RunOperations {
    AgentRun save(AgentRun run);
    Optional<AgentRun> findById(String id);
    /** Loads the run and acquires a pessimistic write lock for the duration of the caller's transaction. */
    Optional<AgentRun> findByIdForUpdate(String id);
    long countByAgentIdAndStatus(String agentId, RunStatus status);
    java.util.List<AgentRun> findByStatusIn(java.util.List<RunStatus> statuses);
    java.util.List<AgentRun> findByStatusInAndCreatedAtBefore(
            java.util.Collection<RunStatus> statuses, LocalDateTime cutoff);
    java.util.List<AgentRun> findByIdIn(java.util.Collection<String> ids);
}
