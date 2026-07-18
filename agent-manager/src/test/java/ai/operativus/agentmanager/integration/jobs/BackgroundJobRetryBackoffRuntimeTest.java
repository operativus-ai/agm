package ai.operativus.agentmanager.integration.jobs;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.JobHandler;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobPriority;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link PersistentJobQueueService}'s retry-backoff arithmetic
 *   on the outer retry loop. The contract — note retry_count is incremented FIRST and the
 *   backoff uses the post-increment value:
 *   <pre>
 *     nextRetryAt = now() + 30s * 2^(retryCount_after_increment)
 *   </pre>
 *   So a fresh job (retry_count=0) that fails for the first time becomes retry_count=1
 *   and gets next_retry_at = now + 60s. Pinning this prevents an off-by-one (shifting by
 *   the pre-increment value) or a units regression from silently changing the wall-clock
 *   back-pressure on a flaky handler.
 *
 *   <p>Companion to {@code JobQueueRuntimeTest.failingHandlerExhaustsRetriesAndLandsInDlq},
 *   which proves the retry → DLQ path end-to-end but does NOT assert the per-cycle
 *   {@code next_retry_at} value (just that the row terminates in DLQ).
 *
 *   <p>Strategy: per test, seed a QUEUED row at a chosen pre-failure retry_count
 *   (max_retries=3 unless overridden), {@code next_retry_at} null so the poller picks
 *   it up immediately. After one {@link PersistentJobQueueService#processQueue} cycle:
 *   <ul>
 *     <li>rc=0 → 1: requeued, {@code next_retry_at ≈ now + 60s}  (30 * 2^1)</li>
 *     <li>rc=1 → 2: requeued, {@code next_retry_at ≈ now + 120s} (30 * 2^2)</li>
 *     <li>rc=2 → 3 == max_retries: DLQ, {@code next_retry_at} NOT advanced (left null)</li>
 *     <li>rc=3 → 4 with max_retries=5: requeued, {@code next_retry_at ≈ now + 480s}
 *         (30 * 2^4) — pins the doubling explicitly. max_retries=5 keeps the row in
 *         QUEUED; the post-increment rc=4 would hit the DLQ branch at max_retries=4
 *         before the backoff is computed.</li>
 *   </ul>
 *
 * State: Stateless. Handler is registered as a singleton Spring bean — no shared mutable
 *   state in test methods.
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        BackgroundJobRetryBackoffRuntimeTest.AlwaysFailHandlerConfig.class
})
public class BackgroundJobRetryBackoffRuntimeTest extends BaseIntegrationTest {

    private static final String FAIL_TYPE = "J1_BACKOFF_FAIL";
    private static final long BASE_BACKOFF_SECONDS = 30L;
    /**
     * Tolerance must cover (a) clock-skew between {@code before} capture and the actual
     * {@code LocalDateTime.now()} inside executeJob and (b) the inner queueRetryTemplate
     * burning 3 attempts with 1s/2s exponential waits (~3s wall-clock) BEFORE the outer
     * catch block computes next_retry_at. 15s is comfortably above that.
     */
    private static final long TOLERANCE_SECONDS = 15L;

    @Autowired private PersistentJobQueueService queue;
    @Autowired private BackgroundJobRepository repo;
    @Autowired private JobQueueTestSupport jobs;

    // ─── J1.1 — first failure: rc 0 -> 1, nextRetryAt ≈ now + 60s (30 * 2^1) ──

    @Test
    void firstFailedCycle_setsNextRetryAt_sixtySecondsOut() {
        BackgroundJob seeded = seedFailingJob("j1-first-" + UUID.randomUUID(), /*retryCount=*/ 0);

        LocalDateTime before = LocalDateTime.now();
        jobs.processNow();
        BackgroundJob requeued = awaitQueuedAtRetryCount(seeded.getId(), 1);

        long expectedSeconds = BASE_BACKOFF_SECONDS << 1;  // 30 * 2^1 = 60
        assertBackoffApprox(requeued.getNextRetryAt(), before, expectedSeconds,
                "retry_count 0 -> 1 must schedule next_retry_at ≈ now + 60s (30 * 2^1); "
                        + "executeJob increments rc BEFORE computing backoff, so the shift "
                        + "uses the post-increment value");
    }

    // ─── J1.2 — second failure: rc 1 -> 2, nextRetryAt ≈ now + 120s (30 * 2^2) ──

    @Test
    void secondFailedCycle_setsNextRetryAt_oneTwentySecondsOut() {
        BackgroundJob seeded = seedFailingJob("j1-second-" + UUID.randomUUID(), /*retryCount=*/ 1);

        LocalDateTime before = LocalDateTime.now();
        jobs.processNow();
        BackgroundJob requeued = awaitQueuedAtRetryCount(seeded.getId(), 2);

        long expectedSeconds = BASE_BACKOFF_SECONDS << 2;  // 30 * 2^2 = 120
        assertBackoffApprox(requeued.getNextRetryAt(), before, expectedSeconds,
                "retry_count 1 -> 2 must schedule next_retry_at ≈ now + 120s (30 * 2^2)");
    }

    // ─── J1.3 — third failure terminal (rc 2 -> 3 == max_retries): DLQ, no advance ──

