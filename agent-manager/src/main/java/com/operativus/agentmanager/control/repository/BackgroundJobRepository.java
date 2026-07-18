package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Persistence for {@code background_jobs}. Provides both the internal
 *   queue mechanics (lock-and-claim, retry, stall-recovery) and the tenant-scoped read surface
 *   used by {@code JobsController} ({@link #findByIdAndOrg}, {@link #findByOrg},
 *   {@link #findByOrgAndStatus}).
 *
 * <p><b>Tenant-scoping:</b> {@code background_jobs} has no {@code org_id} column; the tenant
 *   boundary is derived via {@code agent_id → agents.org_id} (EXISTS subquery). Jobs where
 *   {@code agent_id IS NULL} (system/cron jobs) are invisible through the user-facing queries —
 *   they have no owning agent and therefore no tenant. The admin observability surface
 *   ({@code BackgroundJobController}) uses the unscoped {@link #findByStatusOrderByCreatedAtDesc}
 *   and {@link JpaRepository#findAll} methods intentionally.
 *
 * <p><b>Scale note:</b> the EXISTS subquery resolves via the {@code agents.id} PK index.
 *   Sufficient at current scale; a denormalized {@code org_id} column with composite index
 *   ({@code background_jobs(org_id, status, created_at)}) would be the migration path above
 *   ~1M rows / ~200ms p99 latency.
 */
@Repository
public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, String> {

    /**
     * Tenant-scoped single-job lookup. Returns the job only if it belongs to an agent in
     * {@code orgId}; otherwise empty (caller emits 404 — existence-leak protection mirrors
     * the Schedule / KnowledgeBase / Workflow pattern). Jobs where {@code agent_id IS NULL}
     * are not returned.
     */
    @Query("""
            SELECT j FROM BackgroundJob j
            WHERE j.id = :jobId
            AND EXISTS (
                SELECT a FROM AgentEntity a
                WHERE a.id = j.agentId AND a.orgId = :orgId
            )
            """)
    Optional<BackgroundJob> findByIdAndOrg(@Param("jobId") String jobId, @Param("orgId") String orgId);

    /**
     * Tenant-scoped listing — all jobs for the given org, most-recent first.
     * System jobs ({@code agent_id IS NULL}) are excluded.
     */
    @Query("""
            SELECT j FROM BackgroundJob j
            WHERE EXISTS (
                SELECT a FROM AgentEntity a
                WHERE a.id = j.agentId AND a.orgId = :orgId
            )
            ORDER BY j.createdAt DESC
            """)
    Page<BackgroundJob> findByOrg(@Param("orgId") String orgId, Pageable pageable);

    /**
     * Tenant-scoped listing filtered by status. Separated from {@link #findByOrg} to avoid
     * nullable-enum JPQL edge cases.
     */
    @Query("""
            SELECT j FROM BackgroundJob j
            WHERE j.status = :status
            AND EXISTS (
                SELECT a FROM AgentEntity a
                WHERE a.id = j.agentId AND a.orgId = :orgId
            )
            ORDER BY j.createdAt DESC
            """)
    Page<BackgroundJob> findByOrgAndStatus(@Param("orgId") String orgId,
                                           @Param("status") JobStatus status,
                                           Pageable pageable);

    @Query(value = """
            SELECT * FROM background_jobs
            WHERE status = 'QUEUED'
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            ORDER BY
                CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END,
                created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<BackgroundJob> findReadyJobs(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Query(value = "SELECT * FROM background_jobs WHERE status = 'PROCESSING' AND locked_at < :threshold",
            nativeQuery = true)
    List<BackgroundJob> findStalledProcessingJobs(@Param("threshold") LocalDateTime threshold);

    @Query("SELECT j FROM BackgroundJob j WHERE j.jobKey = :jobKey AND j.status NOT IN ('COMPLETED', 'FAILED', 'DLQ', 'CANCELLED')")
    Optional<BackgroundJob> findActiveByJobKey(@Param("jobKey") String jobKey);

    List<BackgroundJob> findByStatus(JobStatus status);

    Page<BackgroundJob> findByStatusOrderByCreatedAtDesc(JobStatus status, Pageable pageable);

    long countByStatus(JobStatus status);

    /**
     * @summary Per-status counts in a single query — backs the current-state stacked
     *     bar at the top of the Background Job Monitor tab (observability plan T036).
     *     Returns rows shaped {@code [status: JobStatus, count: Long]}; statuses with
     *     zero rows are absent (caller is responsible for filling defaults).
     */
    @Query("SELECT j.status, COUNT(j) FROM BackgroundJob j GROUP BY j.status")
    List<Object[]> countGroupByStatus();

    /**
     * @summary Atomic manual-retry UPDATE for the observability Background Job Monitor
     *          (plan T004). Flips a FAILED row back to QUEUED in a single statement so
     *          concurrent retry clicks can't double-enqueue a job.
     * @logic Returns 1 if the row was eligible (status=FAILED AND locked_at IS NULL AND
     *        retry_count < max_retries), 0 otherwise. Callers disambiguate a 0 return by
     *        issuing a follow-up SELECT to decide between 404, 409, and 422.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE background_jobs
               SET status = 'QUEUED',
                   locked_at = NULL,
                   started_at = NULL,
                   next_retry_at = CURRENT_TIMESTAMP,
                   error_message = NULL
             WHERE id = :id
               AND status = 'FAILED'
               AND locked_at IS NULL
               AND retry_count < max_retries
            """, nativeQuery = true)
    int atomicRetry(@Param("id") String id);

    /**
     * Atomically cancels a single non-terminal job. Returns 1 if the row was eligible
     * (QUEUED or PAUSED, not currently locked), 0 otherwise.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE background_jobs
               SET status = 'CANCELLED',
                   cancel_reason = :reason,
                   cancelled_by_user_id = :cancelledBy,
                   completed_at = CURRENT_TIMESTAMP,
                   locked_at = NULL
             WHERE id = :id
               AND status IN ('QUEUED', 'PAUSED')
               AND locked_at IS NULL
            """, nativeQuery = true)
    int atomicCancel(@Param("id") String id,
                     @Param("reason") String reason,
                     @Param("cancelledBy") String cancelledBy);

    /**
     * Bulk-cancels all QUEUED and PAUSED jobs not yet locked. Returns count of rows affected.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE background_jobs
               SET status = 'CANCELLED',
                   cancel_reason = :reason,
                   cancelled_by_user_id = :cancelledBy,
                   completed_at = CURRENT_TIMESTAMP,
                   locked_at = NULL
             WHERE status IN ('QUEUED', 'PAUSED')
               AND locked_at IS NULL
            """, nativeQuery = true)
    int bulkCancel(@Param("reason") String reason, @Param("cancelledBy") String cancelledBy);

    /**
     * Clears DLQ: transitions all DLQ rows to CANCELLED. Returns count of rows affected.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE background_jobs
               SET status = 'CANCELLED',
                   cancel_reason = :reason,
                   cancelled_by_user_id = :cancelledBy,
                   completed_at = CURRENT_TIMESTAMP
             WHERE status = 'DLQ'
            """, nativeQuery = true)
    int clearDlq(@Param("reason") String reason, @Param("cancelledBy") String cancelledBy);
}
