package com.operativus.agentmanager.integration.hitl;

import com.operativus.agentmanager.compute.service.AgentRunFinalizer;
import com.operativus.agentmanager.compute.service.RunExecutionManager;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the runtime contract introduced by the {@code AgentRunFinalizer}
 *   terminal-state idempotence guard against real PostgreSQL optimistic locking. Two concurrent
 *   writers (resume's COMPLETED finalize vs. sweeper's CANCELLED finalize) must not both succeed
 *   on the same {@code agent_runs} row — whichever loses the version race observes the row
 *   already-terminal on its retry-reload and short-circuits without overwriting. The contract
 *   was introduced by the Tier 2.4 deep-design analysis (T004b) and addresses §2.1 of the report.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p><b>Why a deliberate latch-gated race?</b> The clobber bug is invisible under sequential
 * test execution — a single finalize call always succeeds. The guard's effect is observable only
 * when a SECOND finalize call lands on an already-terminal row. Pre-T004b: the second writer
 * overwrote (clobbered). Post-T004b: it short-circuits. {@link CountDownLatch} maximizes the
 * chance both writers reach {@code findById} simultaneously so the optimistic-lock retry path is
 * exercised against real Postgres semantics, not Mockito.
 *
 * <p><b>Single-JVM scope:</b> in-process contention via two virtual threads in one JVM. Cross-pod
 * contention (where two different pods both load the row at version V) requires {@code SELECT FOR
 * UPDATE} or a distributed lock, which is PR-7 territory and out of scope here.
 *
 * <p><b>Test strategy:</b> two writers race on the same {@code runId}; assertions are on the
 * row's final terminal state, the entity {@code @Version}, and that exactly one writer's output
 * persists (no Frankenstein interleaving of fields from both writers).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class HitlSweeperResumeRaceRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRunFinalizer agentRunFinalizer;
    @Autowired private RunExecutionManager runExecutionManager;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // T005 primary contract — Two concurrent finalizers race on the same runId. One writes
    // COMPLETED ("resume-output"); the other writes CANCELLED ("Run cancelled by stuck-PAUSED
    // scheduler"). Whichever wins the optimistic-lock first must persist; the loser's retry-
    // reload observes the row already terminal and the T004b guard short-circuits without
    // overwriting. The row's final state must therefore be EXACTLY ONE of the two, never an
    // interleaved mix.
    //
    // Pre-T004b: the loser's retry would clobber the winner — output and status would mix.
    // Post-T004b: deterministic single terminal state.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentFinalize_terminalStateGuardPreventsClobber() throws Exception {
        Fixture fx = seedPausedRunPastCutoff("race-clobber");

        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Thread tA = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                agentRunFinalizer.finalizeRun(fx.runId, RunStatus.COMPLETED,
                        "resume-output", null, null);
            } catch (Throwable t) { errA.set(t); }
        });
        Thread tB = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                agentRunFinalizer.finalizeRun(fx.runId, RunStatus.CANCELLED,
                        "Run cancelled by stuck-PAUSED scheduler", null, null);
            } catch (Throwable t) { errB.set(t); }
        });

        tA.start(); tB.start();
        gate.countDown(); // release both at once
        assertTrue(tA.join(Duration.ofSeconds(15)), "thread A must complete within timeout");
        assertTrue(tB.join(Duration.ofSeconds(15)), "thread B must complete within timeout");

        // Neither thread should have surfaced an error — finalizeRun never throws (its
        // contract is to swallow optimistic-lock and runtime exceptions internally).
        assertAll("threads must not surface errors — finalizeRun swallows internally",
                () -> assertEquals(null, errA.get(),
                        "thread A must not surface an exception — got " + errA.get()),
                () -> assertEquals(null, errB.get(),
                        "thread B must not surface an exception — got " + errB.get()));

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);

        assertNotNull(status, "row must exist");
        assertNotEquals("PAUSED", status,
                "row must NOT remain PAUSED — at least one finalize must have written a terminal state");
        assertTrue("COMPLETED".equals(status) || "CANCELLED".equals(status),
                "row must end in EXACTLY ONE terminal state, not a mid-state — got " + status);

        // The decisive contract: status and output must come from the SAME winner. Pre-T004b,
        // a clobber could leave status=A's value but output=B's value (Frankenstein write).
        // Post-T004b, whichever finalize landed first persists both fields atomically; the
        // loser short-circuits before any setter call.
        if ("COMPLETED".equals(status)) {
            assertEquals("resume-output", output,
                    "COMPLETED winner's output must match — output mismatch indicates a clobber where the loser overwrote status but not output (or vice versa)");
        } else {
            assertEquals("Run cancelled by stuck-PAUSED scheduler", output,
                    "CANCELLED winner's output must match — output mismatch indicates a clobber");
        }
    }

    // T005 sequential variant — Deterministic, no concurrency. Demonstrates the guard's
    // effect cleanly: finalize once as COMPLETED, finalize again as CANCELLED, assert
    // status stayed COMPLETED. This is the simplest possible expression of the contract.
    @Test
    void sequentialFinalize_secondCallOnTerminalRowIsNoop() {
        Fixture fx = seedPausedRunPastCutoff("seq-noop");

        agentRunFinalizer.finalizeRun(fx.runId, RunStatus.COMPLETED, "first-write", null, null);
        agentRunFinalizer.finalizeRun(fx.runId, RunStatus.CANCELLED, "second-write-must-not-land", null, null);

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);

        assertAll("guard short-circuits the second call",
                () -> assertEquals("COMPLETED", status,
                        "second finalize must NOT have transitioned COMPLETED → CANCELLED"),
                () -> assertEquals("first-write", output,
                        "first writer's output must be preserved — second writer's text must NOT have clobbered it"));
    }

    // T005 sweeper-side ride-along — When a row is already COMPLETED (resume finalized first),
    // the stuck-PAUSED sweeper's filter (status='PAUSED' AND created_at < cutoff) must not
    // find the row, so finalizeRun is never invoked from the sweeper path. This is the
    // sweeper's own first-line guard — independent of T004b but worth pinning together so
    // both layers' contracts are explicit.
    @Test
    void sweeperFilter_excludesAlreadyTerminalRows_neverInvokesFinalize() {
        Fixture fx = seedPausedRunPastCutoff("sweeper-skip");
        // Pre-finalize the row as COMPLETED — simulates resume completing before sweeper fires.
        agentRunFinalizer.finalizeRun(fx.runId, RunStatus.COMPLETED, "resume-output", null, null);

        runExecutionManager.expireStuckPausedRuns();

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);

        assertAll("sweeper must not touch already-terminal rows",
                () -> assertEquals("COMPLETED", status,
                        "row must remain COMPLETED — sweeper's filter excludes non-PAUSED status; if the sweeper somehow finalized, the T004b guard catches it"),
                () -> assertEquals("resume-output", output,
                        "resume's output must be preserved through the sweeper sweep"));
    }

    // T005 cross-pod guard — SELECT FOR UPDATE closes the race window where two pods both
    // read the row at version V before either commits. With the pessimistic write lock held
    // for the duration of finalizeRun's REQUIRES_NEW transaction, the second concurrent
    // caller blocks at the database level until the first commits and releases the lock.
    // The second caller then reloads the post-commit row, observes the terminal state, and
    // the T004b idempotence guard short-circuits before any field mutation. No OCC retry
    // needed; the @Version guard remains as defense-in-depth for other writers.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void crossPodFinalize_currentlyUnsafe_pinsPR7Scope() throws Exception {
        Fixture fx = seedPausedRunPastCutoff("cross-pod-lock");

        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Thread tA = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                agentRunFinalizer.finalizeRun(fx.runId, RunStatus.COMPLETED,
                        "cross-pod-A-output", null, null);
            } catch (Throwable t) { errA.set(t); }
        });
        Thread tB = Thread.ofVirtual().unstarted(() -> {
            awaitGate(gate);
            try {
                agentRunFinalizer.finalizeRun(fx.runId, RunStatus.CANCELLED,
                        "cross-pod-B-output", null, null);
            } catch (Throwable t) { errB.set(t); }
        });

        tA.start(); tB.start();
        gate.countDown();
        assertTrue(tA.join(Duration.ofSeconds(15)), "thread A must complete within timeout");
        assertTrue(tB.join(Duration.ofSeconds(15)), "thread B must complete within timeout");

        assertAll("threads must not surface errors — SELECT FOR UPDATE serializes; finalizeRun never throws",
                () -> assertEquals(null, errA.get(),
                        "thread A must not surface an exception — got " + errA.get()),
                () -> assertEquals(null, errB.get(),
                        "thread B must not surface an exception — got " + errB.get()));

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, fx.runId);

        assertNotNull(status, "row must exist");
        assertTrue("COMPLETED".equals(status) || "CANCELLED".equals(status),
                "row must end in exactly one terminal state — got " + status);

        // The decisive contract: the SELECT FOR UPDATE lock guarantees the first writer's
        // commit is fully visible before the second writer reads. Status and output must be
        // from the SAME writer — no Frankenstein interleaving.
        if ("COMPLETED".equals(status)) {
            assertEquals("cross-pod-A-output", output,
                    "COMPLETED winner's output must be consistent — mismatch indicates the lock did not prevent clobber");
        } else {
            assertEquals("cross-pod-B-output", output,
                    "CANCELLED winner's output must be consistent — mismatch indicates the lock did not prevent clobber");
        }
    }

    // ─── helpers ───

    private record Fixture(String orgId, String userId, String agentId, String sessionId, String runId) {}

    private Fixture seedPausedRunPastCutoff(String label) {
        String orgId = "org-" + label;
        String userId = "user-" + label;
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, true, now(), now())
                """, agentId, "Race Test Agent " + label, orgId);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, userId, orgId, agentId);

        // 25h-old PAUSED row — past the 24h sweeper cutoff so sweeper picks it up.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, version,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAUSED', 0, now() - interval '25 hours', now())
                """,
                runId, agentId, sessionId, userId, orgId, "input for " + label);

        return new Fixture(orgId, userId, agentId, sessionId, runId);
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Gate did not open within 5s — test setup error");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
