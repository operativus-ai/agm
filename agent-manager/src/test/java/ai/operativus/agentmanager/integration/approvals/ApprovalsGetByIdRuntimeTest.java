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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Runtime coverage for {@code GET /api/v1/approvals/{id}}.
 *   The endpoint is status-agnostic (returns the row whether PENDING, APPROVED, or
 *   REJECTED) and tenant-scoped (cross-tenant lookups return 404, not 200, so
 *   tenant-membership cannot be probed). Sibling case
 *   {@code ApprovalsRuntimeTest.approveLifecycle_removesFromPendingList_andRowPersistsAsApproved}
 *   verifies the row-persistence contract via JDBC; this test verifies the HTTP
 *   read surface that production consumers (audit / history views) call.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsGetByIdRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModelRow() {
        // agents.model_id FK — seed model row first; mirrors the pattern used by
        // ApprovalsRuntimeTest and other approvals-surface runtime tests.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void getById_pendingApproval_returns200WithDto() {
        String orgId = "org-getbyid-pending-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("getbyid-pending", orgId);
        String approvalId = seedPendingApproval("pending", orgId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/approvals/" + approvalId),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "response body must not be null");
        assertAll("pending DTO",
                () -> assertEquals(approvalId, body.get("id")),
                () -> assertEquals("PENDING", body.get("status")),
                () -> assertEquals("destructive-tool", body.get("toolName")));
    }

    @Test
    void getById_resolvedApproval_returns200WithApprovedStatus() {
        String orgId = "org-getbyid-resolved-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("getbyid-resolved", orgId);
        String approvalId = seedPendingApproval("resolved", orgId);

        // Drive the row to APPROVED via the documented resolve endpoint so this test
        // exercises the actual transition and does not depend on JDBC manipulation
        // contradicting the service's state machine.
        ResponseEntity<Map<String, Object>> resolve = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resolve.getStatusCode());

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/approvals/" + approvalId),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /{id} must succeed for an APPROVED row — endpoint is status-agnostic; "
                        + "this is the gap surfaced by approveLifecycle and closed here");
        assertEquals("APPROVED", response.getBody().get("status"),
                "GET /{id} must reflect the post-resolve status — audit / history views "
                        + "rely on this read path");
    }

    @Test
    void getById_crossTenant_returns404() {
        String orgOwner = "org-getbyid-owner-" + UUID.randomUUID();
        String orgIntruder = "org-getbyid-intruder-" + UUID.randomUUID();
        HttpHeaders ownerAuth = registerLoginWithOrg("getbyid-owner", orgOwner);
        HttpHeaders intruderAuth = registerLoginWithOrg("getbyid-intruder", orgIntruder);
        String approvalId = seedPendingApproval("cross-tenant", orgOwner);

        // Owner sees the row.
        ResponseEntity<Map<String, Object>> ownerGet = rest.exchange(
                url("/api/v1/approvals/" + approvalId),
                HttpMethod.GET,
                new HttpEntity<>(ownerAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, ownerGet.getStatusCode(), "owning tenant must see its own approval");

        // Intruder must get 404, NOT 403 — the controller returns the same status as
        // "row does not exist" so tenant-membership cannot be probed by id.
        ResponseEntity<Map<String, Object>> intruderGet = rest.exchange(
                url("/api/v1/approvals/" + approvalId),
                HttpMethod.GET,
                new HttpEntity<>(intruderAuth),
                JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, intruderGet.getStatusCode(),
                "cross-tenant GET must return 404, not 200/403 — leaking row existence "
                        + "via 403 would let an attacker probe approval ids across tenants");
    }

    @Test
    void getById_unknownApproval_returns404() {
        String orgId = "org-getbyid-unknown-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("getbyid-unknown", orgId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/approvals/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "unknown id must return 404 — same path as cross-tenant so the two cases "
                        + "are indistinguishable to a probe");
    }

    // ─── helpers ───

    private String seedPendingApproval(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "GetById Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, ?, ?, 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"db\":\"prod\"}", label + "-user", orgId, now, now);
        return approvalId;
    }
}
