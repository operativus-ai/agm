package com.operativus.agentmanager.control.service;

import java.util.UUID;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.entity.Schedule;
import com.operativus.agentmanager.core.entity.ScheduleRun;
import com.operativus.agentmanager.control.repository.ScheduleRepository;
import com.operativus.agentmanager.control.repository.ScheduleRunRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Infrastructure Driver that evaluates CRON timings and bridges active schedules into the LLM Execution Engine.
 * State: Stateful (Manages active Spring @Scheduled polling)
 */
@Service
public class ScheduleExecutionPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionPoller.class);

    /**
     * Synthetic user_id stamped on agent_sessions rows that the scheduler auto-creates
     * for WORKFLOW schedules with no {@code resume_session_id}. Mirrors the convention
     * used by {@code WorkflowScheduledExecutionRuntimeTest}'s seed fixture so test and
     * production rows look identical to downstream observability.
     */
    public static final String SCHEDULER_SYNTHETIC_USER_ID = "sched-user";

    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository scheduleRunRepository;
    private final SessionRepository sessionRepository;
    private final com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
    private final WorkflowService workflowService;

    public ScheduleExecutionPoller(ScheduleRepository scheduleRepository,
                                   ScheduleRunRepository scheduleRunRepository,
                                   SessionRepository sessionRepository,
                                   com.operativus.agentmanager.core.registry.AgentOperations agentOperations,
                                   WorkflowService workflowService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.sessionRepository = sessionRepository;
        this.agentOperations = agentOperations;
        this.workflowService = workflowService;
    }

    /**
     * @summary Background poller that evaluates active schedules every 60 seconds.
     * @logic Fetches all schedules marked 'isActive'. Calculates 'next execution' time based on the CRON expression and the last run time. Triggers execution if due.
     *
     *   Resilience: {@code @Retryable(DataAccessException)} re-invokes the method on a transient
     *   DB blip (connection drop, deadlock, lock timeout). Spring Retry's advisor wraps the
     *   {@code @Transactional} advisor (Retry order = LOWEST_PRECEDENCE-1, Tx = LOWEST_PRECEDENCE),
     *   so each retry attempt gets a fresh transaction and a fresh {@code FOR UPDATE SKIP LOCKED}
     *   acquire. The per-schedule {@code try/catch} inside the loop swallows non-fatal failures,
     *   so the only path that bubbles to the retry advisor is the initial repository read or a
     *   DB connection failure mid-tick — both pre-dispatch states where retry is safe (no
     *   schedule has yet been triggered, so re-running the loop cannot double-fire).
     */
    @Retryable(retryFor = DataAccessException.class,
               maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2.0))
    @Transactional
    @Scheduled(fixedRateString = "${agentmanager.scheduler.schedule-poll-ms:60000}")
    public void evaluateSchedules() {
        log.info("Evaluating active schedules...");
        List<Schedule> activeSchedules = scheduleRepository.findByIsActiveTrueForUpdateSkipLocked();

        for (Schedule schedule : activeSchedules) {
            try {
                // Per-schedule zone: each schedule's IANA Schedule.timezone defines the
                // wall-clock its cron expression operates in. ZonedDateTime.now(zone) is
                // the same physical instant regardless of zone, but the ZonedDateTime
                // carries the zone metadata that CronExpression.next() uses for wall-clock
                // increments (DST-aware, locale-aware).
                ZoneId zone = resolveScheduleZone(schedule);
                ZonedDateTime now = ZonedDateTime.now(zone);
                if (isScheduleDue(schedule, now)) {
                    log.info("Schedule [{}] is due. Triggering execution.", schedule.getId());
                    triggerScheduleExecution(schedule);
                }
            } catch (Exception e) {
                log.error("Error evaluating schedule [{}]: {}", schedule.getId(), e.getMessage());
            }
        }
    }

    /**
     * Resolve the {@link ZoneId} a schedule's cron expression should be evaluated in.
     * Falls back to {@link ZoneId#systemDefault()} when the schedule has no IANA
     * timezone set (legacy rows or schedules that opt out of explicit zoning), and
     * also when the stored value is not a valid zone string (defensive — never crash
     * a poll tick on a stored value the operator can repair via PUT).
     */
    static ZoneId resolveScheduleZone(Schedule schedule) {
        String tz = schedule.getTimezone();
        if (tz == null || tz.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (java.time.DateTimeException ex) {
            log.warn("Schedule [{}] carries invalid timezone '{}' — falling back to system default. "
                    + "Operator should PUT a valid IANA zone (e.g. 'America/Chicago' or 'UTC').",
                    schedule.getId(), tz);
            return ZoneId.systemDefault();
        }
    }

    /**
     * @summary Forces immediate execution of a schedule, bypassing CRON timing.
     * @logic Tenant-scoped: cross-tenant manual triggers are silently dropped (the schedule
     *   is invisible to the caller). The scheduled poller in this same class is exempt —
     *   it runs in a system context and uses findByIsActiveTrue() across tenants by design.
     */
    public void manualTrigger(String scheduleId) {
        String callerOrgId = com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId();
        String resolvedOrgId = (callerOrgId == null || callerOrgId.isBlank())
                ? com.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG
                : callerOrgId;
        scheduleRepository.findByIdAndOrgId(scheduleId, resolvedOrgId).ifPresent(this::triggerScheduleExecution);
    }

    /**
     * @summary Calculates whether a schedule should be executed based on its CRON expression.
     * @logic Cron evaluation is done in the schedule's own {@link Schedule#getTimezone()}
     *   (IANA zone, falling back to system default — see {@link #resolveScheduleZone}).
     *   Both {@code now} and {@code lastRunStartedAt} are rebased to that zone so wall-clock
     *   increments (e.g. "9 AM daily") match the operator's intended timezone. Pre-fix
     *   both rebased to {@code ZoneId.systemDefault()} which silently broke any schedule
     *   whose timezone differed from the JVM's default — typically UTC in production.
     */
    boolean isScheduleDue(Schedule schedule, ZonedDateTime now) {
        // F6 — DAG dependency. If this schedule declares a parent via
        // dependsOnScheduleId, gate firing on the parent's most-recent terminal status
        // being COMPLETED. A FAILED or RUNNING parent (or a parent that has never run)
        // blocks the dependent. Fail-closed: parent missing → block (the validate-on-
        // create guard should prevent this, but the poller is the last line of defense).
        if (!isDependencyMet(schedule)) {
            log.debug("Schedule [{}] gated by dependsOnScheduleId=[{}] — parent's most-recent run is not COMPLETED",
                    schedule.getId(), schedule.getDependsOnScheduleId());
            return false;
        }

        ZoneId zone = now.getZone();
        CronExpression expression = CronExpression.parse(schedule.getCronExpression());
        List<ScheduleRun> runs = scheduleRunRepository.findByScheduleIdOrderByStartedAtDesc(schedule.getId());

        if (runs.isEmpty()) {
             // If never run, run it immediately
             return true;
        }

        LocalDateTime lastRunStartedAt = runs.get(0).getStartedAt();
        ZonedDateTime lastRunZoned = lastRunStartedAt.atZone(zone);
        ZonedDateTime nextExecution = expression.next(lastRunZoned);

        return nextExecution != null && (now.isEqual(nextExecution) || now.isAfter(nextExecution));
    }

    /**
     * F6 — Returns true if {@code schedule} has no declared parent, OR its parent's
     * most-recent <em>finished</em> {@code schedule_runs} row is COMPLETED. RUNNING
     * rows are skipped — they represent the parent firing right now (possibly in
     * THIS same poll tick) and are not yet terminal evidence either way. Without
     * this skip, the iteration order within a tick would race: if parent A is
     * processed before dependent B, A's fresh RUNNING insert would block B even
     * though A's prior run was COMPLETED.
     *
     * Returns false (block) when:
     *   <ul>
     *     <li>parent exists but has no finished runs yet;</li>
     *     <li>parent's most-recent finished run is FAILED, CANCELLED, or any non-COMPLETED status;</li>
     *     <li>parent row no longer exists (defense-in-depth — fail-closed).</li>
     *   </ul>
     */
    private boolean isDependencyMet(Schedule schedule) {
        String parentId = schedule.getDependsOnScheduleId();
        if (parentId == null || parentId.isBlank()) return true;
        if (!scheduleRepository.existsById(parentId)) {
            log.warn("Schedule [{}] depends on missing parent [{}] — blocking until parent re-appears or dependency is cleared",
                    schedule.getId(), parentId);
            return false;
        }
        List<ScheduleRun> parentRuns = scheduleRunRepository.findByScheduleIdOrderByStartedAtDesc(parentId);
        for (ScheduleRun r : parentRuns) {
            if (r.getStatus() != RunStatus.RUNNING) {
                return r.getStatus() == RunStatus.COMPLETED;
            }
        }
        return false;
    }

    /**
     * @summary Launches the target of a schedule (Agent, Team, or Workflow) in a background Virtual Thread.
     * @logic Persists a RUNNING schedule_runs row immediately, then dispatches the actual
     *        execution onto a virtual thread so the poll loop can return promptly. The
     *        async branch invokes the synchronous AgentOperations.run(...) for AGENT/TEAM
     *        targets, capturing the real terminal status from the returned RunResponse.
     *        For WORKFLOW targets, executeWorkflowAsync returns the workflowRunId immediately;
     *        it is stored on the schedule_run row and syncWorkflowScheduleRuns() polls the
     *        workflow_run terminal state on the next tick rather than stamping COMPLETED at dispatch.
     *
     *        T037-3 one-shot: if {@code schedule.isOneShot()}, flip {@code is_active=false}
     *        in the same outer transaction as the schedule_run insert so the schedule fires
     *        exactly once. The flip + insert atomicity guarantees the next tick will not
     *        observe a still-active one-shot row even if the VT has not started executing.
     */
    private void triggerScheduleExecution(Schedule schedule) {
        String scheduleRunId = UUID.randomUUID().toString();
        ScheduleRun runRecord = new ScheduleRun(
                scheduleRunId,
                schedule.getId(),
                RunStatus.RUNNING,
                null,
                null,
                null
        );
        scheduleRunRepository.save(runRecord);

        if (schedule.isOneShot()) {
            schedule.setActive(false);
            scheduleRepository.save(schedule);
            log.info("Schedule [{}] is one-shot — disabled after first dispatch", schedule.getId());
        }

        String instruction = schedule.getContextualPrompt() != null && !schedule.getContextualPrompt().isBlank()
                ? schedule.getContextualPrompt()
                : "Executing scheduled task: " + schedule.getName();
        String sessionId = schedule.getResumeSessionId();
        String targetType = schedule.getTargetType();
        String targetId = schedule.getTargetId();
        String orgId = schedule.getOrgId();

        Thread.ofVirtual()
                .name("schedule-exec-" + scheduleRunId)
                .start(() -> executeAndPersist(runRecord, targetType, targetId, instruction, sessionId, orgId));
    }

    /**
     * @summary Runs the schedule target on a virtual thread and updates the schedule_runs
     *          row with the actual terminal status returned by the agent/team execution.
     * @logic The {@code runRecord} entity is passed in-memory from the caller's
     *        {@code @Transactional} context. The outer transaction commits before the VT
     *        proceeds past the initial agent/workflow call; the entity is detached at that
     *        point, so the terminal {@code scheduleRunRepository.save(runRecord)} below
     *        performs a merge (UPDATE), not an INSERT.
     */
    private void executeAndPersist(ScheduleRun runRecord, String targetType, String targetId,
                                   String instruction, String sessionId, String orgId) {
        String scheduleRunId = runRecord.getId();
        try {
            // F8 — fresh VT does NOT inherit JDK 21 ScopedValues. Bind the schedule's owning
            // orgId so downstream lookups (DatabaseAgentRegistry.findById, advisor chain reads,
            // WorkflowService.executeWorkflowAsync's AgentContextSnapshot.capture()) see the
            // schedule owner's tenant rather than falling through to DEFAULT_SYSTEM_ORG.
            if ("AGENT".equalsIgnoreCase(targetType) || "TEAM".equalsIgnoreCase(targetType)) {
                RunResponse response = ScopedValue
                        .where(com.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                        .call(() -> agentOperations.run(
                                targetId, instruction, null, sessionId, null, orgId, false, null));
                log.info("Schedule run [{}] for {} target [{}] completed with status {} (run id {})",
                        scheduleRunId, targetType, targetId, response.status(), response.runId());
                runRecord.setStatus(response.status());
                runRecord.setAgentRunId(response.runId());
                runRecord.setOutput("{\"run_id\": \"" + response.runId() + "\"}");
            } else if ("WORKFLOW".equalsIgnoreCase(targetType)) {
                String effectiveSession = sessionId != null
                        ? sessionId
                        : autoCreateSchedulerSession(orgId);
                final String[] workflowRunIdOut = new String[1];
                ScopedValue.where(com.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                        .run(() -> workflowRunIdOut[0] = workflowService.executeWorkflowAsync(
                                targetId, instruction, effectiveSession));
                String workflowRunId = workflowRunIdOut[0];
                log.info("Dispatched Workflow [{}] as run [{}] for schedule run [{}] — RUNNING until terminal sync",
                        targetId, workflowRunId, scheduleRunId);
                runRecord.setWorkflowRunId(workflowRunId);
                runRecord.setOutput("{\"workflowRunId\": \"" + workflowRunId + "\", \"workflowId\": \"" + targetId + "\"}");
                // Leave status as RUNNING; syncWorkflowScheduleRuns() will flip to terminal once the workflow_run settles.
                scheduleRunRepository.save(runRecord);
                return;
            } else {
                runRecord.setStatus(RunStatus.FAILED);
                runRecord.setErrorMessage("Unknown schedule target type: " + targetType);
            }
            runRecord.setCompletedAt(LocalDateTime.now());
            scheduleRunRepository.save(runRecord);
        } catch (ResourceNotFoundException nf) {
            log.warn("Schedule target [{}] missing; self-disabling schedule to prevent re-fire storm", targetId);
            runRecord.setStatus(RunStatus.FAILED);
            runRecord.setErrorMessage(nf.getMessage());
            runRecord.setCompletedAt(LocalDateTime.now());
            scheduleRunRepository.save(runRecord);
            selfDisableSchedule(runRecord.getScheduleId(), nf.getMessage());
        } catch (Exception e) {
            log.error("Error executing schedule target [{}]: {}", scheduleRunId, e.getMessage(), e);
            runRecord.setStatus(RunStatus.FAILED);
            runRecord.setErrorMessage(e.getMessage());
            runRecord.setCompletedAt(LocalDateTime.now());
            scheduleRunRepository.save(runRecord);
        }
    }

    /**
     * @summary Creates a fresh {@code agent_sessions} row owned by the schedule's tenant
     *          so the downstream {@code workflow_runs} INSERT does not FK-violate when
     *          a WORKFLOW schedule has no {@code resume_session_id}.
     * @logic Schedules created without a {@code resume_session_id} previously yielded a
     *        random UUID at dispatch that did not exist in {@code agent_sessions} — the
     *        {@code fk_workflow_runs_session} constraint then aborted the workflow_runs
     *        INSERT and left the schedule_run in FAILED with no run linkage. This helper
     *        persists a minimal session row keyed by a fresh UUID, attributed to the
     *        synthetic {@code sched-user} (same convention as scheduled-execution test
     *        fixtures), under the schedule's owning org so tenant isolation holds.
     *        Returns the new session id for the workflow dispatcher to use.
     */
    private String autoCreateSchedulerSession(String orgId) {
        String resolvedOrg = (orgId != null && !orgId.isBlank())
                ? orgId
                : TenantConstants.DEFAULT_SYSTEM_ORG;
        AgentSession session = new AgentSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(SCHEDULER_SYNTHETIC_USER_ID);
        session.setOrgId(resolvedOrg);
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.save(session);
        return session.getSessionId();
    }

    /**
     * @summary Polls RUNNING schedule_runs backed by a workflow_run and propagates terminal status.
     * @logic Runs on the same cadence as the main schedule poller. For each RUNNING schedule_run
     *        that has a non-null workflow_run_id, it reads the linked workflow_run status via
     *        WorkflowService.getWorkflowRunStatus(). If the workflow_run has reached a terminal
     *        state (COMPLETED, FAILED, CANCELLED), it mirrors that status onto the schedule_run
     *        and stamps completed_at. RUNNING/PAUSED workflow_runs are left for the next tick.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.workflow-schedule-run-sync-ms:60000}")
    public void syncWorkflowScheduleRuns() {
        List<ScheduleRun> pending = scheduleRunRepository.findByStatusAndWorkflowRunIdIsNotNull(RunStatus.RUNNING);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("syncWorkflowScheduleRuns: checking {} RUNNING workflow-backed schedule_run(s)", pending.size());
        for (ScheduleRun run : pending) {
            workflowService.getWorkflowRunStatus(run.getWorkflowRunId()).ifPresent(workflowStatus -> {
                if (workflowStatus == RunStatus.COMPLETED || workflowStatus == RunStatus.FAILED
                        || workflowStatus == RunStatus.CANCELLED) {
                    run.setStatus(workflowStatus);
                    run.setCompletedAt(LocalDateTime.now());
                    scheduleRunRepository.save(run);
                    log.info("schedule_run [{}] terminal sync: workflow_run [{}] → {}",
                            run.getId(), run.getWorkflowRunId(), workflowStatus);
                }
            });
        }
    }

    /**
     * @summary Flips {@code schedules.is_active=false} when the target resource has been
     *          deleted, preventing the poller from re-firing every tick.
     */
    private void selfDisableSchedule(String scheduleId, String reason) {
        scheduleRepository.findById(scheduleId).ifPresent(s -> {
            s.setActive(false);
            scheduleRepository.save(s);
            log.info("Schedule [{}] self-disabled after missing target: {}", scheduleId, reason);
        });
    }
}
