package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.control.service.queue.AgentRunJobHandler;
import com.operativus.agentmanager.control.service.queue.JobHandler;
import com.operativus.agentmanager.control.service.queue.JobHandlerRegistry;
import com.operativus.agentmanager.control.service.queue.JobQueueAdminState;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobPriority;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-backed job queue with priority, retry, crash recovery, and job-type dispatch.
 * Polls every 30 seconds for QUEUED jobs, processes up to 5 per cycle using virtual threads.
 * Uses FOR UPDATE SKIP LOCKED for safe multi-pod concurrent polling.
 */
@Service
public class PersistentJobQueueService {

    private static final Logger log = LoggerFactory.getLogger(PersistentJobQueueService.class);
    private static final int MAX_BATCH_SIZE = 5;
    private static final int STALE_PROCESSING_MINUTES = 15;

    private final BackgroundJobRepository jobRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final JobQueueAdminState adminState;
    private final RetryTemplate queueRetryTemplate;

    // Captured SecurityContext per enqueued job, keyed by job id. Populated from the caller's
    // thread at enqueue time and drained by the virtual-thread worker so @PreAuthorize gates
    // on downstream handlers (e.g. AgentAdminService#deleteAgent) resolve against the original
    // Authentication instead of an anonymous worker context. In-memory by design; jobs picked
    // up by another JVM or by this JVM after a crash simply run without a captured context
    // (legacy behavior). Entry lifetime runs from enqueue() until executeJob() reaches a
    // terminal status so that reschedule-driven retries still see the caller's auth.
    private final Map<String, SecurityContext> pendingAuthContexts = new ConcurrentHashMap<>();

