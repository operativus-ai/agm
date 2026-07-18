package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.TaskEntity;
import ai.operativus.agentmanager.core.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Persistence boundary for {@link TaskEntity}. All cross-tenant
 *     reads go through the {@code (orgId)} filter pattern. Writes are funneled through
 *     {@link #atomicallyDispatch} for the status CAS (D5 idempotency).
 * State: Stateless.
 */
public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    /** Org-scoped fetch by id. The repository never exposes an org-blind {@code findById}
     *  to service callers — use this to enforce tenant isolation at the query layer. */
    Optional<TaskEntity> findByIdAndOrgId(String id, String orgId);

    /** All tasks for a team run, ordered by creation for deterministic listing in the UI. */
    List<TaskEntity> findByTeamRunIdAndOrgIdOrderByCreatedAtAsc(String teamRunId, String orgId);

    /** Tasks pending dispatch (status=PENDING). Worker loop polls this and evaluates
     *  dependency-completion in code before calling {@link #atomicallyDispatch}. */
    List<TaskEntity> findByTeamRunIdAndStatusOrderByCreatedAtAsc(String teamRunId, TaskStatus status);

    /** Atomic dispatch CAS — flip PENDING → IN_PROGRESS in a single SQL statement.
     *  Returns 1 when this caller won the race, 0 when another worker already claimed
     *  the task. Per D5, this is the primary idempotency primitive — no separate version
     *  column needed because the status itself is the gate. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE TaskEntity t
               SET t.status = ai.operativus.agentmanager.core.entity.TaskStatus.IN_PROGRESS,
                   t.workerId = :workerId,
                   t.dispatchedAt = :dispatchedAt,
                   t.startedAt = :dispatchedAt
             WHERE t.id = :taskId
               AND t.orgId = :orgId
               AND t.status = ai.operativus.agentmanager.core.entity.TaskStatus.PENDING
            """)
    int atomicallyDispatch(@Param("taskId") String taskId,
                           @Param("orgId") String orgId,
                           @Param("workerId") String workerId,
                           @Param("dispatchedAt") LocalDateTime dispatchedAt);

    /** Count by terminal status for the run — drives the "all tasks done" gate that
     *  triggers the coordinator's synthesis pass. */
    long countByTeamRunIdAndStatusIn(String teamRunId, List<TaskStatus> statuses);
}
