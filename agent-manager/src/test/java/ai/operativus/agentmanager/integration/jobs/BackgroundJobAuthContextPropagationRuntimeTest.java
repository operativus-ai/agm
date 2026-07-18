package ai.operativus.agentmanager.integration.jobs;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.JobHandler;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobPriority;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pins {@link PersistentJobQueueService}'s SecurityContext
 *   propagation across the virtual-thread worker boundary, plus the
 *   {@code pendingAuthContexts} cleanup contract.
 *
 *   <p>The contract under test:
 *   <ul>
 *     <li>{@code captureAuthContext} at enqueue time stores the caller's
 *         {@code SecurityContext} keyed by job id — but only when an authenticated
 *         {@code Authentication} is bound (null or anonymous returns early).</li>
 *     <li>{@code executeJob} on the worker thread restores the captured context before
 *         calling the handler so any downstream {@code @PreAuthorize} gate sees the
 *         caller's principal, not an anonymous worker context.</li>
 *     <li>{@code pendingAuthContexts.remove(jobId)} fires in the {@code finally} block
 *         only when the job reaches a terminal status (COMPLETED or DLQ). A retry
 *         (non-terminal QUEUED) MUST retain the entry so the next cycle's worker still
 *         sees the caller's principal.</li>
 *   </ul>
 *
 *   <p>Handler observes the captured principal via a {@code CapturingHandler} that records
 *   {@code SecurityContextHolder.getContext().getAuthentication()} at execute time, plus
 *   an internal-state inspection of the private {@code pendingAuthContexts} map via
 *   reflection for the cleanup pins (no public observable surface for map size).
 *
 * State: Stateless at class level. Per-test reset of the capturing handler's recorded
 *   principals (singleton bean, survives between tests).
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        BackgroundJobAuthContextPropagationRuntimeTest.CapturingHandlerConfig.class
})
public class BackgroundJobAuthContextPropagationRuntimeTest extends BaseIntegrationTest {

    private static final String SUCCESS_TYPE = "J3_AUTH_SUCCESS";
    private static final String FAIL_TYPE = "J3_AUTH_FAIL";
    private static final Duration FAST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired private PersistentJobQueueService queue;
    @Autowired private BackgroundJobRepository repo;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private CapturingHandler captureSuccess;
    @Autowired private CapturingFailHandler captureFail;