    public PersistentJobQueueService(BackgroundJobRepository jobRepository,
                                     JobHandlerRegistry handlerRegistry,
                                     JobQueueAdminState adminState,
                                     MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.handlerRegistry = handlerRegistry;
        this.adminState = adminState;
        // Retry every Exception EXCEPT NonRetryableJobException — a handler throws that to
        // signal a terminal business outcome (e.g. a workflow run that ended FAILED), where
        // re-running would re-execute side effects. The RetryTemplateBuilder forbids mixing
        // retryOn + notRetryOn, so the policy is assembled by hand. traverseCauses=true so a
        // wrapped marker is still classified as non-retryable. Map order: most-specific first.
        java.util.Map<Class<? extends Throwable>, Boolean> retryable = new java.util.LinkedHashMap<>();
        retryable.put(com.operativus.agentmanager.control.service.queue.NonRetryableJobException.class, false);
        retryable.put(Exception.class, true);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryable, true);
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(10000);
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOff);
        this.queueRetryTemplate = template;

        // Gauge bindings are strong-referenced to the owning bean; Micrometer calls the supplier
        // on each scrape so depth/dlq numbers are always fresh against the DB.
        Gauge.builder("background_jobs.queued", this, s -> s.getQueueDepth())
                .description("Number of BackgroundJob rows currently in QUEUED status")
                .register(meterRegistry);
        Gauge.builder("background_jobs.dlq", this, s -> s.getDlqCount())
                .description("Number of BackgroundJob rows currently in DLQ status")
                .register(meterRegistry);
    }

    /**
     * Recovers jobs stuck in PROCESSING state after a JVM crash (locked_at older than threshold).
     * Runs at startup ({@code @PostConstruct}) to catch abandoned rows from a previous JVM, and
     * again on a fixed schedule so a surviving node in a cluster picks up rows abandoned by a
     * crashed peer without waiting for a full cluster restart. Cadence defaults to 5 min, which
     * is 1/3 of {@link #STALE_PROCESSING_MINUTES} so at worst a stalled job sits ~20 min before
     * being reclaimed.
     */
    @PostConstruct
    @Scheduled(fixedRateString = "${agentmanager.scheduler.stalled-recovery-ms:300000}")
    @Transactional
    public void recoverStalledJobs() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(STALE_PROCESSING_MINUTES);
        List<BackgroundJob> stalled = jobRepository.findStalledProcessingJobs(staleThreshold);
        if (stalled.isEmpty()) return;

        log.warn("Recovering {} stalled PROCESSING jobs", stalled.size());
        stalled.forEach(job -> {
            log.warn("Recovering stalled job: {} (locked_at={})", job.getId(), job.getLockedAt());
            job.setStatus(JobStatus.QUEUED);
            job.setLockedAt(null);
        });
        jobRepository.saveAll(stalled);
    }

    /**
     * Enqueues a new background job. Supports optional job-key deduplication.
     * Returns the existing active job if a duplicate job_key is found.
     */
    public BackgroundJob enqueue(String jobType, String agentId, String payload, String priority, String jobKey) {
        if (jobKey != null) {
            Optional<BackgroundJob> existing = jobRepository.findActiveByJobKey(jobKey);
            if (existing.isPresent()) {
                log.info("Returning existing active job {} for key {}", existing.get().getId(), jobKey);
                return existing.get();
            }
        }

        BackgroundJob job = new BackgroundJob(UUID.randomUUID().toString(), agentId, jobType, payload);
        job.setPriority(priority != null ? priority : JobPriority.NORMAL.getValue());
        job.setJobKey(jobKey);
        jobRepository.save(job);
        captureAuthContext(job.getId());
        log.info("Job enqueued: {} type={} agentId={} priority={}", job.getId(), jobType, agentId, job.getPriority());
        return job;
    }

    private void captureAuthContext(String jobId) {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        pendingAuthContexts.put(jobId, context);
    }

    /**
     * Backwards-compatible overload for existing AGENT_RUN callers.
     */
    public BackgroundJob enqueue(String agentId, String payload, String priority) {
        return enqueue(AgentRunJobHandler.JOB_TYPE, agentId, payload, priority, null);
    }

    /**
     * Polls for ready jobs and processes them. Runs every 30 seconds.
     * Short-circuits when the queue is administratively paused (G-A5).
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.batch-poll-ms:30000}")
    @Transactional
    public void processQueue() {
        if (adminState.isPaused()) {
            log.debug("Job queue is paused — skipping poll cycle");
            return;
        }
        List<BackgroundJob> readyJobs = jobRepository.findReadyJobs(LocalDateTime.now(), MAX_BATCH_SIZE);
        if (readyJobs.isEmpty()) return;

        log.debug("Processing {} queued jobs", readyJobs.size());
        for (BackgroundJob job : readyJobs) {
            job.setStatus(JobStatus.PROCESSING);
            job.setStartedAt(LocalDateTime.now());
            jobRepository.save(job);

            Thread.ofVirtual().name("job-" + job.getId()).start(() -> executeJob(job));
        }
    }

    private void executeJob(BackgroundJob job) {
        SecurityContext capturedContext = pendingAuthContexts.get(job.getId());
        if (capturedContext != null) {
            SecurityContextHolder.setContext(capturedContext);
        }
        boolean terminal = false;
        try {
            job.setLockedAt(LocalDateTime.now());
            jobRepository.save(job);

            JobHandler handler = handlerRegistry.get(job.getJobType());
            queueRetryTemplate.execute(ctx -> {
                handler.execute(job);
                return null;
            });

            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            job.setJobKey(null);
            jobRepository.save(job);
            terminal = true;
            log.info("Job completed: {} type={}", job.getId(), job.getJobType());
        } catch (Exception e) {
            log.warn("Job failed: {} type={} (attempt {}/{}): {}",
                    job.getId(), job.getJobType(), job.getRetryCount() + 1, job.getMaxRetries(), e.getMessage());
            job.setRetryCount(job.getRetryCount() + 1);
            job.setErrorMessage(e.getMessage());

            boolean nonRetryable = e instanceof com.operativus.agentmanager.control.service.queue.NonRetryableJobException;
            if (nonRetryable) {
                // Terminal business outcome — do not re-queue (would re-execute side effects).
                job.setStatus(JobStatus.DLQ);
                terminal = true;
                log.error("Job moved to DLQ (non-retryable failure): {} type={}: {}",
                        job.getId(), job.getJobType(), e.getMessage());
            } else if (job.getRetryCount() >= job.getMaxRetries()) {
                job.setStatus(JobStatus.DLQ);
                terminal = true;
                log.error("Job moved to DLQ after {} retries: {}", job.getMaxRetries(), job.getId());
            } else {
                job.setStatus(JobStatus.QUEUED);
                job.setNextRetryAt(LocalDateTime.now().plusSeconds(30L * (1L << job.getRetryCount())));
            }
            jobRepository.save(job);
        } finally {
            SecurityContextHolder.clearContext();
            if (terminal) {
                pendingAuthContexts.remove(job.getId());
            }
        }
    }

    public long getQueueDepth() {
        return jobRepository.countByStatus(JobStatus.QUEUED);
    }

    public long getDlqCount() {
        return jobRepository.countByStatus(JobStatus.DLQ);
    }
}
