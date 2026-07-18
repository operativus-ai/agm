package com.operativus.agentmanager.integration.agents;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Sibling pin to {@link AgentRunOrgIdSpoofRuntimeTest}. Pins
 *   that the {@code AgentsController} run / stream / runs-background endpoints reject
 *   the request body's {@code userId} field when a JWT principal is bound.
 *
 *   <p>The corresponding production logic lives in
 *   {@code AgentsController.resolveCallerUserId} (lines 144-150):
 *   <pre>
 *   Authentication auth = SecurityContextHolder.getContext().getAuthentication();
 *   if (auth != null &amp;&amp; auth.isAuthenticated()
 *           &amp;&amp; auth.getPrincipal() instanceof UserDetailsImpl ud) {
 *       return ud.getId() != null ? ud.getId().toString() : ud.getUsername();
 *   }
 *   return request.userId();
 *   </pre>
 *
 *   <p>Without this pin, a regression that drops the SecurityContext branch (or that
 *   instanceof-checks the wrong principal type) would silently fall through to
 *   {@code request.userId()} — allowing any caller to attribute a run to any user.
 *   Audit and FinOps trails (which use {@code agent_runs.user_id} for spend
 *   attribution) would then be spoofable via a body field.
 *
 *   <p>Two pins:
 *   <ol>
 *     <li>Spoofed {@code userId} in the body is ignored — the persisted
 *         {@code agent_runs.user_id} matches the JWT-bound
 *         {@code UserDetailsImpl.getId().toString()}, not the attacker-supplied value.</li>
 *     <li>The attacker-supplied {@code userId} never appears on the row — guards
 *         against a future regression where the controller accidentally writes BOTH
 *         (e.g. JWT-id in one column, body-id in another).</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentRunUserIdSpoofRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void agentRun_spoofedUserIdInBody_isIgnored_runAttributedToJwtPrincipalUserId() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-user-spoof-" + tag;
        String victimUsername = "agent-run-user-spoof-" + tag;

        // registerLoginWithOrg creates the user row and stamps org_id; the JWT carries the
        // user's UUID. We need that UUID to compare against agent_runs.user_id after the run.
        HttpHeaders victimAuth = registerLoginWithOrg(victimUsername, orgId);
        String victimUserUuid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", String.class, victimUsername);
        assertNotNull(victimUserUuid, "fixture precondition: registered user must have a UUID id");

        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-user-spoof-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "user-spoof probe agent", orgId);

        long runsBefore = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE agent_id = ?",
                Long.class, agentId);

        // The spoof: body claims userId=spoof-target, but JWT carries the victim's
        // actual UUID. resolveCallerUserId must return the JWT value.
        String spoofedUserId = "spoof-target-not-actually-this-user-" + tag;
        Map<String, Object> body = new HashMap<>();
        body.put("message", "user-spoof probe");
        body.put("sessionId", "session-user-spoof-" + tag);
        body.put("userId", spoofedUserId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, victimAuth), JSON_MAP);

        assertEquals(false, resp.getStatusCode().is5xxServerError(),
                "controller must not 500 on a normal tenant run attempt; got: " + resp.getStatusCode());

        long runsAfter = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE agent_id = ?",
                Long.class, agentId);
        assertEquals(runsBefore + 1, runsAfter,
                "POST /api/agents/{id}/runs must insert exactly one agent_runs row for this run");

        // Pin: the persisted user_id matches the JWT principal's UUID, NOT the body spoof.
        String runUserId = jdbc.queryForObject(
                "SELECT user_id FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, agentId);
        assertEquals(victimUserUuid, runUserId,
                "agent_runs.user_id must equal the JWT principal's UUID ("
                        + victimUserUuid + "), NOT the body-spoofed value (" + spoofedUserId + "). "
                        + "A failure here means resolveCallerUserId regressed — either the "
                        + "SecurityContext branch stopped firing or it stopped instanceof-matching "
                        + "UserDetailsImpl. Audit + FinOps attribution would then be spoofable "
                        + "by any tenant caller via the body field.");
        assertNotEquals(spoofedUserId, runUserId,
                "attacker-supplied userId must never appear on the persisted run row");
    }

    @Test
    void agentRun_omittedUserIdInBody_stillAttributedToJwtPrincipalUserId() {
        // The body field is OPTIONAL — omitting it must still trigger the JWT-id resolution.
        // Without this guard, the controller might silently persist NULL user_id when the
        // body omits userId, which would break FinOps spend reports keyed on user_id.
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-user-omit-" + tag;
        String username = "agent-run-user-omit-" + tag;

        HttpHeaders auth = registerLoginWithOrg(username, orgId);
        String userUuid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", String.class, username);

        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-user-omit-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "user-omit probe agent", orgId);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "user-omit probe");
        body.put("sessionId", "session-user-omit-" + tag);
        // Deliberately omitting userId.

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(false, resp.getStatusCode().is5xxServerError(),
                "controller must not 500 when body userId is absent; got: " + resp.getStatusCode());

        String runUserId = jdbc.queryForObject(
                "SELECT user_id FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, agentId);
        assertEquals(userUuid, runUserId,
                "omitted body userId must NOT collapse the persisted user_id to NULL — the "
                        + "JWT principal's UUID must still flow through resolveCallerUserId. "
                        + "If this fails, FinOps spend attribution by user would silently break.");
    }
}