    @Test
    void thirdFailedCycle_atMaxRetries_movesToDlq_andDoesNotAdvanceNextRetryAt() {
        BackgroundJob seeded = seedFailingJob("j1-third-" + UUID.randomUUID(), /*retryCount=*/ 2);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobFailure(seeded.getId(), Duration.ofSeconds(30));

        assertAll("rc 2 -> 3 with max_retries=3 terminates in DLQ without backoff advance",
                () -> assertEquals(JobStatus.DLQ, terminal.getStatus(),
                        "row MUST land in DLQ — retry_count >= max_retries is the only condition "
                                + "that flips status to DLQ at this point in executeJob"),
                () -> assertEquals(3, terminal.getRetryCount(),
                        "retry_count must increment one final time to 3 even though the row "
                                + "is going to DLQ — the increment is unconditional, the DLQ "
                                + "check happens after"),
                () -> assertNull(terminal.getNextRetryAt(),
                        "next_retry_at must NOT be advanced on the DLQ branch — the backoff "
                                + "scheduling lives only inside the else-branch (not-yet-at-cap). "
                                + "A populated next_retry_at on a DLQ row would be a misleading "
                                + "scheduling hint that something will pick this up again."));
    }

    // ─── J1.4 — exponential growth: rc 3 -> 4 (with max_retries=4) ≈ now + 480s ──

    @Test
    void backoffDoublesAcrossRetryCounts_pinThe2PowerNFormula() {
        // Pins the doubling: rc 3 -> 4 must schedule ≈ now + 480s (30 * 2^4). Each
        // retry the wait must double; an arithmetic regression to 30 * (rc+1) would
        // give 150s here. max_retries=5 keeps the row in QUEUED — at max_retries=4 the
        // post-increment rc=4 hits the DLQ branch before the backoff is computed.
        BackgroundJob seeded = seedFailingJobWithMaxRetries(
                "j1-fourth-" + UUID.randomUUID(), /*retryCount=*/ 3, /*maxRetries=*/ 5);

        LocalDateTime before = LocalDateTime.now();
        jobs.processNow();
        BackgroundJob requeued = awaitQueuedAtRetryCount(seeded.getId(), 4);

        long expectedSeconds = BASE_BACKOFF_SECONDS << 4;  // 30 * 2^4 = 480
        assertBackoffApprox(requeued.getNextRetryAt(), before, expectedSeconds,
                "retry_count 3 -> 4 must schedule next_retry_at ≈ now + 480s (30 * 2^4); "
                        + "an arithmetic regression here (e.g. 30 * (rc+1) = 150s) would "
                        + "silently relax wall-clock back-pressure on flaky handlers");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private BackgroundJob seedFailingJob(String agentId, int retryCount) {
        return seedFailingJobWithMaxRetries(agentId, retryCount, /*maxRetries=*/ 3);
    }

    /**
     * Seeds a QUEUED background_job row with the given retry_count + max_retries, ready
     * for the next {@code processQueue} cycle to pick it up immediately (next_retry_at
     * is null). The job uses the always-failing handler so the cycle increments
     * retry_count + sets next_retry_at per the backoff formula (or DLQs).
     */
    private BackgroundJob seedFailingJobWithMaxRetries(String agentId, int retryCount, int maxRetries) {
        BackgroundJob job = queue.enqueue(FAIL_TYPE, agentId, "payload-" + agentId,
                JobPriority.NORMAL.getValue(), null);
        // Force retry_count + max_retries via direct save — enqueue starts at rc=0/maxRetries=3.
        job.setRetryCount(retryCount);
        job.setMaxRetries(maxRetries);
        job.setNextRetryAt(null);
        return repo.save(job);
    }

    /**
     * Polls the job row until {@code retry_count} reaches the expected value and the row
     * is QUEUED (not still PROCESSING). Required because executeJob runs on a virtual
     * thread launched from processQueue, so the requeue is async.
     */
    private BackgroundJob awaitQueuedAtRetryCount(String jobId, int expectedRetryCount) {
        // 25s window covers worst-case: ~3s inner queueRetryTemplate retries + VT scheduling
        // + DB save latency on a loaded CI box.
        Awaitility.await()
                .atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    BackgroundJob current = repo.findById(jobId).orElseThrow();
                    return current.getStatus() == JobStatus.QUEUED
                            && current.getRetryCount() == expectedRetryCount;
                });
        return repo.findById(jobId).orElseThrow();
    }

    private static void assertBackoffApprox(LocalDateTime nextRetryAt, LocalDateTime before,
                                            long expectedSeconds, String message) {
        assertNotNull(nextRetryAt, message + " — next_retry_at was null");
        long deltaSeconds = java.time.Duration.between(before, nextRetryAt).getSeconds();
        long lower = expectedSeconds - TOLERANCE_SECONDS;
        long upper = expectedSeconds + TOLERANCE_SECONDS;
        assertTrue(deltaSeconds >= lower && deltaSeconds <= upper,
                message + " — expected ≈ " + expectedSeconds + "s (±" + TOLERANCE_SECONDS
                        + "s for clock skew + inner queueRetryTemplate wait time); "
                        + "got " + deltaSeconds + "s");
    }

    /** Always-throws handler registered for the test-only job type. */
    @TestConfiguration
    static class AlwaysFailHandlerConfig {
        @Bean
        JobHandler j1AlwaysFailHandler() {
            return new JobHandler() {
                @Override public String jobType() { return FAIL_TYPE; }
                @Override public void execute(BackgroundJob job) {
                    throw new RuntimeException("J1 synthetic backoff failure for " + job.getId());
                }
            };
        }
    }
}
