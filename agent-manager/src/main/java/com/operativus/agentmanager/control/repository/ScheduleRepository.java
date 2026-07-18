package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.Schedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Automated Task Schedules.
 * State: Stateless
 */
@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, String> {

    /**
     * @summary Retrieves a list of all currently active task schedules across the system.
     * @logic Derived query executed by Spring Data JPA framework. UNSCOPED — used only by
     *   the system-context {@code ScheduleExecutionPoller} which fires across every tenant.
     *   Tenant filtering for user-facing reads goes through {@link #findAllByOrgId} or
     *   {@link #findByIdAndOrgId}.
     */
    List<Schedule> findByIsActiveTrue();

    /**
     * @summary Same as {@link #findByIsActiveTrue} but acquires a Postgres FOR UPDATE SKIP LOCKED
     *   row-level lock on each returned schedule row. Only called from
     *   {@code ScheduleExecutionPoller.evaluateSchedules()} inside a {@code @Transactional} context.
     *   Concurrent nodes that try to lock the same row skip it and receive an empty list,
     *   preventing double-fire on multi-instance deployments.
     */
    @Query(value = "SELECT * FROM schedules WHERE is_active = true FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Schedule> findByIsActiveTrueForUpdateSkipLocked();

    /**
     * @summary Retrieves a list of schedules associated with a specific target definition (e.g. Agent or Workflow ID).
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<Schedule> findByTargetTypeAndTargetId(String targetType, String targetId);

    // Tenant-scoped finders. Used by ScheduleService (and indirectly SchedulesController)
    // to enforce org isolation. The unscoped findAll/findById/etc. above remain for
    // system-context callers (poller, retention sweeps).
    Page<Schedule> findAllByOrgId(String orgId, Pageable pageable);

    Optional<Schedule> findByIdAndOrgId(String id, String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);
}
