package com.operativus.agentmanager.integration.sessions;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SessionController by-id IDOR fix for GET/{sessionId}, GET/{sessionId}/runs,
 * and DELETE/{sessionId}.
 *
 * <p>Pre-fix the service layer enforced orgId match only; within an org, ANY authenticated
 * ROLE_USER could read/delete ANY other user's session by guessing the session id.
 * Post-fix, the service additionally enforces row.user_id matches the caller's principal
 * (admin path bypasses via {@code permittedUserId=null}).
 *
 * <p>This is the second half of the SessionController security cleanup — the first half
 * (PR #672) closed the list-time enumeration via {@code ?userId=} query param.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SessionByIdIdorRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Test
    void roleUserCannotReadOrDeleteAnotherUsersSessionInTheSameOrg() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String org = "org-byid-idor-" + tag;

        // Attacker — ROLE_USER only, bound to org-N via JDBC update so the JWT carries
        // the org claim AND the principal has no admin role.
        String attackerUsername = "session-byid-attacker-" + tag;
        HttpHeaders attackerAuth = authenticateAs(
                attackerUsername,
                attackerUsername + "@test.local",
                "pass-session-1234",
                List.of("ROLE_USER"));
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", org, attackerUsername);

        // Seed FK-dependencies: a model + an agent owned by the same org.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "session-byid-agent-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "session by-id IDOR probe agent", org);

        // Victim — a different user in the same org with their own session.
        String victimUserId = "victim-user-byid-" + tag;
        String victimSessionId = "victim-session-byid-" + tag;
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, victimSessionId, victimUserId, org, agentId);

        // GET /api/sessions/{victim-session-id} as the attacker → must 404
        // (existence-leak protection: no signal that the session exists in another user's scope).
        ResponseEntity<Map<String, Object>> get = rest.exchange(
                url("/api/sessions/" + victimSessionId),
                HttpMethod.GET, new HttpEntity<>(attackerAuth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, get.getStatusCode(),
                "ROLE_USER attacker must NOT read another user's session by id — "
                        + "pre-fix this returned 200 with the victim's payload");

        // GET /api/sessions/{victim-session-id}/runs as the attacker → must return empty list
        // (existence-leak protection: same response shape as a session with no runs).
        ResponseEntity<List<Map<String, Object>>> runs = rest.exchange(
                url("/api/sessions/" + victimSessionId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(attackerAuth), JSON_LIST);
        assertEquals(HttpStatus.OK, runs.getStatusCode());
        assertTrue(runs.getBody().isEmpty(),
                "ROLE_USER attacker must NOT enumerate runs of another user's session — "
                        + "pre-fix this would list the victim's runs");

        // DELETE /api/sessions/{victim-session-id} as the attacker → 204 (no-op).
        // Verify the victim's session row is STILL present in the DB.
        long victimRowsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, victimSessionId);
        assertEquals(1L, victimRowsBefore, "fixture precondition: the victim's session row exists");

        ResponseEntity<Void> delete = rest.exchange(
                url("/api/sessions/" + victimSessionId),
                HttpMethod.DELETE, new HttpEntity<>(attackerAuth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, delete.getStatusCode(),
                "DELETE always returns 204 — existence-leak protection (same response whether "
                        + "the session was deleted or not)");

        long victimRowsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, victimSessionId);
        assertEquals(1L, victimRowsAfter,
                "G6: the attacker's DELETE must NOT remove the victim's session row. "
                        + "Pre-fix the controller passed sessionId to a service that only checked orgId, "
                        + "so the attacker could destroy another user's data within their org.");
    }
}
