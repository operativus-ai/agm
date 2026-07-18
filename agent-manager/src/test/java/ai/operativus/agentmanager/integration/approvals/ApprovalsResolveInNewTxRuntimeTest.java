package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Pin the {@code Propagation.REQUIRES_NEW} contract on
 *   {@code ApprovalService.resolveApprovalForOrgInNewTx}. The method exists specifically
 *   so {@code bulkResolveForOrg}'s outer transaction does NOT get marked rollback-only
 *   when a single inner resolve throws — the explanatory Javadoc on the production
 *   method says:
 *   <blockquote>
 *     "Without REQUIRES_NEW propagation, a failed inner resolve marks the outer
 *      transaction as rollback-only. The outer's catch block absorbs the exception,
 *      but the rollback-only flag remains, and the outer commit throws
 *      UnexpectedRollbackException → HTTP 500."
 *   </blockquote>
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Coverage gap before this test:
 *   {@code ApprovalsBulkResolvePartialFailureRuntimeTest} exercises the contract from
 *   the outside (via the HTTP bulk-resolve endpoint that uses this method) and
 *   observes the symptomatic result — fresh resolves still commit when others fail.
 *   This test pins the REQUIRES_NEW contract directly at the service layer so a
 *   future regression that drops the propagation annotation surfaces here with a
 *   precise failure mode (UnexpectedRollbackException on outer commit), not just
 *   as a 200 → 500 status flip in the bulk integration test.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsResolveInNewTxRuntimeTest extends BaseIntegrationTest {

    @Autowired private ApprovalService approvalService;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tt;

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        tt = new TransactionTemplate(txManager);
    }

    // P3.2-1 — REQUIRES_NEW isolation contract. Inner throws; outer's catch block
    // absorbs the exception; outer commits SUCCESSFULLY. Without REQUIRES_NEW, the
    // inner's rollback-only flag would propagate, and the outer commit would throw
    // UnexpectedRollbackException — failing this assertDoesNotThrow.
    @Test
    void resolveInNewTx_innerThrows_outerCommitsSuccessfully_notMarkedRollbackOnly() {
        String orgId = "org-tx-iso";
        String missingId = "approval-does-not-exist-" + UUID.randomUUID();
        AtomicReference<Throwable> innerExceptionCaught = new AtomicReference<>();

        assertDoesNotThrow(() -> tt.execute(status -> {
            try {
                approvalService.resolveApprovalForOrgInNewTx(missingId, "APPROVED",
                        "test-resolver", orgId);
                fail("inner call must throw — approval " + missingId + " does not exist");
            } catch (Exception e) {
                innerExceptionCaught.set(e);
                // Swallow per the bulk-resolve pattern. If REQUIRES_NEW is not in effect,
                // the inner exception WILL still propagate via the rollback-only flag at
                // outer commit time as UnexpectedRollbackException — the assertDoesNotThrow
                // above catches that failure mode.
            }
            return null;
        }), "outer transaction must commit normally even though inner threw — "
                + "this is the entire purpose of Propagation.REQUIRES_NEW on resolveApprovalForOrgInNewTx");

        assertNotNull(innerExceptionCaught.get(),
                "inner must have thrown — missing approval id should produce ResourceNotFoundException");
    }

    // P3.2-2 — REQUIRES_NEW commit-independence: the inner's commit survives even when
    // the outer rolls back. Seed PENDING row → outer tx → call inner (which commits the
    // resolve in its own tx) → outer throws → outer rolls back. The approval row stays
    // APPROVED because the inner's commit was already durable when the outer rolled
    // back. Without REQUIRES_NEW, both would share the outer's tx and a rollback there
    // would also roll back the resolve.
    @Test
    void resolveInNewTx_innerCommits_thenOuterRollsBack_approvalRowStaysApproved() {
        String orgId = "org-tx-commit-iso";
        String approvalId = seedPendingApprovalViaJdbc("tx-commit-iso", orgId);

        // Sanity pre-condition
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId));

        // Outer tx that intentionally rolls back AFTER the inner commits.
        try {
            tt.execute(status -> {
                approvalService.resolveApprovalForOrgInNewTx(approvalId, "APPROVED",
                        "test-resolver", orgId);
                // Mark the outer as rollback-only AFTER the inner is done. Inner's
                // independent tx is already committed at this point.
                status.setRollbackOnly();
                return null;
            });
        } catch (UnexpectedRollbackException expected) {
            // setRollbackOnly() causes Spring to surface UnexpectedRollbackException on
            // commit attempt. That's expected; the assertion is about the row state.
        }

        assertAll("inner's commit is durable even after outer rolls back",
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, approvalId),
                        "row must be APPROVED — inner REQUIRES_NEW commit is independent of the outer's rollback"),
                () -> assertEquals("test-resolver", jdbc.queryForObject(
                        "SELECT resolved_by FROM approvals WHERE id = ?", String.class, approvalId),
                        "resolved_by audit must be persisted — inner tx's writes survived"));
    }

    // ─── helpers ───

    private String seedPendingApprovalViaJdbc(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Tx Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'tx-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);
        return approvalId;
    }
}
