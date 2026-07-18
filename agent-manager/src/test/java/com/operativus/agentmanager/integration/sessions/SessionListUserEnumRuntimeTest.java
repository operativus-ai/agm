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
 * Pins the SessionController fix for cross-user session enumeration.
 *
 * <p>Pre-fix, {@code GET /api/sessions?userId=other-user} was open: the controller
 * passed the query param straight to the service, which filtered by orgId but NOT
 * by the caller's user id. A regular tenant user could enumerate any other user's
 * sessions within their org by setting the query param.
 *
 * <p>Post-fix, the controller gates the {@code userId} param by role — regular
 * users have their {@code userId} forcibly overridden to their authenticated
 * principal's id, so the filter collapses to "my sessions" regardless of the
 * query value. Admins retain full filter access (matches the §28 RBAC pattern).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SessionListUserEnumRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void listSessions_roleUserCannotEnumerateOtherUsersSessionsViaQueryParam() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String org = "org-session-enum-" + tag;

        // Two users in the same org. Both are ROLE_USER (the registerLoginWithOrg
        // helper grants ROLE_USER + ROLE_ADMIN, so we use authenticateAs + a JDBC
        // org bind for a ROLE_USER-only caller).
        HttpHeaders attackerAuth = authenticateAs(
                "session-enum-attacker-" + tag,
                "session-enum-attacker-" + tag + "@test.local",
                "pass-session-1234",
                List.of("ROLE_USER"));
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ?",
                org, "session-enum-attacker-" + tag);

        // Seed a model + agent first to satisfy FK constraints.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "session-enum-agent-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "session enum probe agent", org);

        // Seed a session belonging to a DIFFERENT victim user in the same org.
        // Recent updated_at so SessionService.listSessions's SESSION_TTL_HOURS cutoff
        // doesn't filter it out.
        String victimUserId = "victim-user-" + tag;
        String victimSessionId = "victim-session-" + tag;
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, victimSessionId, victimUserId, org, agentId);

        // Attacker (ROLE_USER) attempts cross-user enumeration via the userId query param.
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/sessions?userId=" + victimUserId + "&size=50"),
                HttpMethod.GET, new HttpEntity<>(attackerAuth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "endpoint should return 200 (filter collapses to caller's own sessions, not 403)");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");

        assertTrue(content.stream().noneMatch(s -> victimSessionId.equals(s.get("sessionId"))),
                "ROLE_USER attacker must NOT see victim's session via ?userId=" + victimUserId
                        + " — the controller now overrides userId with the principal's id. "
                        + "Got response content: " + content);
    }
}
