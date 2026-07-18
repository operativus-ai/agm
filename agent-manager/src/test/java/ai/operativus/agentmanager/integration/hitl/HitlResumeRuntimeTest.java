package ai.operativus.agentmanager.integration.hitl;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Black-box runtime coverage of the HITL (Human-in-the-Loop)
 *   resume surface — {@code POST /api/v1/approvals/{id}/resolve}, driven by
 *   {@link ai.operativus.agentmanager.control.service.ApprovalService#resolveApprovalForOrg}.
 *   Pins the PENDING → APPROVED / REJECTED state machine, the "cannot re-resolve" guard,
 *   and (post fix/hitl-authz-tenant-scoping) the cross-tenant 404 / authenticated-principal
 *   resolvedBy contract.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §11 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T018.
 *
 * Implementation notes / gaps these tests pin:
 *   - The real HITL trigger is {@link ai.operativus.agentmanager.compute.advisor.HitlAdvisor}
 *     throwing {@code ApprovalRequiredException} when a tool annotated with
 *     {@code @RequiresConfirmation} is selected by the model. Reproducing that end-to-end
 *     requires a tenant-registered agent with a real tool binding, a scripted tool-calling
 *     model response, and the correct decision-tier classification — out of scope for a
 *     black-box resume pin. Instead these tests seed the resume preconditions directly via
 *     JDBC (agent + session + approval rows) and exercise ONLY the resolve endpoint.
 *   - {@link ai.operativus.agentmanager.control.service.ApprovalService#resolveApprovalForOrg}
 *     spawns a fire-and-forget virtual thread that calls
 *     {@code agentOperations.continueRun(runId, decision)}. That thread may fail to find
 *     a real run row (we don't seed one — {@code approvals.run_id} has no FK) and logs an
 *     error. Harmless, but it DOES mean assertions must target the approval row and
 *     HTTP response only — anything that depends on the virtual thread completing
 *     successfully (e.g. run.status flipping, workflow route-back) would be racy and is
 *     NOT pinned here.
 *   - Tenant scope: the controller resolves orgId from the authenticated principal. Tests
 *     use {@link BaseIntegrationTest#registerLoginWithOrg} to force-bind the user's orgId
 *     to a known value, then seed the approval row with the same orgId.
 *   - {@code resolvedBy} is the authenticated principal's username — the request body
 *     field is no longer honored by the controller, preventing forged attribution.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class HitlResumeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        // agents.model_id has a FK to models.id (fk_agents_model_id) — seed the model
        // row before we insert our fixture agents, otherwise JDBC inserts fail with
        // DataIntegrityViolation before the test body runs.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // §11 — Case (a): POST /resolve with {decision: APPROVED} on a PENDING approval
    // transitions the row to APPROVED, records resolvedBy + resolvedAt, and returns the
    // updated DTO. The virtual-thread continueRun is fire-and-forget and NOT asserted on
    // here (see class Javadoc).
    @Test
    void approvePendingApprovalReturns200AndPersistsApprovedStatusWithResolvedByFromPrincipal() {
        String orgId = "org-hitl-approve";
        String username = "hitl-approve-runner";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        Fixture fx = seedPendingApproval("hitl-approve", orgId);

        Map<String, Object> body = Map.of("decision", "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "resolve on a PENDING approval must return 200 with the updated DTO — 4xx here indicates a guard regression, 5xx indicates the virtual-thread resume leaked its exception into the HTTP response");

        Map<String, Object> payload = resp.getBody();
        assertNotNull(payload, "ApprovalDTO body must be populated on success");
        assertEquals(fx.approvalId, payload.get("id"));
        assertEquals("APPROVED", payload.get("status"),
                "status in the DTO must reflect the post-resolve state");
        assertEquals(username, payload.get("resolvedBy"),
                "resolvedBy must equal the authenticated principal's username — pin protects against a regression that re-honors the request body field (which would let clients forge attribution)");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, resolved_by, resolved_at FROM approvals WHERE id = ?", fx.approvalId);
        assertEquals("APPROVED", row.get("status"),
                "JDBC status must match DTO — a mismatch means the @Transactional boundary did not commit before the HTTP response flushed");
        assertEquals(username, row.get("resolved_by"));
        assertNotNull(row.get("resolved_at"),
                "resolved_at timestamp must be set on transition — NULL here would break audit queries that rely on the column to detect resolution");
    }

    // §11 — Case (b): POST /resolve with {decision: REJECTED} transitions to REJECTED.
    @Test
    void rejectPendingApprovalReturns200AndPersistsRejectedStatus() {
        String orgId = "org-hitl-reject";
        String username = "hitl-reject-runner";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        Fixture fx = seedPendingApproval("hitl-reject", orgId);

        Map<String, Object> body = Map.of("decision", "REJECTED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("REJECTED", resp.getBody().get("status"));

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertEquals("REJECTED", dbStatus,
                "REJECTED must land on the row — pin separately from APPROVED so a regression that only short-circuits one branch (common when decoupling approve/reject into separate endpoints) still fails");
    }

    // §11 — Case (c): Re-resolving an already-APPROVED approval must return 400.
    @Test
    void resolveAlreadyApprovedApprovalReturnsBadRequestAndLeavesRowUnchanged() {
        String orgId = "org-hitl-guard";
        String username = "hitl-guard-runner";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        Fixture fx = seedPendingApproval("hitl-guard", orgId);

        // First resolve flips PENDING → APPROVED.
        Map<String, Object> firstBody = Map.of("decision", "APPROVED");
        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(firstBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, first.getStatusCode(),
                "precondition: first resolve must succeed before we can exercise the guard on the second");

        // Second resolve on the same approval — now in APPROVED state — must be rejected.
        Map<String, Object> secondBody = Map.of("decision", "REJECTED");
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(secondBody, auth), JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, second.getStatusCode(),
                "resolving a non-PENDING approval must return 400 — 200 would mean double-spend is possible");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, resolved_by FROM approvals WHERE id = ?", fx.approvalId);
        assertEquals("APPROVED", row.get("status"),
                "the row must stay APPROVED from the first call");
        assertEquals(username, row.get("resolved_by"),
                "the resolver name must stay the first-call principal");
    }

    // Pins the cross-tenant 404 introduced by fix/hitl-authz-tenant-scoping (audit
    // 2.4 F1+F2). Pre-fix, ApprovalsController.resolveApproval looked up the approval
    // by id without an orgId check — any authenticated user could resolve any tenant's
    // pending approval. Post-fix, a caller in orgA targeting an approval in orgB sees
    // the same response shape as a missing row (404), so the endpoint cannot be used
    // to probe tenant membership.
    @Test
    void resolveCrossTenantApprovalReturns404() {
        String victimOrg = "org-victim";
        String attackerOrg = "org-attacker";
        Fixture fx = seedPendingApproval("xtenant-victim", victimOrg);

        HttpHeaders attackerAuth = registerLoginWithOrg("xtenant-attacker", attackerOrg);
        Map<String, Object> body = Map.of("decision", "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(body, attackerAuth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant resolve must surface as 404 (same as missing row) — 200 here means the tenant filter is bypassed; 403 here would leak tenant-membership probe results");

        String victimStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertEquals("PENDING", victimStatus,
                "cross-tenant resolve must NOT mutate the victim row — anything other than PENDING means the filter ran AFTER the write");
    }

    // ─── helpers ───

    private record Fixture(String approvalId, String agentId, String sessionId, String runId) {}

    private Fixture seedPendingApproval(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "HITL Test Agent " + label);

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);

        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 'delete_database',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now())
                """, approvalId, runId, sessionId, agentId, "{\"db\":\"prod\"}", label + "-user", orgId);

        return new Fixture(approvalId, agentId, sessionId, runId);
    }
}
