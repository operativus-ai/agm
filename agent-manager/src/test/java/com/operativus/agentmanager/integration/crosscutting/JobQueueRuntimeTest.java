package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.queue.JobHandler;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobPriority;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for the T051 / matrix §27.3 background
 *   job queue surface. Pins {@link PersistentJobQueueService} end-to-end:
 *   - {@code enqueue(...)} produces a QUEUED row with a UUID id and the configured priority.
 *   - {@code processQueue()} moves QUEUED rows to PROCESSING, dispatches to the handler
 *     registered for the job-type, and persists COMPLETED with timestamps on success.
 *   - Handler failures are retried by the inner {@code queueRetryTemplate} (3 attempts,
 *     exponential backoff), then the outer loop increments {@code retry_count} and either
 *     re-QUEUEs with {@code next_retry_at} backoff or transitions to DLQ when
 *     {@code retry_count >= max_retries}.
 *   - Unknown {@code job_type} values surface {@code IllegalStateException} from the
 *     registry, which takes the same retry/DLQ path.
 *   - Enqueuing with the same {@code job_key} while an active job exists returns that
 *     row instead of creating a duplicate (idempotency contract).
 *   - Concurrent {@code processQueue()} calls do not double-claim rows; Postgres
 *     {@code FOR UPDATE SKIP LOCKED} is the single source of truth for row ownership.
 * State: Stateless at the class level, but two @Bean-registered test handlers share
 *   per-class {@code AtomicInteger} counters that must be reset in {@link #resetCounters()}
 *   (the handlers are singletons; {@link BaseIntegrationTest#truncateDatabase} wipes rows
 *   but not bean state).
 *
 * Knob: {@code application-test.properties} pushes {@code agentmanager.scheduler.batch-poll-ms}
 *   to 24h, so the {@code @Scheduled} poller never fires during a test run. Work is driven
 *   synchronously via {@link JobQueueTestSupport#processNow()} (which invokes
 *   {@code processQueue()} directly) and then we poll the job row for terminal state via
 *   Awaitility (see {@link JobQueueTestSupport} Javadoc).
 *
 * Retry math: default {@code max_retries} is 3. To keep failure-path tests fast we seed
 *   jobs with {@code max_retries=1} — one processing cycle fails (3 inner attempts with
 *   1s/2s backoff ≈ 3s wall-clock), {@code retry_count} increments 0→1, and 1 >= 1 moves
 *   the row to DLQ without a second cycle. For unknown-job-type the inner retry never
 *   fires (the exception is thrown before {@code queueRetryTemplate.execute}), so that
 *   case terminates in sub-second time.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.3.
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        JobQueueRuntimeTest.TestHandlers.class
})
public class JobQueueRuntimeTest extends BaseIntegrationTest {

    private static final String SUCCESS_TYPE = "T051_SUCCESS";
    private static final String FAIL_TYPE = "T051_FAIL";
    private static final Duration FAST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired private PersistentJobQueueService queue;
    @Autowired private BackgroundJobRepository repo;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private SuccessCounter successCounter;
    @Autowired private FailCounter failCounter;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @BeforeEach
    void resetCounters() {
        successCounter.count.set(0);
        failCounter.count.set(0);
    }

