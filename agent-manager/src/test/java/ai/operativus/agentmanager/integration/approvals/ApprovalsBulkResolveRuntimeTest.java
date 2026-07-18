package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins {@code POST /api/v1/approvals/bulk-resolve} —
 *   {@link ai.operativus.agentmanager.control.controller.ApprovalsController#bulkResolve}.
 *   Existing {@code ApprovalsRuntimeTest} covers single-resolve in detail; the bulk path
 *   was completely untested. Bulk-resolve is the efficient route for HITL inbox UIs.
 *
 *   <p>Contract from the controller javadoc (lines 76–82) and
 *   {@code ApprovalService.bulkResolveForOrg}:
 *   <ul>
 *     <li>Each approval is resolved independently via {@code Propagation.REQUIRES_NEW}.
 *         A single failure does NOT roll back successful resolves.</li>
 *     <li>Cross-tenant ids are counted as <b>failures</b>, not 403'd — this prevents
 *         tenant-membership probing through the endpoint's response shape.</li>
 *     <li>Response is {@code BulkResolveResponse(int resolved, int failed)} — terminal
 *         counters only, no per-id detail (also a probe-resistance choice).</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsBulkResolveRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void bulkResolve_allOwnedPendingIds_approveDecision_resolvesAllAndZeroFailures() {
        String orgId = "org-bulk-allowned-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulk-allowned", orgId);
        String a1 = seedPending("a1", orgId);
        String a2 = seedPending("a2", orgId);
        String a3 = seedPending("a3", orgId);

        Map<String, Object> resp = postBulkResolve(auth,
                Map.of("ids", List.of(a1, a2, a3), "decision", "APPROVED"));

        assertAll("happy-path bulk resolve",
                () -> assertEquals(3, ((Number) resp.get("resolved")).intValue(),
                        "all 3 owned PENDING approvals must resolve; got " + resp.get("resolved")),
                () -> assertEquals(0, ((Number) resp.get("failed")).intValue(),
                        "no failures expected on owned-and-PENDING ids; got " + resp.get("failed")));

        for (String id : List.of(a1, a2, a3)) {
            assertEquals("APPROVED", statusOf(id),
                    "approval " + id + " must persist as APPROVED");
        }
    }

    @Test
    void bulkResolve_rejectDecision_flipsAllToRejected() {
        String orgId = "org-bulk-reject-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulk-reject", orgId);
        String a1 = seedPending("rj1", orgId);
        String a2 = seedPending("rj2", orgId);

        Map<String, Object> resp = postBulkResolve(auth,
                Map.of("ids", List.of(a1, a2), "decision", "REJECTED"));

        assertEquals(2, ((Number) resp.get("resolved")).intValue());
        assertEquals(0, ((Number) resp.get("failed")).intValue());
        assertEquals("REJECTED", statusOf(a1));
        assertEquals("REJECTED", statusOf(a2));
    }

    @Test
    void bulkResolve_crossTenantIdsAreCountedAsFailures_notForbidden() {
        String orgA = "org-bulk-cross-A-" + UUID.randomUUID();
        String orgB = "org-bulk-cross-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("bulk-cross-a", orgA);
        registerLoginWithOrg("bulk-cross-b", orgB); // exists; we don't auth as B here

        String ownedA = seedPending("own-a", orgA);
        String foreignB = seedPending("foreign-b", orgB);

        ResponseEntity<Map<String, Object>> raw = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(ownedA, foreignB), "decision", "APPROVED"), authA),
                JSON_MAP);

        assertEquals(HttpStatus.OK, raw.getStatusCode(),
                "cross-tenant ids must NOT 403 — they are counted as failures (probe-resistance "
                        + "design choice per controller javadoc); got " + raw.getStatusCode());
        assertEquals(1, ((Number) raw.getBody().get("resolved")).intValue(),
                "exactly 1 owned approval must resolve; got " + raw.getBody().get("resolved"));
        assertEquals(1, ((Number) raw.getBody().get("failed")).intValue(),
                "the cross-tenant id must be counted as a failure, not 403'd");

        assertEquals("APPROVED", statusOf(ownedA),
                "owned approval must persist as APPROVED");
        assertEquals("PENDING", statusOf(foreignB),
                "cross-tenant approval must remain PENDING — REQUIRES_NEW isolation preserves it");
    }

    @Test
    void bulkResolve_mixOfPendingAndNonExistentIds_partialSucceeds() {
        String orgId = "org-bulk-partial-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulk-partial", orgId);
        String real = seedPending("real", orgId);
        String ghost1 = UUID.randomUUID().toString();
        String ghost2 = UUID.randomUUID().toString();

        Map<String, Object> resp = postBulkResolve(auth,
                Map.of("ids", List.of(real, ghost1, ghost2), "decision", "APPROVED"));

        assertEquals(1, ((Number) resp.get("resolved")).intValue());
        assertEquals(2, ((Number) resp.get("failed")).intValue(),
                "non-existent ids must be counted as failures, not silently ignored — "
                        + "the counter contract matters for client UX (toasts say "
                        + "'2 failed' so the user can audit which IDs)");
        assertEquals("APPROVED", statusOf(real));
    }

    @Test
    void bulkResolve_emptyIdList_returns200WithZeroResolvedZeroFailed() {
        String orgId = "org-bulk-empty-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulk-empty", orgId);

        Map<String, Object> resp = postBulkResolve(auth,
                Map.of("ids", List.of(), "decision", "APPROVED"));

        assertEquals(0, ((Number) resp.get("resolved")).intValue());
        assertEquals(0, ((Number) resp.get("failed")).intValue(),
                "empty ids list is a valid request — neither resolved nor failed counter "
                        + "should increment");
    }

    @Test
    void bulkResolve_alreadyTerminalIds_areCountedAsFailures_notReResolved() {
        String orgId = "org-bulk-terminal-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulk-terminal", orgId);
        String pending = seedPending("pen", orgId);
        String approved = seedTerminal("appr", orgId, "APPROVED");
        String rejected = seedTerminal("rej", orgId, "REJECTED");

        Map<String, Object> resp = postBulkResolve(auth,
                Map.of("ids", List.of(pending, approved, rejected), "decision", "APPROVED"));

        assertEquals(1, ((Number) resp.get("resolved")).intValue(),
                "only the PENDING row should resolve; terminal rows must be guarded");
        assertEquals(2, ((Number) resp.get("failed")).intValue(),
                "both already-terminal rows must be counted as failures (idempotency: "
                        + "re-resolving a terminal approval is a contract violation)");

        // Pre-existing terminal rows must not be mutated.
        assertEquals("APPROVED", statusOf(approved));
        assertEquals("REJECTED", statusOf(rejected));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> postBulkResolve(HttpHeaders auth, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> raw = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, raw.getStatusCode(),
                "bulk-resolve must return 200 — failures are encoded in the body counter, "
                        + "not via HTTP status; got " + raw.getStatusCode());
        assertNotNull(raw.getBody());
        return raw.getBody();
    }

    private String seedPending(String label, String orgId) {
        return seedApproval(label, orgId, "PENDING");
    }

    private String seedTerminal(String label, String orgId, String terminalStatus) {
        return seedApproval(label, orgId, terminalStatus);
    }

    private String seedApproval(String label, String orgId, String status) {
        String agentId = "agent-bulk-" + label + "-" + UUID.randomUUID();
        String sessionId = "sess-bulk-" + label + "-" + UUID.randomUUID();
        String runId = "run-bulk-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-bulk-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Bulk approval test agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId, status,
                "{\"db\":\"prod\"}", label + "-user", orgId, now, now);

        return approvalId;
    }

    private String statusOf(String approvalId) {
        return jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
    }
}
