package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.compute.service.RunExecutionManager;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the {@link RunExecutionManager#expireStuckPausedRuns} sweep —
 *   PAUSED rows older than the configured cutoff flip to CANCELLED via
 *   {@code AgentRunFinalizer.finalizeRun}; rows newer than the cutoff stay PAUSED; rows
 *   already in a terminal state are not touched.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p><b>Why this scheduler exists:</b> a HITL approval that is never resolved (the user
 * walked away, the approver was decommissioned, etc.) leaves the {@code agent_runs} row
 * stuck in PAUSED forever. Without this sweep, session UI / billing rollups / audit
 * closure queries that filter on terminal state silently skip those rows. Mirrors
 * {@code ApprovalService.expireStaleApprovals} on the run side.
 *
 * <p><b>Test strategy:</b> the scheduler interval is pinned to 24h in
 * {@code application-test.properties} (autonomous fire suppressed). Each case invokes
 * {@code expireStuckPausedRuns()} directly — same pattern as
 * {@code ApprovalsRuntimeTest.cleanupScheduler_expiresPendingOlderThan24Hours_…}. Cutoff
 * stays at 24h so the cutoff arithmetic is the contract under test (not test-only magic).
 *
 * <p><b>What this test does NOT pin:</b>
 * <ul>
 *   <li>The {@code @Scheduled} cron itself firing — out of scope for direct invocation
 *       tests; testing Spring's scheduler infra would just verify Spring works.</li>
 *   <li>Concurrent resume races with the sweep — the {@code @Version}-checked write in
 *       {@code AgentRunFinalizer} retries 3x on optimistic-lock contention; that retry
 *       behavior is pinned by {@code AgentRunFinalizerTest}.</li>
 * </ul>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class StuckPausedRunsSchedulerRuntimeTest extends BaseIntegrationTest {

    @Autowired private RunExecutionManager runExecutionManager;

    @BeforeEach
    void seedModel() {
        // agents.model_id FK — same seed pattern as sibling integration tests.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // Primary contract: PAUSED rows older than the 24h cutoff flip to CANCELLED;
    // PAUSED rows newer than the cutoff stay untouched. The CANCELLED row's output
    // carries the explicit "stuck-PAUSED scheduler" reason so audit triage can
    // distinguish system-driven cancellation from user-rejection ("User rejected
    // the requested action.") and orphan-cleanup ("Orphaned"). No magic numbers
    // in the assertion — just the substring that uniquely names this scheduler.
    @Test
    void sweepCancelsPausedOlderThanCutoff_keepsFreshPausedUntouched() {
        Fixture stale = seedPausedRun("stuck-stale", "now() - interval '25 hours'");
        Fixture fresh = seedPausedRun("stuck-fresh", "now()");

        runExecutionManager.expireStuckPausedRuns();

        String staleStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, stale.runId);
        String staleOutput = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, stale.runId);
        String freshStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fresh.runId);

        assertAll("stuck-PAUSED sweep behavior",
                () -> assertEquals("CANCELLED", staleStatus,
                        "PAUSED >= 24h must flip to CANCELLED — if still PAUSED, the cutoff filter never matched and stuck rows accumulate forever"),
                () -> assertNotNull(staleOutput,
                        "CANCELLED row must carry the scheduler's explicit reason — null output blurs audit triage"),
                () -> assertTrue(staleOutput.toLowerCase().contains("stuck-paused scheduler"),
                        "output must name THIS scheduler so audit triage can distinguish it from user-reject (\"User rejected…\") and orphan-cleanup (\"Orphaned\") — got: " + staleOutput),
                () -> assertEquals("PAUSED", freshStatus,
                        "PAUSED < 24h must NOT flip — an off-by-one cutoff would kill in-flight HITL approvals before users have a chance to resolve them"));
    }

    // Guard against the sweep "helping" by re-cancelling rows already in a terminal
    // state. AgentRunFinalizer.finalizeRun is idempotent on terminal status (sets the
    // status field unconditionally if non-null), but re-running it on a COMPLETED row
    // would clobber the original output with the scheduler's reason — which would lose
    // legitimate run output and confuse downstream consumers.
    @Test
    void sweepIgnoresAlreadyTerminalRows() {
        Fixture completed = seedPausedRun("term-completed", "now() - interval '25 hours'");
        // Flip out of PAUSED — sweep should not see this row.
        jdbc.update("UPDATE agent_runs SET status = 'COMPLETED', output = 'success' WHERE id = ?",
                completed.runId);

        runExecutionManager.expireStuckPausedRuns();

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, completed.runId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, completed.runId);
        assertAll("terminal rows untouched",
                () -> assertEquals("COMPLETED", status,
                        "COMPLETED row must stay COMPLETED — if status flipped, the sweep is missing the PAUSED-only filter"),
                () -> assertEquals("success", output,
                        "COMPLETED row's output must stay intact — clobbering it with the scheduler's reason would lose the legitimate run result"));
    }

    // T008 — PR #358's body asserts that the sweeper preserves the row's existing
    // requiredAction column through finalization (relies on AgentRunFinalizer's
    // `if (requiredAction != null) fresh.setRequiredAction(requiredAction)` guard plus the
    // sweeper's null requiredAction argument). Without this, audit-triage of a stuck-PAUSED
    // CANCELLED row loses the original tool/payload context and incident RCA is harder.
    // This case pins that contract directly so a future change (e.g., re-enabling the
    // sweeper to write a new requiredAction value) can't silently regress it.
    @Test
    void sweepPreservesExistingRequiredActionPayload_forAuditTriage() {
        Fixture stuck = seedPausedRun("preserve-required-action", "now() - interval '25 hours'");
        // Seed an existing requiredAction value — same JSON shape AgentService writes via the
        // typed RequiredAction record (PR #357). Sweep must not overwrite this.
        String originalPayload = "{\"tool\":\"destructive-tool\",\"args\":{\"db\":\"prod\"},\"tier\":3}";
        jdbc.update("UPDATE agent_runs SET required_action = ?::text WHERE id = ?",
                originalPayload, stuck.runId);

        runExecutionManager.expireStuckPausedRuns();

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, stuck.runId);
        String requiredAction = jdbc.queryForObject(
                "SELECT required_action FROM agent_runs WHERE id = ?", String.class, stuck.runId);

        assertAll("sweep preserves audit payload",
                () -> assertEquals("CANCELLED", status,
                        "row must transition to CANCELLED — sweep ran and finalized"),
                () -> assertNotNull(requiredAction,
                        "required_action must NOT be null after sweep — sweeper passes null arg to finalizeRun specifically to preserve the existing value via the if-non-null guard"),
                () -> assertEquals(originalPayload, requiredAction,
                        "required_action payload must be byte-for-byte identical to the pre-sweep value — incident RCA needs the original tool/payload context to distinguish user-walked-away from approver-decommissioned"));
    }

    // ─── helpers ───

    private record Fixture(String orgId, String userId, String agentId, String sessionId, String runId) {}

    private Fixture seedPausedRun(String label, String createdAtSqlExpr) {
        String orgId = "org-" + label;
        String userId = "user-" + label;
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, true, now(), now())
                """, agentId, "Stuck-PAUSED Test Agent " + label, orgId);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, userId, orgId, agentId);

        // String-interpolated SQL expression for created_at — controlled at compile time
        // (only seedPausedRun() callers pass values, no test data here), so SQL injection
        // is not in scope. Bind parameters are still used for the string values.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, version,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAUSED', 0, %s, now())
                """.formatted(createdAtSqlExpr),
                runId, agentId, sessionId, userId, orgId, "input for " + label);

        return new Fixture(orgId, userId, agentId, sessionId, runId);
    }
}