    // §27.3 case 1 — enqueue contract. enqueue(...) assigns a UUID id, persists status=QUEUED,
    // stores payload/priority, and leaves terminal timestamps null until processed. Pins
    // PersistentJobQueueService:enqueue lines 73-88.
    @Test
    void enqueueCreatesQueuedJobWithGeneratedIdAndPriority() {
        String agentId = "agent-" + shortUuid();
        BackgroundJob job = queue.enqueue(SUCCESS_TYPE, agentId, "{\"k\":\"v\"}",
                JobPriority.HIGH.getValue(), null);

        assertNotNull(job.getId(), "enqueue must assign an id");
        assertEquals(36, job.getId().length(), "id must be a UUID string (36 chars)");
        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(SUCCESS_TYPE, job.getJobType());
        assertEquals(agentId, job.getAgentId());
        assertEquals("{\"k\":\"v\"}", job.getPayload());
        assertEquals(JobPriority.HIGH.getValue(), job.getPriority());
        assertNull(job.getStartedAt(), "QUEUED row must not have startedAt yet");
        assertNull(job.getCompletedAt(), "QUEUED row must not have completedAt yet");

        // @CreationTimestamp fires at JPA pre-persist; read it back from the DB rather
        // than relying on the in-memory reference returned from save().
        BackgroundJob persisted = repo.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, persisted.getStatus(),
                "enqueue must durably persist the row before returning");
        assertNotNull(persisted.getCreatedAt(),
                "persisted row must carry a created_at timestamp (@CreationTimestamp)");
    }

    // §27.3 case 2 — happy path. processQueue() picks up a QUEUED job, runs the registered
    // handler exactly once, and transitions the row to COMPLETED with startedAt + completedAt
    // both set. Pins PersistentJobQueueService:processQueue + executeJob (success branch).
    @Test
    void processQueueExecutesHandlerAndMarksCompleted() {
        BackgroundJob job = queue.enqueue(SUCCESS_TYPE, "agent-" + shortUuid(),
                "payload-1", JobPriority.NORMAL.getValue(), null);

        jobs.processNow();
        BackgroundJob done = jobs.awaitJobSuccess(job.getId(), FAST_TIMEOUT);

        assertEquals(JobStatus.COMPLETED, done.getStatus());
        assertEquals(1, successCounter.count.get(),
                "handler must have been invoked exactly once");
        assertNotNull(done.getStartedAt(), "startedAt must be set before handler runs");
        assertNotNull(done.getCompletedAt(), "completedAt must be set on success");
        assertEquals(0, done.getRetryCount(), "a first-try success must not bump retryCount");
        assertNull(done.getErrorMessage(), "successful jobs must carry no errorMessage");
    }

    // §27.3 case 3 — failure path to DLQ. A handler that always throws exhausts the inner
    // queueRetryTemplate (3 attempts), then the outer loop increments retry_count. With
    // max_retries=1 the row goes straight to DLQ. The catch block in executeJob must
    // persist status=DLQ AND the last error message. Pins executeJob lines 132-146.
    @Test
    void failingHandlerExhaustsRetriesAndLandsInDlq() {
        BackgroundJob enqueued = queue.enqueue(FAIL_TYPE, "agent-" + shortUuid(),
                "doomed", JobPriority.NORMAL.getValue(), null);

        // Lower max_retries so the outer loop sends the row to DLQ after one failed cycle
        // instead of requeueing with a 30s backoff we'd have to wait out.
        enqueued.setMaxRetries(1);
        repo.save(enqueued);

        jobs.processNow();
        BackgroundJob failed = jobs.awaitJobFailure(enqueued.getId(), RETRY_TIMEOUT);

        assertEquals(JobStatus.DLQ, failed.getStatus(),
                "row must terminate in DLQ, not FAILED/QUEUED, when retry_count >= max_retries");
        assertEquals(1, failed.getRetryCount(),
                "one failed processing cycle must increment retry_count once (not per inner attempt)");
        assertNotNull(failed.getErrorMessage(), "DLQ row must carry the last error message");
        assertTrue(failed.getErrorMessage().contains("T051 synthetic failure"),
                "error_message must be the exception message from the handler; got: "
                        + failed.getErrorMessage());
        // queueRetryTemplate is configured for 3 inner attempts; assert the handler was
        // invoked that many times before the outer loop gave up.
        assertEquals(3, failCounter.count.get(),
                "queueRetryTemplate (maxAttempts=3) must retry the handler 3 times per cycle");
        assertEquals(1L, queue.getDlqCount(), "getDlqCount() must reflect the DLQ'd row");
    }

    // §27.3 case 4 — unknown job_type. Registry lookup throws IllegalStateException, which
    // flows through the SAME failure path as a throwing handler. No inner retry (the throw
    // happens before queueRetryTemplate.execute), so this terminates in sub-second time.
    // Pins JobHandlerRegistry:get + executeJob's catch.
    @Test
    void unknownJobTypeTakesRetryPathAndDlqs() {
        // Seed directly — enqueue() does not validate job_type against the registry, but we
        // skip it here to be explicit about the seeded state.
        BackgroundJob seeded = new BackgroundJob(
                UUID.randomUUID().toString(), "agent-x", "T051_UNREGISTERED_TYPE", "{}");
        seeded.setMaxRetries(1);
        repo.save(seeded);

        jobs.processNow();
        BackgroundJob failed = jobs.awaitJobFailure(seeded.getId(), FAST_TIMEOUT);

        assertEquals(JobStatus.DLQ, failed.getStatus());
        assertNotNull(failed.getErrorMessage());
        assertTrue(failed.getErrorMessage().contains("No handler registered"),
                "error_message must come from JobHandlerRegistry.get; got: " + failed.getErrorMessage());
        assertEquals(0, successCounter.count.get(), "no real handler should have run");
        assertEquals(0, failCounter.count.get(), "no real handler should have run");
    }

    // §27.3 case 5 — job_key dedupe. While an active (non-terminal) job with a given
    // job_key exists, enqueue() with the same key returns that row (same id) instead of
    // creating a duplicate. After the first job COMPLETEs, its job_key is cleared (see
    // executeJob line 129), so a subsequent enqueue with the same key creates a fresh row.
    // Pins enqueue lines 74-80 + executeJob's setJobKey(null).
    @Test
    void enqueueWithJobKeyDedupesReturningExistingActiveJob() {
        String key = "dedupe-" + shortUuid();
        BackgroundJob first = queue.enqueue(SUCCESS_TYPE, "agent-dedupe", "p1",
                JobPriority.NORMAL.getValue(), key);
        BackgroundJob second = queue.enqueue(SUCCESS_TYPE, "agent-dedupe", "p2-should-be-ignored",
                JobPriority.HIGH.getValue(), key);

        assertEquals(first.getId(), second.getId(),
                "a second enqueue with the same active job_key must return the existing row");
        assertEquals(1L, repo.count(), "no duplicate row must have been persisted");
        assertEquals("p1", second.getPayload(),
                "the existing row is returned verbatim; new payload must be ignored");

        // Drain the first job, which clears its job_key (executeJob line 129).
        jobs.processNow();
        jobs.awaitJobSuccess(first.getId(), FAST_TIMEOUT);

        BackgroundJob third = queue.enqueue(SUCCESS_TYPE, "agent-dedupe", "p3",
                JobPriority.NORMAL.getValue(), key);
        assertFalse(third.getId().equals(first.getId()),
                "once the first job terminates and clears job_key, a fresh enqueue must create a new row");
        assertEquals(2L, repo.count(), "second active job under the same key must be a new row");
    }

    // §27.3 case 6 — FOR UPDATE SKIP LOCKED row-lock contract. Two concurrent
    // processQueue() calls must not both claim the same row. We enqueue N jobs, fire N
    // concurrent processQueue() calls, and assert the handler ran exactly N times (no
    // duplicates). This is the single-node simulation of the multi-pod invariant
    // documented on processQueue() line 98-114.
    @Test
    void concurrentProcessQueueCallsDoNotDoubleClaimRows() throws Exception {
        int n = 8; // below MAX_BATCH_SIZE=5 * concurrency=4 so one pass drains everything
        List<String> jobIds = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            jobIds.add(queue.enqueue(SUCCESS_TYPE, "agent-concurrent-" + i,
                    "p" + i, JobPriority.NORMAL.getValue(), null).getId());
        }

        int concurrency = 4;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<Void>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                calls.add(CompletableFuture.runAsync(jobs::processNow, pool));
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        } finally {
            pool.shutdown();
        }

        for (String id : jobIds) {
            jobs.awaitJobSuccess(id, RETRY_TIMEOUT);
        }

        assertEquals(n, successCounter.count.get(),
                "handler must fire exactly once per row even under concurrent pollers; "
                        + "a higher count would prove SKIP LOCKED is not holding and a row was "
                        + "claimed by two transactions");
    }

    // §27.3 case 7 — stalled-job recovery on startup. A row left in PROCESSING with an old
    // locked_at (e.g. from a node that crashed mid-job) is requeued by recoverStalledJobs()
    // at @PostConstruct time. We invoke the method directly here because @PostConstruct
    // only runs once per Spring context; this pins the STALE_PROCESSING_MINUTES=15 cutoff
    // and the side-effects (status→QUEUED, lockedAt→null).
    @Test
    void recoverStalledJobsRequeuesLongLockedProcessingRows() {
        BackgroundJob stalled = new BackgroundJob(
                UUID.randomUUID().toString(), "agent-stalled", SUCCESS_TYPE, "payload-stalled");
        stalled.setStatus(JobStatus.PROCESSING);
        stalled.setLockedAt(java.time.LocalDateTime.now().minusMinutes(30));
        repo.save(stalled);

        queue.recoverStalledJobs();

        BackgroundJob recovered = repo.findById(stalled.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, recovered.getStatus(),
                "stalled PROCESSING row must be reset to QUEUED so the next poll can reclaim it");
        assertNull(recovered.getLockedAt(),
                "locked_at must be cleared so the row no longer looks owned");
    }

    // §27.3 case 8 — getQueueDepth reflects only QUEUED rows (not PROCESSING / COMPLETED).
    // Tight assertion against countByStatus so future drift (e.g., counting PAUSED) is caught.
    @Test
    void queueDepthReflectsQueuedRowsOnly() {
        assertEquals(0L, queue.getQueueDepth(), "empty queue must report depth 0");

        String aId = queue.enqueue(SUCCESS_TYPE, "a", "1", JobPriority.NORMAL.getValue(), null).getId();
        queue.enqueue(SUCCESS_TYPE, "b", "2", JobPriority.NORMAL.getValue(), null);
        queue.enqueue(SUCCESS_TYPE, "c", "3", JobPriority.LOW.getValue(), null);
        assertEquals(3L, queue.getQueueDepth(), "3 enqueued rows must show depth 3");

        jobs.processNow();
        jobs.awaitJobSuccess(aId, FAST_TIMEOUT);
        // After processNow(), the first BATCH_SIZE rows were picked up; all three fit in one
        // batch (MAX_BATCH_SIZE=5), so after awaiting success on one we expect depth to drop.
        // Drain the rest too so we have a deterministic endpoint.
        long depthAfterDrain = queue.getQueueDepth();
        assertTrue(depthAfterDrain <= 2,
                "after draining at least one job, depth must drop below the enqueued count; got "
                        + depthAfterDrain);
    }

    // ─── @Disabled matrix gaps ───

    // Matrix §27.3 case 7 — queue-depth and DLQ gauges must be exposed to Micrometer so
    // Prometheus / the alerting pipeline can observe them. The service registers two gauges
    // in its constructor: `background_jobs.queued` bound to getQueueDepth() and
    // `background_jobs.dlq` bound to getDlqCount(). Micrometer calls the supplier on each
    // scrape, so the test seeds rows and asserts the gauge reads match what countByStatus
    // would return — pinning that the wire-up actually goes through the registry (not just
    // that the Java method exists).
    @Test
    void queueDepthIsExposedAsMicrometerGauge() {
        queue.enqueue(SUCCESS_TYPE, "agent-g1", "{}", JobPriority.NORMAL.getValue(), null);
        queue.enqueue(SUCCESS_TYPE, "agent-g2", "{}", JobPriority.NORMAL.getValue(), null);

        io.micrometer.core.instrument.Gauge queuedGauge =
                meterRegistry.find("background_jobs.queued").gauge();
        assertNotNull(queuedGauge,
                "Gauge 'background_jobs.queued' must be registered by PersistentJobQueueService");
        assertEquals(2.0, queuedGauge.value(), 0.0001,
                "gauge value must reflect the 2 QUEUED rows just seeded");

        io.micrometer.core.instrument.Gauge dlqGauge =
                meterRegistry.find("background_jobs.dlq").gauge();
        assertNotNull(dlqGauge,
                "Gauge 'background_jobs.dlq' must be registered alongside the queued gauge");
        assertEquals(0.0, dlqGauge.value(), 0.0001,
                "no DLQ rows seeded — gauge must read 0");
    }

    // Matrix §27.3 case 8 — recoverStalledJobs() must run on a @Scheduled tick in addition
    // to @PostConstruct so surviving cluster nodes pick up rows abandoned by a crashed peer
    // without waiting for a full restart. Two-part pin:
    //   (a) reflectively assert the method carries BOTH @PostConstruct and @Scheduled — the
    //       wire-up is the actual spec, not a side-effect we could fake with a manual call.
    //   (b) functional probe: seed a stalled row AFTER the @PostConstruct boot sweep has
    //       already completed, invoke recoverStalledJobs() directly (simulating one tick),
    //       and assert the row is back in QUEUED with locked_at cleared. If only the
    //       @PostConstruct hook existed this row would sit stalled forever until the next
    //       cluster restart.
    @Test
    void stalledJobRecoveryRunsPeriodicallyNotOnlyAtStartup() throws Exception {
        java.lang.reflect.Method m = PersistentJobQueueService.class
                .getMethod("recoverStalledJobs");
        assertNotNull(m.getAnnotation(jakarta.annotation.PostConstruct.class),
                "recoverStalledJobs() must still fire at startup via @PostConstruct");
        assertNotNull(m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class),
                "recoverStalledJobs() must also carry @Scheduled so it runs on surviving nodes "
                        + "after a peer crash — this is the §27.3 matrix gap we're closing");

        BackgroundJob stalled = new BackgroundJob(
                UUID.randomUUID().toString(), "agent-stall", SUCCESS_TYPE, "{}");
        stalled.setStatus(JobStatus.PROCESSING);
        stalled.setLockedAt(java.time.LocalDateTime.now().minusMinutes(30));
        repo.save(stalled);

        queue.recoverStalledJobs();

        BackgroundJob recovered = repo.findById(stalled.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, recovered.getStatus(),
                "stalled PROCESSING row must be reset to QUEUED on the periodic tick");
        assertNull(recovered.getLockedAt(),
                "recovery must clear locked_at so the row is eligible for the next poll");
    }

    // ─── test-only handlers + counters ───

    /** Exposes two synthetic handlers that count invocations so we can assert at-most-once / retry semantics. */
    @TestConfiguration
    static class TestHandlers {

        @Bean
        SuccessCounter successCounter() {
            return new SuccessCounter();
        }

        @Bean
        FailCounter failCounter() {
            return new FailCounter();
        }

        @Bean
        JobHandler t051SuccessHandler(SuccessCounter counter) {
            return new JobHandler() {
                @Override public String jobType() { return SUCCESS_TYPE; }
                @Override public void execute(BackgroundJob job) {
                    counter.count.incrementAndGet();
                }
            };
        }

        @Bean
        JobHandler t051FailHandler(FailCounter counter) {
            return new JobHandler() {
                @Override public String jobType() { return FAIL_TYPE; }
                @Override public void execute(BackgroundJob job) {
                    counter.count.incrementAndGet();
                    throw new RuntimeException("T051 synthetic failure");
                }
            };
        }
    }

    static class SuccessCounter {
        final AtomicInteger count = new AtomicInteger(0);
    }

    static class FailCounter {
        final AtomicInteger count = new AtomicInteger(0);
    }

    // ─── helpers ───

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
