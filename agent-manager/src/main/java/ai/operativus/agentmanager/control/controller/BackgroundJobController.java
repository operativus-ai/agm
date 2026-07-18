package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.config.PaginationDefaultsConfig;
import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.service.SystemAuditService;
import ai.operativus.agentmanager.control.service.queue.JobQueueAdminState;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.BackgroundJobResponse;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Domain Responsibility: Read-side HTTP surface for the background-job queue
 * (observability plan Phase 1 T004). Lists jobs for the monitor UI and exposes an
 * atomic manual-retry action that cannot double-enqueue a job under concurrent POSTs.
 *
 * <p>The retry is a single {@code UPDATE … WHERE …} predicate (plan H1 resolution):
 * one row affected means the transition happened; zero rows means a follow-up SELECT
 * tells us whether the job is missing (404), not-retryable (409), or at the retry cap
 * (422). All four outcomes emit {@code agm.observability.bgjob.retry{outcome}} so
 * platform-ops dashboards can track retry success rates.
 *
 * <p><strong>Cross-tenant visibility is intentional.</strong> This is a platform-admin
 * observability surface — admins legitimately need cross-org job visibility to triage
 * queue backlogs, stuck jobs, and retry failures. All endpoints are {@code ROLE_ADMIN}-
 * gated; there is no per-org scoping by design.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/background-jobs")
public class BackgroundJobController {

    private final BackgroundJobRepository jobRepository;
    private final JobQueueAdminState adminState;
    private final SystemAuditService systemAuditService;
    private final MeterRegistry meterRegistry;
    private final Counter retryOkCounter;
    private final Counter retryNotFoundCounter;
    private final Counter retryNotFailedCounter;
    private final Counter retryMaxRetriesCounter;

    public BackgroundJobController(BackgroundJobRepository jobRepository,
                                   JobQueueAdminState adminState,
                                   SystemAuditService systemAuditService,
                                   MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.adminState = adminState;
        this.systemAuditService = systemAuditService;
        this.meterRegistry = meterRegistry;
        this.retryOkCounter = Counter.builder("agm.observability.bgjob.retry").tag("outcome", "ok").register(meterRegistry);
        this.retryNotFoundCounter = Counter.builder("agm.observability.bgjob.retry").tag("outcome", "not_found").register(meterRegistry);
        this.retryNotFailedCounter = Counter.builder("agm.observability.bgjob.retry").tag("outcome", "not_failed").register(meterRegistry);
        this.retryMaxRetriesCounter = Counter.builder("agm.observability.bgjob.retry").tag("outcome", "max_retries").register(meterRegistry);
    }

