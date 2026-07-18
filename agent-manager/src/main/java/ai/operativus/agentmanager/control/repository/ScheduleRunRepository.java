package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.ScheduleRun;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the Execution History of Automated Task Schedules.
 * State: Stateless
 */
@Repository
public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, String> {

    /**
     * @summary Retrieves the execution history for a specific schedule, ordered chronologically descending by start time.
     * @logic Derived query executed by Spring Data JPA framework. Used by
     *        {@code ScheduleExecutionPoller.isScheduleDue} for last-run lookup; the
     *        ordering guarantee lets the poller stop after the first row.
     */
    List<ScheduleRun> findByScheduleIdOrderByStartedAtDesc(String scheduleId);

    /**
     * @summary Paginated overload of the schedule-run history.
     * @logic Used by the public {@code GET /api/v1/schedules/{id}/runs} surface to
     *        cap the response size — high-frequency schedules (every-minute cron over
     *        weeks) would otherwise return tens of thousands of rows per call.
     *        The {@code Pageable}'s sort is ignored; the derived-method DESC ordering
     *        wins.
     */
    Page<ScheduleRun> findByScheduleIdOrderByStartedAtDesc(String scheduleId, Pageable pageable);

    /**
     * @summary Finds RUNNING schedule_runs that have a linked workflow_run pending terminal-status sync.
     * @logic Used by ScheduleExecutionPoller.syncWorkflowScheduleRuns to back-propagate
     *        COMPLETED/FAILED/CANCELLED from workflow_runs to the parent schedule_run row.
     */
    List<ScheduleRun> findByStatusAndWorkflowRunIdIsNotNull(RunStatus status);

    /**
     * @summary Aggregates the most-recent {@code startedAt} per schedule for a batch of schedules.
     * @logic Single GROUP BY to avoid N+1 when projecting {@code lastRunAt} on
     *        {@code ScheduleDTO}. Each row is {@code [scheduleId, latestStartedAt]}.
     *        Schedules with no runs are simply absent from the result; callers should
     *        treat absence as null.
     */
    @Query("SELECT r.scheduleId, MAX(r.startedAt) FROM ScheduleRun r WHERE r.scheduleId IN :scheduleIds GROUP BY r.scheduleId")
    List<Object[]> findLatestStartedAtByScheduleIds(@Param("scheduleIds") Collection<String> scheduleIds);
}
