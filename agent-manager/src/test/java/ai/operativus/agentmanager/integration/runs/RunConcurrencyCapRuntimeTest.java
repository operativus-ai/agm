package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the concurrency-cap rejection contract for
 *   {@code AgentService.checkConcurrencyLimit}. Two caps stack in the production code:
 *   <ol>
 *     <li><strong>Per-agent FinOps cap</strong> — {@code def.maxConcurrentExecutions()}.
 *         When the count of {@code agent_runs} rows in {@code RUNNING} status for the
 *         specific agent reaches the cap, the next run dispatch throws
 *         {@code BusinessValidationException} which the {@code GlobalExceptionHandler}
 *         maps to HTTP 400.</li>
 *     <li><strong>JVM-wide orchestration cap</strong> — property
 *         {@code agent.orchestration.max-concurrent-calls} (default 10). When the count
 *         of {@code agent_runs} rows in {@code RUNNING} status for the same agent
 *         reaches this cap, the next dispatch is rejected even if the per-agent cap
 *         was not set or not yet hit.</li>
 *   </ol>
 *
 *   <p>Pre-pin reality: the production logic is well-defended (see
 *   {@code AgentService.java:1091-1112}) but no runtime test exercised the rejection
 *   path. A future refactor that rewires {@code runRepository.countByAgentIdAndStatus},
 *   the counter beans, or the BusinessValidationException → 400 mapping could regress
 *   silently.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>Per-agent cap rejection</strong>: agent configured with
 *         {@code maxConcurrentExecutions=1}, one RUNNING row already present for that
 *         agent → next POST returns HTTP 400 with the per-agent rejection message.</li>
 *     <li><strong>JVM-wide cap rejection</strong>: agent with no per-agent cap; the
 *         default global cap is 10, so 10 RUNNING rows seeded for the same agent
 *         exhaust the global slot → next POST returns HTTP 400 with the JVM-wide
 *         rejection message.</li>
 *     <li><strong>Cap clears once running rows finalize</strong>: regression-lock that
 *         the rejection is observed-state-driven (count of RUNNING rows), not
 *         monotonically incremented. After deleting the seeded RUNNING rows, the next
 *         dispatch succeeds.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunConcurrencyCapRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeChat;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agent_runs");
        jdbc.update("DELETE FROM agent_messages");
        jdbc.update("DELETE FROM agent_sessions");
        fakeChat.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void perAgentCapExceeded_returns400_persistsNoExtraRunningRow() {
        HttpHeaders auth = userHeaders("cap-per-agent");
        String agentId = createAgent(auth, "Cap fixture (per-agent)");
        // Configure the per-agent FinOps cap to exactly 1.
        jdbc.update("UPDATE agents SET max_concurrent_executions = 1 WHERE id = ?", agentId);

        // Seed a single RUNNING row to occupy the cap slot. The agent's own row count
        // for status=RUNNING is what checkConcurrencyLimit polls.
        seedRunningRunRow(agentId);

        Long runningBefore = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ? AND status = 'RUNNING'",
                Long.class, agentId);
        assertEquals(1L, runningBefore, "precondition: one seeded RUNNING row");

        fakeChat.respondWith("This response must never reach the wire — the cap should reject.");

        ResponseEntity<Map<String, Object>> resp = runAgent(auth, agentId,
                "should be rejected", "session-" + UUID.randomUUID());

        assertEquals(400, resp.getStatusCode().value(),
                "per-agent cap rejection must surface as HTTP 400 (BusinessValidationException → "
                        + "GlobalExceptionHandler maps to BAD_REQUEST). Anything else means the "
                        + "exception type changed or the exception handler regressed.");

        Long runningAfter = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ? AND status = 'RUNNING'",
                Long.class, agentId);
        assertEquals(1L, runningAfter,
                "rejected dispatch must NOT have persisted a second RUNNING row — the cap "
                        + "check fires BEFORE the AgentRun is saved (line 147 of AgentService."
                        + "run). A count > 1 here means the check moved or was bypassed and a "
                        + "leaky run-row landed despite the rejection.");
    }

    @Test
    void jvmWideCapExceeded_returns400_pinsDefaultGlobalLimitOfTen() {
        HttpHeaders auth = userHeaders("cap-global");
        String agentId = createAgent(auth, "Cap fixture (JVM-wide)");
        // Leave per-agent cap NULL so only the JVM-wide cap applies.

        // The default global cap is 10 (agent.orchestration.max-concurrent-calls=10).
        // Seed exactly 10 RUNNING rows for the same agent so countByAgentIdAndStatus
        // returns the cap, forcing the global rejection path.
        for (int i = 0; i < 10; i++) {
            seedRunningRunRow(agentId);
        }

        fakeChat.respondWith("Must not reach the wire — global cap should reject.");

        ResponseEntity<Map<String, Object>> resp = runAgent(auth, agentId,
                "should be rejected by global cap", "session-" + UUID.randomUUID());

        assertEquals(400, resp.getStatusCode().value(),
                "JVM-wide cap rejection must also surface as HTTP 400. The rejection message "
                        + "in the body should reference 'maximum concurrent capacity', distinct "
                        + "from the per-agent cap message — failure here means either the "
                        + "global cap check was bypassed or the default 10-cap property "
                        + "regressed.");

        if (resp.getBody() != null && resp.getBody().get("detail") instanceof String detail) {
            assertThat(detail)
                    .as("rejection detail must explicitly indicate the global capacity ceiling "
                            + "so operators can distinguish per-agent from JVM-wide rejections "
                            + "in support tickets")
                    .contains("maximum concurrent capacity");
        }
    }

    @Test
    void capClears_afterRunningRowsRemoved_nextDispatchSucceeds() {
        HttpHeaders auth = userHeaders("cap-clear");
        String agentId = createAgent(auth, "Cap clearing fixture");
        jdbc.update("UPDATE agents SET max_concurrent_executions = 1 WHERE id = ?", agentId);

        String seededRunId = seedRunningRunRow(agentId);

        // Confirm pre-cleared rejection.
        ResponseEntity<Map<String, Object>> blocked = runAgent(auth, agentId,
                "blocked while cap is occupied", "session-" + UUID.randomUUID());
        assertEquals(400, blocked.getStatusCode().value(),
                "precondition: dispatch must be rejected while the seeded RUNNING row "
                        + "occupies the per-agent cap slot");

        // Remove the seeded RUNNING row so the count drops below the cap.
        jdbc.update("DELETE FROM agent_runs WHERE id = ?", seededRunId);

        fakeChat.respondWith("Cap-cleared reply " + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> allowed = runAgent(auth, agentId,
                "should now pass", "session-" + UUID.randomUUID());
        assertEquals(200, allowed.getStatusCode().value(),
                "after the RUNNING row is removed, the next dispatch must succeed — the "
                        + "cap check is observed-state-driven (poll count of RUNNING rows) "
                        + "not monotonically incremented. A 400 here means the rejection "
                        + "decision is being cached across requests, which would deadlock "
                        + "any agent that ever once hit its cap.");
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private String seedRunningRunRow(String agentId) {
        // Mirror SessionsRuntimeTest's run-row seeding pattern. agent_id is FK to agents;
        // session_id is plain FK to agent_sessions (no cascade) per CLAUDE.md. We don't
        // need a session row for the count query since countByAgentIdAndStatus is on
        // agent_id alone.
        String runId = UUID.randomUUID().toString();
        String sessionId = "session-cap-seed-" + UUID.randomUUID();
        // Seed the session row first (FK target).
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'cap-seed-user', 'cap-seed-org', ?, now(), now())
                """, sessionId, agentId);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, input, user_id, org_id,
                                        status, created_at, updated_at)
                VALUES (?, ?, ?, 'seeded RUNNING input', 'cap-seed-user', 'cap-seed-org',
                        'RUNNING', ?, ?)
                """, runId, agentId, sessionId,
                LocalDateTime.now(), LocalDateTime.now());
        return runId;
    }

    private ResponseEntity<Map<String, Object>> runAgent(HttpHeaders auth, String agentId,
                                                          String message, String sessionId) {
        // TestRestTemplate's default error handler throws on 4xx — swap to a no-op so
        // we can assert against the ResponseEntity for rejection cases.
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        return rest.exchange(url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-cap-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "concurrency cap fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", false);
        body.put("addHistoryToMessages", false);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201");
        return agentId;
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-cap-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
