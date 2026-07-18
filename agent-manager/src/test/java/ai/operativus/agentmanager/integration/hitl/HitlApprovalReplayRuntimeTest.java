package ai.operativus.agentmanager.integration.hitl;

import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the approval-replay contract — once an approval has been
 *   resolved (APPROVED or REJECTED), a second resolve attempt against the same approval ID
 *   must reject with {@link BusinessValidationException} and produce no side effects on
 *   either the {@code approvals} row or the linked {@code agent_runs} row. Guards against
 *   double-resume (two finalize calls landing for the same run) and against the duplicate-
 *   row regression that PR #355 (F3a) closed for the original resume path.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>Distinct from {@code ApprovalsRuntimeTest.concurrentResolve_exactlyOneWins_*} which
 * tests the optimistic-lock guard under simultaneous contention. This test pins the
 * SEQUENTIAL replay path (e.g., a stale UI re-submitting the same decision) where the
 * first call has fully committed before the second begins.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class HitlApprovalReplayRuntimeTest extends BaseIntegrationTest {

    @Autowired private ApprovalService approvalService;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // T007 primary contract — Two sequential resolveApprovalForOrg calls on the same
    // approval ID. The first commits APPROVED; the second must throw
    // BusinessValidationException("Cannot resolve approval in state: APPROVED") and the
    // approval/run rows must remain in the first call's outcome with no duplicate
    // agent_runs row inserted.
    @Test
    void replayApprove_secondCallRejected_andNoDuplicateAgentRunRow() {
        Fixture fx = seedPendingApprovalAndPausedRun("replay-approve");

        approvalService.resolveApprovalForOrg(fx.approvalId, "APPROVED", "approver-1", fx.orgId);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> approvalService.resolveApprovalForOrg(fx.approvalId, "APPROVED", "approver-2", fx.orgId),
                "second resolve must throw BusinessValidationException — the PENDING-state guard at ApprovalService.resolveApprovalForOrg fires before any persistence");

        assertTrue(ex.getMessage().contains("APPROVED")
                        || ex.getMessage().contains("Cannot resolve"),
                "exception message must reference the already-terminal state — got: " + ex.getMessage());

        // Side-effect assertions: approval row stays in first writer's state.
        String approvalStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        String approvalResolvedBy = jdbc.queryForObject(
                "SELECT resolved_by FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertAll("approval row must reflect the first writer only",
                () -> assertEquals("APPROVED", approvalStatus,
                        "approval status must be APPROVED from first call — second call must not have flipped it"),
                () -> assertEquals("approver-1", approvalResolvedBy,
                        "resolvedBy must remain 'approver-1' — second call must not have overwritten attribution"));

        // F3a regression check: exactly ONE agent_runs row for this runId, not two.
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE id = ?", Integer.class, fx.runId);
        assertNotNull(rowCount, "count query must return a value");
        assertEquals(1, rowCount,
                "exactly one agent_runs row for this runId must exist — F3a regression: replay must not insert a duplicate row via the inner run()'s preAllocatedRunId-unbound branch");
    }

    // T007 reject-then-replay variant — Same contract for REJECTED. After first call
    // finalizes the approval as REJECTED, a second call (whether APPROVED or REJECTED)
    // must throw without side effects.
    @Test
    void replayReject_secondCallRejected_andApprovalStaysCancelled() {
        Fixture fx = seedPendingApprovalAndPausedRun("replay-reject");

        approvalService.resolveApprovalForOrg(fx.approvalId, "REJECTED", "approver-1", fx.orgId);

        // Try to flip from REJECTED back to APPROVED — must fail.
        assertThrows(BusinessValidationException.class,
                () -> approvalService.resolveApprovalForOrg(fx.approvalId, "APPROVED", "approver-2", fx.orgId),
                "REJECTED → APPROVED replay must fail — state machine forbids resurrecting a rejected approval");

        String approvalStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertEquals("REJECTED", approvalStatus,
                "approval must remain REJECTED — guard must run before any state mutation attempt");
    }

    // T007 resolved-then-resume-flow check — After a successful APPROVED resolve, the
    // agent_runs row should NOT remain PAUSED (the resume path either ran successfully or
    // finalized via AgentService.continueRun's finalizer call). Specifically guards against
    // the F18 historical bug where APPROVED was string-mismatched to REJECTED logic; if that
    // regressed, the row would be CANCELLED instead of either COMPLETED (success) or PAUSED
    // (still in flight). For Fake* models the resume runs synchronously in a background VT,
    // and the ApprovalService captures + restores AgentContextSnapshot for that VT.
    @Test
    void afterApprovedResolve_agentRunRowDoesNotRegressToPausedOrCancelled() throws Exception {
        Fixture fx = seedPendingApprovalAndPausedRun("post-approve");

        approvalService.resolveApprovalForOrg(fx.approvalId, "APPROVED", "approver-A", fx.orgId);

        // The resume runs in a background VT (see ApprovalService.resolveApprovalForOrg
        // F12 comment). Give it a moment to finalize. 2s is generous for FakeChatModel.
        Thread.sleep(2000);

        String runStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, fx.runId);
        assertNotNull(runStatus, "agent_runs row must exist for the resumed runId");
        assertNotEquals("PAUSED", runStatus,
                "row must have transitioned out of PAUSED — if still PAUSED, the resume's finalize never landed (F4 regression?)");
        // F18 guard — pre-#355, APPROVED was string-mismatched to the REJECT branch and
        // every successful approve finalized as CANCELLED. Asserting the row is NOT
        // CANCELLED catches that regression directly. (FAILED is acceptable too if the
        // FakeChatModel-driven resume genuinely failed; we don't pin the success outcome
        // because the FakeChatModel response wiring isn't this test's concern.)
        assertNotEquals("CANCELLED", runStatus,
                "row must NOT be CANCELLED after APPROVED resolve — F18 regression check (the historical bug routed APPROVED through the REJECT branch). Got: " + runStatus);
    }

    // ─── helpers ───

    private record Fixture(String approvalId, String agentId, String sessionId, String runId, String orgId) {}

    /**
     * Seeds a PENDING approval + a PAUSED agent_run linked by runId, all under one orgId.
     * Mirrors the ApprovalsRuntimeTest helper but also seeds the agent_run row so the
     * resume path (which findById's the run) has something to operate on.
     */
    private Fixture seedPendingApprovalAndPausedRun(String label) {
        String orgId = "org-" + label;
        String userId = "user-" + label;
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, true, now(), now())
                """, agentId, "Replay Test Agent " + label, orgId);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, userId, orgId, agentId);

        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, version,
                                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAUSED', 0, now(), now())
                """,
                runId, agentId, sessionId, userId, orgId, "input for " + label);

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"db\":\"prod\"}", userId, orgId, now, now);

        return new Fixture(approvalId, agentId, sessionId, runId, orgId);
    }
}
