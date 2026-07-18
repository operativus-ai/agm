package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pin the terminal-state idempotency contract on
 *   {@code POST /api/v1/approvals/{id}/resolve}. Once an approval row is in a terminal
 *   state (APPROVED / REJECTED / EXPIRED), a second resolve attempt MUST be a no-op at
 *   the data level — no version bump, no resolved_by overwrite, no resolved_at refresh,
 *   no state regression. The service throws {@code BusinessValidationException("Cannot
 *   resolve approval in state: …")} which surfaces as HTTP 400; the row stays in its
 *   pre-call terminal state.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Why this matters: a race between two concurrent approvers (UI double-click, retry loop,
 * notification handler that fires twice) could otherwise overwrite resolved_by /
 * resolved_at on a terminal row — eroding the audit chain. The
 * {@link ai.operativus.agentmanager.integration.approvals.ApprovalsRuntimeTest#concurrentResolve_exactlyOneWins_rowEndsInSingleTerminalState}
 * test pins the race-winner behaviour; this test pins the loser-side behaviour for
 * already-settled rows AND the EXPIRED state which the concurrent test does not cover.
 *
 * F18 (the resume-side regression where agent_runs flipped to CANCELLED after a
 * terminal-row resolve) is covered by HitlResumeEndToEndTest; this test stays scoped to
 * the approvals row to keep the assertion surface small and the failure mode obvious.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsResolveTerminalIdempotencyRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P1.5-1 — Already-APPROVED row: second resolve attempt returns 400, row state stable.
    // The service throws BusinessValidationException; the controller does NOT special-case
    // it as 200-idempotent (no caching layer makes the retry safe; just rejects).
    @Test
    void resolveAlreadyApprovedRow_secondCallReturns400_rowStaysApproved() {
        String orgId = "org-terminal-approved";
        HttpHeaders auth = registerLoginWithOrg("terminal-approved", orgId);
        String approvalId = seedTerminalRow("terminal-approved", orgId, "APPROVED", "first-resolver");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, resolved_by, version FROM approvals WHERE id = ?", approvalId);

        assertAll("idempotent reject of already-APPROVED resolve",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "second resolve on a terminal row must return 400, not 200-idempotent — "
                                + "no caching layer makes the retry safe; the service rejects loudly"),
                () -> assertEquals("APPROVED", row.get("status"),
                        "terminal state must not regress nor change"),
                () -> assertEquals("first-resolver", row.get("resolved_by"),
                        "resolved_by must NOT be overwritten by the failed second attempt — "
                                + "this is the audit-chain integrity contract"),
                () -> assertEquals(0, ((Number) row.get("version")).intValue(),
                        "no JPA save happened on the failed path — version stays at the seed value"));
    }

    // P1.5-2 — Already-REJECTED row, attempt to re-decision as APPROVED. Stronger
    // assertion than case 1: a future bug that special-cases "approve flips reject"
    // would surface here.
    @Test
    void resolveAlreadyRejectedRow_secondApproveCallReturns400_rowStaysRejected() {
        String orgId = "org-terminal-rejected";
        HttpHeaders auth = registerLoginWithOrg("terminal-rejected", orgId);
        String approvalId = seedTerminalRow("terminal-rejected", orgId, "REJECTED", "first-rejector");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, resolved_by FROM approvals WHERE id = ?", approvalId);

        assertAll("REJECTED stays REJECTED — no flip via second resolve",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode()),
                () -> assertEquals("REJECTED", row.get("status"),
                        "REJECTED rows must NEVER transition to APPROVED via a second resolve"),
                () -> assertEquals("first-rejector", row.get("resolved_by")));
    }

    // P1.5-3 — EXPIRED row (the cleanup scheduler's terminal state, see ApprovalService
    // .expireStaleApprovals). The status check guard reads "status != PENDING" so EXPIRED
    // also rejects. Pinning this prevents a future regression that whitelists EXPIRED as
    // resumable.
    @Test
    void resolveExpiredRow_returns400_rowStaysExpired() {
        String orgId = "org-terminal-expired";
        HttpHeaders auth = registerLoginWithOrg("terminal-expired", orgId);
        String approvalId = seedTerminalRow("terminal-expired", orgId, "EXPIRED", "SYSTEM_GC");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("EXPIRED row cannot be resurrected by a late approve",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode()),
                () -> assertEquals("EXPIRED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, approvalId)),
                () -> assertEquals("SYSTEM_GC", jdbc.queryForObject(
                        "SELECT resolved_by FROM approvals WHERE id = ?", String.class, approvalId),
                        "GC-stamped resolved_by must not be overwritten"));
    }

    // P1.5-4 — Side-effect-free pin: the audit chain (resolved_by, resolved_at, version)
    // remains exactly as seeded after a failed retry attempt. This is the explicit
    // contract that prevents an attacker from "claiming" a resolve by spamming the
    // endpoint on a row resolved by someone else.
    @Test
    void resolveAlreadyApproved_attemptByDifferentUser_doesNotRewriteAuditChain() {
        String orgId = "org-terminal-audit";
        HttpHeaders auth = registerLoginWithOrg("terminal-audit-attacker", orgId);
        String approvalId = seedTerminalRow("terminal-audit", orgId, "APPROVED", "legitimate-approver");
        String resolvedAtBefore = jdbc.queryForObject(
                "SELECT resolved_at::text FROM approvals WHERE id = ?", String.class, approvalId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT resolved_by, resolved_at::text AS resolved_at_text, version FROM approvals WHERE id = ?",
                approvalId);

        assertAll("attacker cannot rewrite resolved_by / resolved_at by retrying",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode()),
                () -> assertEquals("legitimate-approver", row.get("resolved_by"),
                        "resolved_by must reflect the legitimate first approver — never overwritten"),
                () -> assertEquals(resolvedAtBefore, row.get("resolved_at_text"),
                        "resolved_at must remain at the original timestamp — no audit-clock skew"),
                () -> assertEquals(0, ((Number) row.get("version")).intValue(),
                        "version must stay at the seeded value — no JPA save fired on the failed path"),
                () -> assertNotEquals("terminal-audit-attacker", row.get("resolved_by"),
                        "double-negative pin against a future regression that copies the caller's username"));
    }

    // ─── helpers ───

    private String seedTerminalRow(String label, String orgId, String terminalStatus, String resolvedBy) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Terminal Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, resolved_by, decision_tier,
                                       org_id, created_at, updated_at, resolved_at, version)
                VALUES (?, ?, ?, ?, CAST(? AS varchar), 'terminal-tool',
                        ?::jsonb, ?, ?, 'TIER_3_DESTRUCTIVE',
                        ?, now() - interval '1 hour', now() - interval '1 hour',
                        now() - interval '30 minutes', 0)
                """,
                approvalId, runId, sessionId, agentId, terminalStatus,
                "{\"k\":\"v\"}", label + "-requester", resolvedBy, orgId);
        return approvalId;
    }
}
