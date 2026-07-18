package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.control.service.WorkflowService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins both stuck-run sweepers on the workflow-runs side:
 *   <ul>
 *     <li>{@link WorkflowService#expireStuckPausedWorkflowRuns} — hourly cron that cancels
 *         {@code workflow_runs} stuck in PAUSED beyond the cutoff (mirror of
 *         {@code RunExecutionManager.expireStuckPausedRuns} on the agent-runs side).</li>
 *     <li>{@link WorkflowService#cancelOrphanedRunningWorkflowRuns} — startup sweep
 *         (ApplicationReadyEvent) that cancels {@code workflow_runs} stuck in RUNNING
 *         beyond the cutoff. Catches rows orphaned by an ungraceful restart.</li>
 *   </ul>
 *   Sibling {@code StuckPausedRunsSchedulerRuntimeTest} pins the agent_runs side. This
 *   class fills the symmetric gap.
 *
 *   <p>Both methods are invoked directly (not via the @Scheduled cron or the
 *   ApplicationReadyEvent re-fire) — testing Spring's scheduler/event infra would just
 *   verify Spring works. The contract under test is the cutoff arithmetic + the
 *   state-transition semantics.
 *
 *   <p>Cutoff hours stay at their configured defaults
 *   ({@code agentmanager.workflow-run-paused-cutoff-hours},
 *   {@code agentmanager.workflow-run-running-cutoff-hours}) so the arithmetic is the
 *   contract — not test-only magic. Fixtures use SQL interval literals to push
 *   {@code created_at} into the past.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowStuckRunSweeperRuntimeTest extends BaseIntegrationTest {

    @Autowired private WorkflowService workflowService;

    @BeforeEach
    void seedFixtures() {
        // agents.model_id FK seed — same idiom as sibling sweeper test.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // ─── Stuck-PAUSED sweep ──────────────────────────────────────────────────

    @Test
    void expireStuckPausedWorkflowRuns_cancelsPausedOlderThanCutoff_keepsFreshPausedUntouched() {
        Fixture stale = seedWorkflowRun("stuck-paused-stale", "PAUSED",
                "now() - interval '48 hours'");
        Fixture fresh = seedWorkflowRun("stuck-paused-fresh", "PAUSED",
                "now()");

        workflowService.expireStuckPausedWorkflowRuns();

        String staleStatus = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, stale.runId);
        String stalePayload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, stale.runId);
        String freshStatus = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, fresh.runId);

        assertAll("stuck-PAUSED workflow_run sweep semantics",
                () -> assertEquals("CANCELLED", staleStatus,
                        "PAUSED workflow_run older than the cutoff must flip to CANCELLED"),
                () -> assertNotNull(stalePayload,
                        "sweeper must overwrite current_payload with the cancellation reason"),
                () -> assertTrue(stalePayload.contains("stuck in PAUSED"),
                        "cancellation reason must explicitly name the stuck-PAUSED scheduler "
                                + "so audit triage can distinguish it from user-rejection or "
                                + "orphan-cleanup; got '" + stalePayload + "'"),
                () -> assertEquals("PAUSED", freshStatus,
                        "fresh PAUSED workflow_run must stay PAUSED — sweeping it would cancel "
                                + "an in-flight HITL approval"));
    }

    @Test
    void expireStuckPausedWorkflowRuns_doesNotTouchTerminalStateRows() {
        // Rows already in a terminal state should never be re-cancelled (idempotency).
        Fixture completed = seedWorkflowRun("completed-stale", "COMPLETED",
                "now() - interval '48 hours'");
        Fixture failed = seedWorkflowRun("failed-stale", "FAILED",
                "now() - interval '48 hours'");
        Fixture alreadyCancelled = seedWorkflowRun("cancelled-stale", "CANCELLED",
                "now() - interval '48 hours'");

        workflowService.expireStuckPausedWorkflowRuns();

        assertEquals("COMPLETED", statusOf(completed.runId),
                "COMPLETED rows must not be touched by the stuck-PAUSED sweep");
        assertEquals("FAILED", statusOf(failed.runId),
                "FAILED rows must not be touched by the stuck-PAUSED sweep");
        assertEquals("CANCELLED", statusOf(alreadyCancelled.runId),
                "already-CANCELLED rows must not be re-cancelled (sweep is filtered to PAUSED)");
    }

    // ─── Orphaned-RUNNING sweep ──────────────────────────────────────────────

    @Test
    void cancelOrphanedRunningWorkflowRuns_cancelsRunningOlderThanCutoff_keepsFreshRunningUntouched() {
        Fixture stale = seedWorkflowRun("orphan-running-stale", "RUNNING",
                "now() - interval '48 hours'");
        Fixture fresh = seedWorkflowRun("orphan-running-fresh", "RUNNING",
                "now()");

        workflowService.cancelOrphanedRunningWorkflowRuns();

        String staleStatus = statusOf(stale.runId);
        String stalePayload = payloadOf(stale.runId);
        String freshStatus = statusOf(fresh.runId);

        assertAll("orphaned-RUNNING workflow_run sweep semantics",
                () -> assertEquals("CANCELLED", staleStatus,
                        "RUNNING workflow_run older than the cutoff must flip to CANCELLED — "
                                + "it survived an ungraceful restart and the VT is gone"),
                () -> assertNotNull(stalePayload,
                        "sweeper must overwrite current_payload with the cancellation reason"),
                () -> assertTrue(stalePayload.contains("orphaned"),
                        "cancellation reason must explicitly say 'orphaned' so audit triage can "
                                + "distinguish startup-sweep from user-rejection or stuck-PAUSED-expiry; "
                                + "got '" + stalePayload + "'"),
                () -> assertEquals("RUNNING", freshStatus,
                        "fresh RUNNING workflow_run must stay RUNNING — sweeping it would cancel "
                                + "an in-flight execution that survived the restart via the queue"));
    }

    @Test
    void cancelOrphanedRunningWorkflowRuns_doesNotTouchPausedOrTerminalRows() {
        // The startup sweep filters strictly on RUNNING. Anything else stays put — PAUSED
        // belongs to the hourly stuck-PAUSED sweep; terminal rows shouldn't be re-cancelled.
        Fixture paused = seedWorkflowRun("orphan-paused-stale", "PAUSED",
                "now() - interval '48 hours'");
        Fixture completed = seedWorkflowRun("orphan-completed-stale", "COMPLETED",
                "now() - interval '48 hours'");
        Fixture failed = seedWorkflowRun("orphan-failed-stale", "FAILED",
                "now() - interval '48 hours'");

        workflowService.cancelOrphanedRunningWorkflowRuns();

        assertEquals("PAUSED", statusOf(paused.runId),
                "PAUSED rows must not be touched by the orphaned-RUNNING sweep");
        assertEquals("COMPLETED", statusOf(completed.runId),
                "COMPLETED rows must not be touched");
        assertEquals("FAILED", statusOf(failed.runId),
                "FAILED rows must not be touched");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private record Fixture(String orgId, String workflowId, String sessionId, String runId) {}

    private Fixture seedWorkflowRun(String label, String status, String createdAtSqlExpr) {
        String runId = "wf-run-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-" + label + "-" + UUID.randomUUID();
        String sessionId = "sess-" + label + "-" + UUID.randomUUID();
        String orgId = "org-sweep-" + label;
        String userId = "user-sweep-" + label;

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, sessionId, userId, orgId);

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, workflowId, "Sweeper Probe " + label, "", orgId);

        // String-interpolated SQL expression for created_at — controlled at compile time
        // (only this helper writes it, no test data passes through), so SQL injection is
        // not in scope. Bind parameters are still used for the string values.
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload,
                                           org_id, created_at, updated_at)
                VALUES (?, ?, ?, '%s', 0, ?, ?, %s, now())
                """.formatted(status, createdAtSqlExpr),
                runId, workflowId, sessionId, "payload-" + label, orgId);

        return new Fixture(orgId, workflowId, sessionId, runId);
    }

    private String statusOf(String runId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
    }

    private String payloadOf(String runId) {
        return jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, runId);
    }
}