    /**
     * @summary Paginated list of background jobs newest-first, optionally filtered
     *     by status.
     * @logic
     * - No {@code status} → {@link BackgroundJobRepository#findAll(Pageable)} with
     *   a created-at DESC pageable; unbounded list is never returned (pagination is
     *   mandatory).
     * - {@code status} set → {@link BackgroundJobRepository#findByStatusOrderByCreatedAtDesc}.
     * - Entity → DTO mapping omits the {@code payload}/{@code result} TEXT blobs to
     *   keep the list response small; the polling endpoint at {@code GET /api/jobs/{id}}
     *   remains the path for full-payload inspection.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<BackgroundJobResponse>> list(
            @RequestParam(value = "status", required = false) JobStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PaginationDefaultsConfig.clampedPageRequest(page, size);
        Page<BackgroundJob> jobs = (status != null)
                ? jobRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : jobRepository.findAll(pageable);
        return ResponseEntity.ok(jobs.map(BackgroundJobController::toDto));
    }

    /**
     * @summary Per-status row count for the {@code background_jobs} table — the
     *     "right now" view at the top of the Background Job Monitor tab
     *     (observability plan T036). One round-trip; statuses with no rows are
     *     filled with zero so the response is the full enum.
     * @logic
     * - Single GROUP BY query via {@link BackgroundJobRepository#countGroupByStatus}.
     * - Result is an {@link EnumMap} keyed by {@link JobStatus}, JSON-serialized as a
     *   plain object {@code {QUEUED: n, PROCESSING: n, ...}} per Jackson's default
     *   handling of enum-keyed maps.
     */
    @GetMapping("/status-summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<JobStatus, Long>> statusSummary() {
        EnumMap<JobStatus, Long> result = new EnumMap<>(JobStatus.class);
        for (JobStatus s : JobStatus.values()) result.put(s, 0L);
        for (Object[] row : jobRepository.countGroupByStatus()) {
            JobStatus status = (JobStatus) row[0];
            Long count = ((Number) row[1]).longValue();
            result.put(status, count);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * @summary Single-atomic manual retry of a FAILED background job. Promotes the
     *     row back to QUEUED so the existing dispatcher picks it up on next poll.
     * @logic
     * - Delegates the transition to
     *   {@link BackgroundJobRepository#atomicRetry(String)} — a single
     *   conditional UPDATE. {@code rowsAffected == 1} means the row was eligible.
     * - {@code rowsAffected == 0} triggers a disambiguating SELECT:
     *   row absent → {@code 404}, row not FAILED (or currently locked) →
     *   {@code 409 {reason:"not_failed"}}, retry cap reached →
     *   {@code 422 {reason:"max_retries"}}. The plan specifies this four-way
     *   mapping so the UI can tailor each toast (H1 resolution).
     * - Each outcome increments
     *   {@code agm.observability.bgjob.retry{outcome=<ok|not_found|not_failed|max_retries>}}.
     */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> retry(@PathVariable("id") String id) {
        int rowsAffected = jobRepository.atomicRetry(id);
        if (rowsAffected == 1) {
            retryOkCounter.increment();
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
        Optional<BackgroundJob> existing = jobRepository.findById(id);
        if (existing.isEmpty()) {
            retryNotFoundCounter.increment();
            return ResponseEntity.notFound().build();
        }
        BackgroundJob job = existing.get();
        if (job.getRetryCount() >= job.getMaxRetries()) {
            retryMaxRetriesCounter.increment();
            return ResponseEntity.status(HttpStatus.valueOf(422))
                    .body(Map.of("reason", "max_retries"));
        }
        // status != FAILED, or locked_at not null; either way this is a 409.
        retryNotFailedCounter.increment();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("reason", "not_failed"));
    }

    /**
     * @summary Returns whether the background job queue is administratively paused.
     * @logic Reads the cached {@link JobQueueAdminState#isPaused()} flag (refreshed
     *        from {@code app_settings.job_queue.paused} on startup and on every
     *        pause/resume call). Cheap — no DB hit on the read path.
     */
    @GetMapping("/pause-state")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Boolean>> getPauseState() {
        return ResponseEntity.ok(Map.of("paused", adminState.isPaused()));
    }

    /**
     * @summary Pauses the background job queue. {@code PersistentJobQueueService.processQueue}
     *          short-circuits while paused (T051 anti-pattern G-A5), so QUEUED rows stop being
     *          claimed within one poll cycle (~30s).
     * @logic Idempotent — POSTing pause while already paused is a 204 no-op. Flag is persisted
     *        to {@code app_settings} so a JVM restart preserves the paused state (and logs a
     *        WARN at startup if so).
     */
    @PostMapping("/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> pause() {
        adminState.setPaused(true);
        systemAuditService.record(
                CallerContext.resolveCallerOrgId(),
                CallerContext.resolveCallerUsername(),
                "JOB_QUEUE_PAUSE",
                "JOB_QUEUE",
                "global",
                "POST",
                "/api/v1/observability/background-jobs/pause",
                204);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Resumes the background job queue after a {@link #pause}.
     * @logic Idempotent — POSTing resume on an already-active queue is a 204 no-op. Clears
     *        the persisted flag so future restarts see the queue as ACTIVE.
     */
    @PostMapping("/resume")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resume() {
        adminState.setPaused(false);
        systemAuditService.record(
                CallerContext.resolveCallerOrgId(),
                CallerContext.resolveCallerUsername(),
                "JOB_QUEUE_RESUME",
                "JOB_QUEUE",
                "global",
                "POST",
                "/api/v1/observability/background-jobs/resume",
                204);
        return ResponseEntity.noContent().build();
    }

    private static BackgroundJobResponse toDto(BackgroundJob j) {
        return new BackgroundJobResponse(
                j.getId(),
                j.getAgentId(),
                j.getJobType(),
                j.getStatus(),
                j.getRetryCount(),
                j.getMaxRetries(),
                j.getErrorMessage(),
                j.getPriority(),
                j.getNextRetryAt(),
                j.getCreatedAt(),
                j.getStartedAt(),
                j.getCompletedAt(),
                j.getLockedAt());
    }
}