    @BeforeEach
    void resetHandlers() {
        captureSuccess.observed.clear();
        captureFail.observed.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── AC1 — authenticated enqueue: handler sees caller principal ─────────

    @Test
    void enqueueAuthenticated_handlerSeesCallerPrincipal_onExecution() {
        String username = "j3-ac1-" + UUID.randomUUID().toString().substring(0, 8);
        setAuthenticated(username);

        BackgroundJob enqueued = queue.enqueue(SUCCESS_TYPE, "agent-ac1", "payload-ac1",
                JobPriority.NORMAL.getValue(), null);

        jobs.processNow();
        jobs.awaitJobSuccess(enqueued.getId(), FAST_TIMEOUT);

        List<String> observed = captureSuccess.observed.get(enqueued.getId());
        assertAll("authenticated enqueue propagates SecurityContext to worker",
                () -> assertNotNull(observed,
                        "handler must have recorded an execution for the job id"),
                () -> assertEquals(1, observed.size(),
                        "handler must have executed exactly once on success path"),
                () -> assertEquals(username, observed.get(0),
                        "worker thread's SecurityContextHolder MUST carry the caller's "
                                + "username — without this, downstream @PreAuthorize gates "
                                + "(AgentAdminService etc.) would reject the call as anonymous"));
    }

    // ─── AC2 — anonymous enqueue: handler sees no auth ──────────────────────

    @Test
    void enqueueAnonymous_handlerSeesNoAuth_onExecution() {
        // SecurityContextHolder is already cleared by @BeforeEach.
        BackgroundJob enqueued = queue.enqueue(SUCCESS_TYPE, "agent-ac2", "payload-ac2",
                JobPriority.NORMAL.getValue(), null);

        jobs.processNow();
        jobs.awaitJobSuccess(enqueued.getId(), FAST_TIMEOUT);

        List<String> observed = captureSuccess.observed.get(enqueued.getId());
        assertAll("anonymous enqueue does NOT propagate any SecurityContext",
                () -> assertNotNull(observed),
                () -> assertEquals(1, observed.size()),
                () -> assertEquals(CapturingHandler.NO_AUTH_SENTINEL, observed.get(0),
                        "captureAuthContext early-returns on null/unauthenticated, and the "
                                + "worker's SecurityContextHolder defaults to no auth — handler "
                                + "must observe the sentinel, not a leaked principal from another "
                                + "thread"));
    }

    // ─── AC3 — retry retains the captured context across cycles ─────────────

    @Test
    void enqueueAuthenticated_failingHandler_retainsAuthContextAcrossRetries() {
        String username = "j3-ac3-" + UUID.randomUUID().toString().substring(0, 8);
        setAuthenticated(username);

        // Enqueue a failing job with max_retries=2 so we observe TWO executions:
        // first cycle increments rc to 1 (next_retry_at +60s), second cycle increments
        // to 2 and DLQs. To avoid the 60s wait, manually clear next_retry_at between
        // cycles.
        BackgroundJob enqueued = queue.enqueue(FAIL_TYPE, "agent-ac3", "payload-ac3",
                JobPriority.NORMAL.getValue(), null);
        enqueued.setMaxRetries(2);
        repo.save(enqueued);

        // Cycle 1
        jobs.processNow();
        // Wait for the cycle-1 worker to (a) record the principal AND (b) finish
        // saving status=QUEUED + next_retry_at — otherwise our subsequent UPDATE
        // races with the worker's terminal save.
        org.awaitility.Awaitility.await()
                .atMost(RETRY_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    if (captureFail.observed.getOrDefault(enqueued.getId(), List.of()).size() < 1) return false;
                    BackgroundJob current = repo.findById(enqueued.getId()).orElseThrow();
                    return current.getStatus().name().equals("QUEUED")
                            && current.getRetryCount() == 1
                            && current.getNextRetryAt() != null;
                });

        // Force next_retry_at to past so cycle 2 picks it up immediately.
        jdbc.update("UPDATE background_jobs SET next_retry_at = NOW() - INTERVAL '1 second' WHERE id = ?",
                enqueued.getId());

        // Cycle 2 (final, DLQs)
        jobs.processNow();
        jobs.awaitJobFailure(enqueued.getId(), RETRY_TIMEOUT);

        List<String> observed = captureFail.observed.get(enqueued.getId());
        // Each outer cycle runs the handler 3 times via queueRetryTemplate (maxAttempts=3).
        // Two outer cycles -> 6 total invocations. The contract pinned here is that EVERY
        // invocation across BOTH cycles sees the caller's principal — proving the
        // pendingAuthContexts entry is retained across the cycle boundary, not cleaned
        // up on the non-terminal QUEUED save between cycles.
        assertAll("retry retains the captured SecurityContext across cycle boundary",
                () -> assertNotNull(observed),
                () -> assertEquals(6, observed.size(),
                        "expected 6 invocations (2 outer cycles * 3 inner queueRetryTemplate "
                                + "attempts); got " + observed.size() + ": " + observed),
                () -> assertEquals(List.of(username, username, username, username, username, username),
                        observed,
                        "EVERY invocation across both cycles MUST see the caller's principal "
                                + "— a sentinel here would indicate pendingAuthContexts was "
                                + "prematurely cleaned up at the cycle boundary, breaking "
                                + "@PreAuthorize gates on retried jobs"));
    }

    // ─── AC4 — terminal COMPLETED cleans up pendingAuthContexts entry ───────

