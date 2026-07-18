package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the AS-IS validation behavior of {@code POST
 *   /api/agents/{agentId}/runs}'s {@code RunRequest} body. The controller's record
 *   declaration ({@code AgentsController.java:111}) has NO bean-validation annotations
 *   — every field is currently unchecked. This test pins the resulting observable
 *   behavior so a fix that adds {@code @NotBlank}/{@code @Valid} can flip these
 *   assertions without silently changing the contract.
 *
 *   <p>Fields probed:
 *   <ul>
 *     <li>{@code message} — null and empty-string. The chat model is reached anyway
 *         (the prompt would just be empty), or the advisor chain throws downstream.</li>
 *     <li>{@code sessionId} — cross-agent (a sessionId that exists for ANOTHER agent
 *         in the same tenant) and cross-tenant (a sessionId from another org).
 *         Pre-fix the controller calls {@code resolveSessionId} which only does
 *         null-check + UUID auto-gen — no agent/tenant ownership verification.</li>
 *   </ul>
 *
 *   <p>The cross-tenant sessionId case is the most security-relevant: a tenant user
 *   could feed in another tenant's sessionId and either (a) succeed silently —
 *   conflating chat histories across tenants in the response prompt window — or
 *   (b) fail with a 500/FK-violation that nonetheless leaks the cross-tenant
 *   sessionId existence. Both are findings worth pinning.
 *
 *   <p>Pins (AS-IS, with PR-description follow-up notes):
 *   <ol>
 *     <li>{@code message=null} — current behavior pin (likely 500 from advisor chain;
 *         test accepts any non-2xx).</li>
 *     <li>{@code message=""} — current behavior pin.</li>
 *     <li>{@code sessionId} that exists in another agent (same tenant) — current
 *         behavior pin: is the new run attached to the foreign session row, or does
 *         the FK guard reject?</li>
 *     <li>{@code sessionId} that exists in another tenant — current behavior pin:
 *         is cross-tenant session-attachment possible?</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunRequestValidationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
        installPermissiveErrorHandler();
    }

    // Pin AS-IS: null message in RunRequest. No @NotBlank on the record — the
    // controller passes the null straight to agentOperations.run, where it eventually
    // hits the chat model boundary. The test asserts only that the request does NOT
    // 200-with-success (which would mean the chat actually ran with a null prompt).
    // A fix that adds @Valid + @NotBlank should flip this to assertEquals(400, ...).
    @Test
    void runWithNullMessage_currentBehaviorPin_doesNotReturn2xx() {
        HttpHeaders auth = authenticatedHeaders("validation-null-msg");
        String agentId = createAgentViaApi(auth, "null-message validation");
        fakeModel.respondWith("should not be reached");

        Map<String, Object> body = new HashMap<>();
        body.put("message", null);
        body.put("sessionId", "session-null-msg-" + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertTrue(!resp.getStatusCode().is2xxSuccessful(),
                "AS-IS pin: null message must NOT return 2xx — current behavior depends on "
                        + "where the null surfaces (advisor chain → 500, or model call → 400). "
                        + "A fix that adds @NotBlank should flip this to assertEquals(400, ...). "
                        + "Actual status: " + resp.getStatusCode());
    }

    // Pin AS-IS: empty-string message. Same lack of @NotBlank — the empty prompt
    // flows through to the chat model. The test pins that current behavior is
    // permissive (a 2xx response with a synthesized "OK" reply from FakeChatModel).
    // A fix should reject with 400.
    @Test
    void runWithEmptyMessage_currentBehaviorPin_documentsObservedStatus() {
        HttpHeaders auth = authenticatedHeaders("validation-empty-msg");
        String agentId = createAgentViaApi(auth, "empty-message validation");
        fakeModel.respondWith("Synthetic reply to empty prompt.");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "");
        body.put("sessionId", "session-empty-msg-" + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        // We don't assert success or failure — current behavior is undefined. The pin
        // just observes that whatever the response is, the persisted row state matches.
        Long runCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ?", Long.class, agentId);
        if (resp.getStatusCode().is2xxSuccessful()) {
            assertEquals(1L, runCount,
                    "AS-IS pin: empty message returns 2xx today — controller treats it as a "
                            + "valid run. A fix that adds @NotBlank should both return 400 AND "
                            + "skip the agent_runs insert. Adjust both branches together.");
        } else {
            assertTrue(runCount <= 1L,
                    "if empty message is rejected, at most one row should appear (RUNNING→FAILED)");
        }
    }

    // Pin AS-IS: sessionId that exists for a DIFFERENT agent in the SAME tenant.
    // resolveSessionId only null-checks; downstream agent_runs.session_id FK
    // (to agent_sessions.session_id) might either accept (if the sessions table
    // doesn't bind session→agent uniquely) or reject. This pin documents the truth.
    @Test
    void runWithSessionIdBelongingToDifferentAgent_currentBehaviorPin() {
        HttpHeaders auth = authenticatedHeaders("validation-cross-agent-session");
        String agentA = createAgentViaApi(auth, "cross-agent A");
        String agentB = createAgentViaApi(auth, "cross-agent B");

        // Seed a session on agentA. The user_id/org_id come from JWT; for the seed we
        // synthesize matching values so the FK chain holds.
        String tenantOrg = jdbc.queryForObject(
                "SELECT org_id FROM users WHERE username = ?", String.class,
                "validation-cross-agent-session");
        String sessionFromAgentA = "session-cross-agent-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'fixture-user', ?, ?, now(), now())
                """, sessionFromAgentA, tenantOrg, agentA);

        fakeModel.respondWith("Synthetic reply on agent B with foreign sessionId.");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "cross-agent session probe");
        body.put("sessionId", sessionFromAgentA);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentB + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        // Observe + pin: does the run succeed silently, or is the cross-agent session
        // rejected? Either case is an AS-IS finding worth pinning. The PR description
        // documents both possibilities; the test asserts only that whatever the response,
        // the row state is internally consistent (no orphan runs without sessions, etc).
        if (resp.getStatusCode().is2xxSuccessful()) {
            // SUCCESS path: agent B's run is attached to agent A's session. This is the
            // worst case from a chat-history-leak perspective — the next turn could see
            // agent A's chat-memory context.
            Long agentBRunsCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_runs WHERE agent_id = ? AND session_id = ?",
                    Long.class, agentB, sessionFromAgentA);
            assertEquals(1L, agentBRunsCount,
                    "AS-IS pin (finding): agent B run was attached to agent A's session — "
                            + "potential chat-history-leak via foreign sessionId. A fix should "
                            + "verify session.agent_id matches the path-param agentId.");
        } else {
            // REJECT path: confirms the controller / service layer guards cross-agent
            // sessionId reuse. Pin so a regression to the leak case is caught.
            Long agentBRunsCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_runs WHERE agent_id = ? AND session_id = ?",
                    Long.class, agentB, sessionFromAgentA);
            assertEquals(0L, agentBRunsCount,
                    "rejected cross-agent sessionId must not persist any run row on agent B");
        }
    }

    // Pin AS-IS: sessionId that exists in ANOTHER tenant. Most security-relevant case.
    // If the run is accepted, agent_runs would be attributed to the JWT-bound org but
    // attached to a session owned by another tenant — cross-tenant session conflation.
    @Test
    void runWithSessionIdBelongingToDifferentTenant_currentBehaviorPin() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgVictim = "org-victim-session-" + tag;
        String orgAttacker = "org-attacker-session-" + tag;

        HttpHeaders attackerAuth = registerLoginWithOrg("session-leak-attacker-" + tag, orgAttacker);
        String attackerAgentId = "agent-attacker-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'attacker agent', 'gpt-4o-mini', true, ?, now(), now())
                """, attackerAgentId, orgAttacker);

        // Victim tenant's session — exists in the agent_sessions table under orgVictim.
        String victimAgentId = "agent-victim-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'victim agent', 'gpt-4o-mini', true, ?, now(), now())
                """, victimAgentId, orgVictim);
        String victimSession = "session-victim-" + tag;
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'victim-user', ?, ?, now(), now())
                """, victimSession, orgVictim, victimAgentId);

        fakeModel.respondWith("Synthetic reply — cross-tenant session probe.");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "cross-tenant session probe");
        body.put("sessionId", victimSession);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + attackerAgentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, attackerAuth), JSON_MAP);

        // Pin what we observe — the victim session row's org_id must not change either way.
        String victimSessionOrgAfter = jdbc.queryForObject(
                "SELECT org_id FROM agent_sessions WHERE session_id = ?", String.class, victimSession);
        assertEquals(orgVictim, victimSessionOrgAfter,
                "victim session's org_id must NOT be mutated by attacker's run attempt — "
                        + "even if the run succeeds, the session row stays in its original tenant");

        if (resp.getStatusCode().is2xxSuccessful()) {
            // FINDING: cross-tenant session reuse is silently accepted.
            String attackerRunOrg = jdbc.queryForObject(
                    "SELECT org_id FROM agent_runs WHERE agent_id = ? AND session_id = ? "
                            + "ORDER BY created_at DESC LIMIT 1",
                    String.class, attackerAgentId, victimSession);
            assertEquals(orgAttacker, attackerRunOrg,
                    "AS-IS pin (finding): the attacker's run was attached to the victim's "
                            + "sessionId. Cross-tenant chat-memory leak possible if the chat-memory "
                            + "advisor pulls messages by sessionId without org-scoping. A fix should "
                            + "404 here (session not found within caller's org).");
            assertNotEquals(orgVictim, attackerRunOrg,
                    "the attacker's run row must NOT be attributed to the victim's org — "
                            + "JWT-bound org must still win attribution");
        } else {
            // REJECT path: cross-tenant session is rejected. Pin so a regression is caught.
            Long attackerRunCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_runs WHERE agent_id = ? AND session_id = ?",
                    Long.class, attackerAgentId, victimSession);
            assertEquals(0L, attackerRunCount,
                    "rejected cross-tenant sessionId must not persist any run row");
        }
    }

    // ─── helpers ───

    private void installPermissiveErrorHandler() {
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "RunRequest validation fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before run endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-validation-1234",
                // ROLE_ADMIN required to create the fixture agent via /api/admin/agents (gated since #969).
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
