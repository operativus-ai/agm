package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.core.entity.A2aTaskEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Append-only persistence for A2A task lifecycle events.
 * Backs the {@code a2a_task_events} audit table.
 *
 * Usage contract: rows are never updated or deleted via this repository.
 * Every state transition in {@code A2ATaskExecutor} appends a new row.
 *
 * State: Stateless (Repository Interface)
 */
@Repository
public interface A2aTaskEventRepository extends JpaRepository<A2aTaskEventEntity, Long> {

    /**
     * @summary Returns all lifecycle events for a given task ID in insertion order.
     * @logic Used to reconstruct the full execution timeline for a specific task — e.g.,
     *        when a peer queries AGM for post-hoc task status after SSE disconnection.
     */
    List<A2aTaskEventEntity> findByTaskIdOrderByEventTsAsc(String taskId);

    /**
     * @summary Returns all events associated with a specific AGM run ID.
     * @logic Used to correlate A2A task events with the internal run record in {@code agent_runs}.
     */
    List<A2aTaskEventEntity> findByRunId(String runId);

    /**
     * @summary Returns all events for a given task in a specific terminal status.
     * @logic Useful for querying whether a task reached COMPLETED, FAILED, or BUDGET_HALT
     *        without loading the full event history.
     */
    List<A2aTaskEventEntity> findByTaskIdAndStatus(String taskId, A2aTaskStatus status);
}
