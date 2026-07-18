package com.operativus.agentmanager.integration.sessions;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the Sessions HTTP surface.
 *   Pins that ROLE_USER of org B cannot list, fetch, or delete sessions belonging to org A,
 *   and cannot enumerate runs attached to org A's sessions.
 *   <p>
 *   Behavioral contracts (SessionService + SessionController):
 *   <ul>
 *     <li>{@code GET /api/sessions}              — paginated; only own-org rows visible.</li>
 *     <li>{@code GET /api/sessions/{id}}          — 404 cross-tenant (existence-leak protection).</li>
 *     <li>{@code GET /api/sessions/{id}/runs}     — empty list cross-tenant.</li>
 *     <li>{@code DELETE /api/sessions/{id}}       — 204 but row NOT deleted (silent no-op guard).</li>
 *   </ul>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SessionTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel();
    }

    @Test
    void listSessionsReturnsOnlyCallerOrgSessions() {
        HttpHeaders orgA = registerLoginWithOrg("sess-iso-a-list", "sess-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sess-iso-b-list", "sess-iso-org-B");

        String agentId = "agent-iso-list-" + UUID.randomUUID();
        seedAgent(agentId);
        insertSessionRow("session-a-1-" + UUID.randomUUID(), "user-a", "sess-iso-org-A", agentId, minutesAgo(5));
        insertSessionRow("session-a-2-" + UUID.randomUUID(), "user-a", "sess-iso-org-A", agentId, minutesAgo(10));
        insertSessionRow("session-b-1-" + UUID.randomUUID(), "user-b", "sess-iso-org-B", agentId, minutesAgo(5));

        ResponseEntity<Map<String, Object>> aResp = rest.exchange(
                url("/api/sessions"),
                HttpMethod.GET, new HttpEntity<>(orgA), JSON_MAP);
        assertEquals(HttpStatus.OK, aResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aResp.getBody().get("content");
        assertEquals(2, aContent.size(),
                "org A must see exactly its 2 sessions — a value of 3 means cross-tenant sessions leaked");

        ResponseEntity<Map<String, Object>> bResp = rest.exchange(
                url("/api/sessions"),
                HttpMethod.GET, new HttpEntity<>(orgB), JSON_MAP);
        assertEquals(HttpStatus.OK, bResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bResp.getBody().get("content");
        assertEquals(1, bContent.size(),
                "org B must see exactly its 1 session — a value of 3 means cross-tenant sessions leaked");
    }

    @Test
    void getSessionByIdReturns404CrossTenant() {
        HttpHeaders orgA = registerLoginWithOrg("sess-iso-a-get", "sess-iso-org-A-get");
        HttpHeaders orgB = registerLoginWithOrg("sess-iso-b-get", "sess-iso-org-B-get");

        String agentId = "agent-iso-get-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-a-get-" + UUID.randomUUID();
        insertSessionRow(sessionId, "user-a", "sess-iso-org-A-get", agentId, minutesAgo(5));

        // org A can read its own session
        ResponseEntity<Map<String, Object>> ownResp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.GET, new HttpEntity<>(orgA), JSON_MAP);
        assertEquals(HttpStatus.OK, ownResp.getStatusCode(),
                "org A must be able to read its own session");
        assertNotNull(ownResp.getBody());
        assertEquals(sessionId, ownResp.getBody().get("id"));

        // org B cannot read org A's session
        ResponseEntity<Map<String, Object>> crossResp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.GET, new HttpEntity<>(orgB), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, crossResp.getStatusCode(),
                "cross-tenant GET /api/sessions/{id} must return 404 — existence-leak protection prevents org B from discovering org A's session IDs");
    }

    @Test
    void getSessionRunsReturnsEmptyListCrossTenant() {
        HttpHeaders orgA = registerLoginWithOrg("sess-iso-a-runs", "sess-iso-org-A-runs");
        HttpHeaders orgB = registerLoginWithOrg("sess-iso-b-runs", "sess-iso-org-B-runs");

        String agentId = "agent-iso-runs-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-a-runs-" + UUID.randomUUID();
        insertSessionRow(sessionId, "user-a", "sess-iso-org-A-runs", agentId, minutesAgo(5));
        insertRunRow("run-a-1-" + UUID.randomUUID(), agentId, sessionId,
                "user-a", "sess-iso-org-A-runs", "COMPLETED", minutesAgo(3));

        // org A sees its own run
        ResponseEntity<List<Map<String, Object>>> ownResp = rest.exchange(
                url("/api/sessions/" + sessionId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(orgA), JSON_LIST);
        assertEquals(HttpStatus.OK, ownResp.getStatusCode());
        assertEquals(1, ownResp.getBody().size(),
                "org A must see its 1 run in the session");

        // org B gets empty list for the same session ID
        ResponseEntity<List<Map<String, Object>>> crossResp = rest.exchange(
                url("/api/sessions/" + sessionId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(orgB), JSON_LIST);
        assertEquals(HttpStatus.OK, crossResp.getStatusCode(),
                "GET /api/sessions/{id}/runs returns 200 with empty list cross-tenant (no existence signal)");
        assertTrue(crossResp.getBody().isEmpty(),
                "cross-tenant run listing must be empty — org_id filter on findBySessionIdAndOrgId must prevent org B from seeing org A's runs");
    }

    @Test
    void deleteSessionCrossTenantIsNoOpRowSurvives() {
        HttpHeaders orgA = registerLoginWithOrg("sess-iso-a-del", "sess-iso-org-A-del");
        HttpHeaders orgB = registerLoginWithOrg("sess-iso-b-del", "sess-iso-org-B-del");

        String agentId = "agent-iso-del-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-a-del-" + UUID.randomUUID();
        insertSessionRow(sessionId, "user-a", "sess-iso-org-A-del", agentId, minutesAgo(5));

        // org B attempts to delete org A's session
        ResponseEntity<Void> crossDel = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.DELETE, new HttpEntity<>(orgB), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, crossDel.getStatusCode(),
                "DELETE returns 204 unconditionally — the controller does not signal whether the row existed to prevent existence-leak");

        // the row must still exist
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(1L, count,
                "org A's session must survive org B's delete attempt — service-level existsBySessionIdAndOrgId guard must block the deletion");

        // org A can still delete its own session
        ResponseEntity<Void> ownDel = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.DELETE, new HttpEntity<>(orgA), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ownDel.getStatusCode());
        Long afterCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(0L, afterCount,
                "org A must be able to delete its own session");
    }

    // ─── helpers ───

    private Timestamp minutesAgo(long minutes) {
        return Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes));
    }

    private void seedAgent(String agentId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'fake-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, agentId);
    }

    private void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-model', 'fake-model', 'fake', 'fake-model', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void insertSessionRow(String sessionId, String userId, String orgId,
                                   String agentId, Timestamp updatedAt) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, userId, orgId, agentId, updatedAt, updatedAt);
    }

    private void insertRunRow(String runId, String agentId, String sessionId,
                               String userId, String orgId, String status, Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'test', 'test', ?, ?)
                """, runId, agentId, sessionId, userId, orgId, status, createdAt, createdAt);
    }
}
