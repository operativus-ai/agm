package com.operativus.agentmanager.integration.jobs;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.queue.JobHandler;
import com.operativus.agentmanager.control.service.queue.JobQueueAdminState;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobPriority;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: End-to-end pin of T051 anti-pattern G-A5: the paused job
 *   queue MUST short-circuit {@code processQueue} so QUEUED rows are not claimed by
 *   the worker until the queue resumes.
 *
 *   <p>Three sites in the codebase reference G-A5 by name (the
 *   {@link JobQueueAdminState} class Javadoc, {@link PersistentJobQueueService#processQueue}
 *   inline comment, and the {@code BackgroundJobController.pause} Javadoc) but no
 *   single test asserts the end-to-end contract. {@link BackgroundJobAdminPauseResumeRuntimeTest}
 *   pins the admin REST surface (state read + persistence + auth). This class pins the
 *   actual behavioral effect — paused queue does not process; resumed queue does.
 *
 *   <p>Test handler counts invocations so a "queue claimed the row anyway" regression
 *   surfaces as a non-zero counter while paused.
 *
 * State: Stateless. The handler is a singleton Spring bean; {@link #resetCounter}
 *   wipes per-test counter state. {@link #restoreActiveState} guarantees the queue
 *   ends each test active so paused state does not leak.
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        BackgroundJobPausedQueueShortCircuitRuntimeTest.CountingHandlerConfig.class
})
public class BackgroundJobPausedQueueShortCircuitRuntimeTest extends BaseIntegrationTest {

    private static final String JOB_TYPE = "J5_PAUSE_PROBE";
    private static final Duration FAST_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private PersistentJobQueueService queue;
    @Autowired private BackgroundJobRepository repo;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private JobQueueAdminState adminState;
    @Autowired private CountingHandler handler;

    @BeforeEach
    void resetCounter() {
        handler.invocations.set(0);
    }

    @AfterEach
    void restoreActiveState() {
        // Defensive: tests after this class share the Spring context. A paused queue
        // leaking through would break JobQueueRuntimeTest's processNow expectations.
        adminState.setPaused(false);
    }

    // ─── PQ1 — paused queue does NOT claim QUEUED rows ──────────────────────

    @Test
    void pausedQueue_processQueueShortCircuits_rowStaysQueued_handlerNeverFires() {
        BackgroundJob enqueued = queue.enqueue(JOB_TYPE, "agent-pq1", "payload-pq1",
                JobPriority.NORMAL.getValue(), null);

        adminState.setPaused(true);
        jobs.processNow();

        // Allow a brief settle window for any (incorrectly-dispatched) worker thread
        // to claim and run before asserting the row stayed QUEUED.
        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        BackgroundJob after = repo.findById(enqueued.getId()).orElseThrow();
        assertAll("paused queue short-circuits processQueue",
                () -> assertEquals(JobStatus.QUEUED, after.getStatus(),
                        "row MUST remain QUEUED while paused — processQueue's adminState.isPaused() "
                                + "check at the top of the method is the only thing blocking the "
                                + "claim; a PROCESSING or COMPLETED row here means the check fired "
                                + "incorrectly or was removed"),
                () -> assertEquals(0, handler.invocations.get(),
                        "handler MUST NOT have been invoked — proves no worker thread was "
                                + "dispatched while paused"),
                () -> assertEquals(null, after.getLockedAt(),
                        "locked_at must stay null — paused queue must not touch row state at all"));
    }

    // ─── PQ2 — resume re-enables claiming, row reaches COMPLETED ────────────

    @Test
    void resumedQueue_processQueueClaimsRow_handlerFiresAndRowCompletes() {
        BackgroundJob enqueued = queue.enqueue(JOB_TYPE, "agent-pq2", "payload-pq2",
                JobPriority.NORMAL.getValue(), null);

        // Pause first, prove no-op, then resume and prove the row finally runs.
        adminState.setPaused(true);
        jobs.processNow();
        try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        assertEquals(JobStatus.QUEUED, repo.findById(enqueued.getId()).orElseThrow().getStatus(),
                "pre-state: paused queue must have left the row QUEUED");
        assertEquals(0, handler.invocations.get(),
                "pre-state: handler must not have been invoked while paused");

        adminState.setPaused(false);
        jobs.processNow();
        BackgroundJob done = jobs.awaitJobSuccess(enqueued.getId(), FAST_TIMEOUT);

        assertAll("resumed queue claims + completes the row",
                () -> assertEquals(JobStatus.COMPLETED, done.getStatus(),
                        "row MUST complete after resume — pre-existing QUEUED rows are NOT "
                                + "drained by a separate flush; they wait for the next processQueue "
                                + "tick which only fires once adminState.isPaused() is false"),
                () -> assertEquals(1, handler.invocations.get(),
                        "handler MUST have been invoked exactly once on the post-resume tick"));
    }

    // ─── Test-only handler ──────────────────────────────────────────────────

    @TestConfiguration
    static class CountingHandlerConfig {
        @Bean CountingHandler pq5CountingHandler() { return new CountingHandler(); }
    }

    /** Counts execute() invocations so pause-short-circuit regressions surface as a non-zero count. */
    static class CountingHandler implements JobHandler {
        final AtomicInteger invocations = new AtomicInteger(0);
        @Override public String jobType() { return JOB_TYPE; }
        @Override public void execute(BackgroundJob job) { invocations.incrementAndGet(); }
    }

    // ─── unused helper to satisfy IDE: see UUID for fixture-id uniqueness ───
    @SuppressWarnings("unused")
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }
}
