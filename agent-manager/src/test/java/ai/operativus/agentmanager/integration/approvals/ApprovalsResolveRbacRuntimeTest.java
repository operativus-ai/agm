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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pin the RBAC contract on the resolve / bulk-resolve surface of
 *   {@code ApprovalsController}. The class is gated on {@code isAuthenticated()}; the two
 *   write endpoints add a method-level {@code @PreAuthorize("hasRole('ADMIN') or hasRole('APPROVER')")}.
 *   Prior coverage (ApprovalsRuntimeTest.resolveByNonApprover_returns403_R3ProductionFix)
 *   pins the "caller not in approvers list" branch, which is a different gate (per-row
 *   approver assignment in the service layer). This test class pins the role-gate layer.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Cases:
 *   - Unauthenticated → 401 on both endpoints (read and write).
 *   - ROLE_USER (no admin) → 403 on both write endpoints.
 *   - ROLE_ADMIN → 200 on both write endpoints (happy path, no other-role gate).
 *
 * Discovery during execution: the {@code or hasRole('APPROVER')} clause in
 * {@code ApprovalsController}'s method-level {@code @PreAuthorize} is dead code today —
 * {@code ROLE_APPROVER} is not declared in {@code RoleType} (valid values: ROLE_VIEWER,
 * ROLE_USER, ROLE_OPERATOR, ROLE_ADMIN, ROLE_SUPER_ADMIN, ROLE_A2A_AGENT,
 * ROLE_MFA_AUTHENTICATED). Either the role was planned and never landed, or the SpEL
 * clause is residual. Either way, only ROLE_ADMIN can satisfy the gate today; this test
 * pins exactly that, so a future addition of ROLE_APPROVER lands with a clear new test.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsResolveRbacRuntimeTest extends BaseIntegrationTest {

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

    // P1.3-1 — Class-level @PreAuthorize("isAuthenticated()") fires on every endpoint. A
    // request without a Bearer token must return 401, not 403. This guard prevents an
    // accidental future "permitAll()" on the controller.
    @Test
    void noAuth_postResolve_returns401() {
        String approvalId = "approval-" + UUID.randomUUID();

        HttpHeaders bare = new HttpHeaders();
        bare.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), bare),
                JSON_MAP);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "Unauthenticated POST /resolve must return 401 — not 403 (which would imply auth succeeded but authz failed)");
    }

    // P1.3-2 — Same guard on the listing endpoint. Pinning both read and write makes the
    // intent visible: every controller method is at least isAuthenticated.
    @Test
    void noAuth_getPending_returns401() {
        HttpHeaders bare = new HttpHeaders();
        bare.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/pending"),
                HttpMethod.GET,
                new HttpEntity<>(bare),
                JSON_MAP);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    // P1.3-3 — Method-level @PreAuthorize("hasRole('ADMIN') or hasRole('APPROVER')") must
    // reject ROLE_USER. Without this gate any authenticated user could resolve approvals
    // for their org — defeating the entire HITL approval-authority model.
    @Test
    void roleUser_postResolve_returns403() {
        String orgId = "org-rbac-user";
        HttpHeaders userAuth = registerUserOnly("rbac-user", orgId);
        String approvalId = seedPendingApprovalViaJdbc("rbac-user", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), userAuth),
                JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "Authenticated ROLE_USER without ROLE_ADMIN/APPROVER must hit 403 — "
                        + "the method-level @PreAuthorize is the only barrier preventing privilege escalation");

        // Sanity: row stays PENDING — no mutation occurred.
        String status = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
        assertEquals("PENDING", status, "rejected resolve must not have mutated the row");
    }

    // P1.3-4 — Same gate on bulk-resolve. The two write endpoints have IDENTICAL @PreAuthorize
    // expressions today — pinning both makes a future drift between them visible.
    @Test
    void roleUser_postBulkResolve_returns403() {
        String orgId = "org-rbac-user-bulk";
        HttpHeaders userAuth = registerUserOnly("rbac-user-bulk", orgId);
        String approvalId = seedPendingApprovalViaJdbc("rbac-user-bulk", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(approvalId), "decision", "APPROVED"), userAuth),
                JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());

        String status = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
        assertEquals("PENDING", status);
    }

    // P1.3-5 — ROLE_ADMIN happy path for resolve. ApprovalsRuntimeTest already exercises
    // this implicitly via the registerLoginWithOrg helper (which seeds ROLE_USER + ROLE_ADMIN),
    // but never asserts the RBAC gate explicitly. Pinning it here makes "admin can resolve"
    // an explicit, named contract — symmetric to the 403 cases above.
    @Test
    void roleAdmin_postResolve_returns200_happyPath() {
        String orgId = "org-rbac-admin";
        HttpHeaders adminAuth = registerWithRoles("rbac-admin", orgId,
                List.of("ROLE_USER", "ROLE_ADMIN"));
        String approvalId = seedPendingApprovalViaJdbc("rbac-admin", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), adminAuth),
                JSON_MAP);

        assertAll("ROLE_ADMIN resolves successfully",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "ROLE_ADMIN must satisfy `hasRole('ADMIN')` — the only role today that does"),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, approvalId)));
    }

    // P1.3-6 — Symmetric ROLE_ADMIN happy path for bulk-resolve.
    @Test
    void roleAdmin_postBulkResolve_returns200_happyPath() {
        String orgId = "org-rbac-admin-bulk";
        HttpHeaders adminAuth = registerWithRoles("rbac-admin-bulk", orgId,
                List.of("ROLE_USER", "ROLE_ADMIN"));
        String approvalId = seedPendingApprovalViaJdbc("rbac-admin-bulk", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(approvalId), "decision", "APPROVED"), adminAuth),
                JSON_MAP);

        assertAll("ROLE_ADMIN bulk-resolve happy path",
                () -> assertNotEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                        "ROLE_ADMIN must not be 403'd by the method-level gate"),
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, approvalId)));
    }

    // ─── helpers ───

    /**
     * Register + login a user with explicit role list. Mirrors {@code registerLoginWithOrg}
     * but allows callers to choose the role set (ROLE_USER only / ROLE_USER+APPROVER / …).
     * The JDBC update binds the user to the target org before login so the JWT carries the
     * {@code org_id} claim.
     */
    private HttpHeaders registerWithRoles(String username, String orgId, List<String> roles) {
        HttpHeaders headers = authenticateAs(username, username + "@test.local",
                "pass-rbac-1234", roles);
        // authenticateAs only stamps DEFAULT_SYSTEM_ORG on null org_id; for tenant-iso tests
        // we need a specific org, so re-bind here. The JWT was already issued with the
        // updated value because authenticateAs UPDATEs before login.
        if (!"DEFAULT_SYSTEM_ORG".equals(orgId)) {
            jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, username);
            // Re-issue JWT so it carries the updated org_id claim. authenticateAs handled the
            // initial login; we need a fresh login to pick up the new claim.
            return reLogin(username, "pass-rbac-1234");
        }
        return headers;
    }

    /**
     * Convenience for the ROLE_USER-only path — explicit single-role registration.
     */
    private HttpHeaders registerUserOnly(String username, String orgId) {
        return registerWithRoles(username, orgId, List.of("ROLE_USER"));
    }

    private HttpHeaders reLogin(String username, String password) {
        var login = new ai.operativus.agentmanager.core.model.AuthModels.LoginRequest(username, password);
        ResponseEntity<ai.operativus.agentmanager.core.model.AuthModels.JwtResponse> response =
                rest.postForEntity(url("/api/auth/login"), login,
                        ai.operativus.agentmanager.core.model.AuthModels.JwtResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(response.getBody().token());
        return headers;
    }

    /**
     * JDBC-insert a PENDING approval with payload_hash=NULL (legacy-row code path that skips
     * the tamper check). Same shape as the canonical helper in ApprovalsRuntimeTest.
     */
    private String seedPendingApprovalViaJdbc(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "RBAC Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'rbac-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);

        return approvalId;
    }
}