    @Test
    void successfulCompletion_removesEntryFromPendingAuthContexts() throws Exception {
        String username = "j3-ac4-" + UUID.randomUUID().toString().substring(0, 8);
        setAuthenticated(username);

        BackgroundJob enqueued = queue.enqueue(SUCCESS_TYPE, "agent-ac4", "payload-ac4",
                JobPriority.NORMAL.getValue(), null);

        // Pre-state: entry must be present (auth was bound at enqueue)
        Map<String, SecurityContext> map = reflectPendingAuthContexts();
        assertNotNull(map.get(enqueued.getId()),
                "captureAuthContext must have populated the map at enqueue time");

        jobs.processNow();
        jobs.awaitJobSuccess(enqueued.getId(), FAST_TIMEOUT);

        // Tiny settle window for the finally block in executeJob to remove the entry
        // after the QUEUED -> COMPLETED save commits. Cleanup happens on the worker
        // thread immediately after the save, but the awaitJobSuccess wait is keyed on
        // the row's status — the map remove can lag by microseconds.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> reflectPendingAuthContexts().get(enqueued.getId()) == null);

        assertNull(reflectPendingAuthContexts().get(enqueued.getId()),
                "pendingAuthContexts entry MUST be removed on terminal COMPLETED — leaving "
                        + "it would grow the map unboundedly across the JVM lifetime");
    }

    // ─── AC5 — terminal DLQ cleans up pendingAuthContexts entry ─────────────

    @Test
    void dlqTransition_removesEntryFromPendingAuthContexts() throws Exception {
        String username = "j3-ac5-" + UUID.randomUUID().toString().substring(0, 8);
        setAuthenticated(username);

        BackgroundJob enqueued = queue.enqueue(FAIL_TYPE, "agent-ac5", "payload-ac5",
                JobPriority.NORMAL.getValue(), null);
        enqueued.setMaxRetries(1);  // one cycle -> DLQ
        repo.save(enqueued);

        Map<String, SecurityContext> map = reflectPendingAuthContexts();
        assertNotNull(map.get(enqueued.getId()),
                "entry must be present at enqueue");

        jobs.processNow();
        jobs.awaitJobFailure(enqueued.getId(), RETRY_TIMEOUT);

        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> reflectPendingAuthContexts().get(enqueued.getId()) == null);

        assertNull(reflectPendingAuthContexts().get(enqueued.getId()),
                "pendingAuthContexts entry MUST be removed on terminal DLQ — the cleanup "
                        + "applies to both terminal branches, not just COMPLETED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void setAuthenticated(String username) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username, "credentials",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Reads the private {@code pendingAuthContexts} map from the queue service via
     * reflection. There is no public accessor — the field's lifecycle is the
     * contract under test for AC4/AC5.
     */
    @SuppressWarnings("unchecked")
    private Map<String, SecurityContext> reflectPendingAuthContexts() throws Exception {
        // queue is a Spring AOP proxy (method-level @Transactional on enqueue triggers
        // CGLIB wrapping). Unwrap to the concrete target so reflection can read the
        // private field.
        Object target = AopUtils.isAopProxy(queue) && queue instanceof Advised advised
                ? advised.getTargetSource().getTarget()
                : queue;
        Field f = PersistentJobQueueService.class.getDeclaredField("pendingAuthContexts");
        f.setAccessible(true);
        return (Map<String, SecurityContext>) f.get(target);
    }

    // ─── Test-only handlers ──────────────────────────────────────────────────

    @TestConfiguration
    static class CapturingHandlerConfig {
        @Bean CapturingHandler captureSuccess() { return new CapturingHandler(); }
        @Bean CapturingFailHandler captureFail() { return new CapturingFailHandler(); }
    }

    /** Records the worker-thread principal name (or NO_AUTH_SENTINEL) per job id, then succeeds. */
    static class CapturingHandler implements JobHandler {
        static final String NO_AUTH_SENTINEL = "<no-auth>";
        final Map<String, List<String>> observed = new ConcurrentHashMap<>();

        @Override public String jobType() { return SUCCESS_TYPE; }

        @Override public void execute(BackgroundJob job) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String principal = (auth == null || !auth.isAuthenticated()) ? NO_AUTH_SENTINEL : auth.getName();
            observed.computeIfAbsent(job.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(principal);
        }
    }

    /** Same recording, then throws to drive the retry / DLQ path. */
    static class CapturingFailHandler implements JobHandler {
        final Map<String, List<String>> observed = new ConcurrentHashMap<>();

        @Override public String jobType() { return FAIL_TYPE; }

        @Override public void execute(BackgroundJob job) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String principal = (auth == null || !auth.isAuthenticated())
                    ? CapturingHandler.NO_AUTH_SENTINEL : auth.getName();
            observed.computeIfAbsent(job.getId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(principal);
            throw new RuntimeException("J3 synthetic failure for " + job.getId());
        }
    }
}
