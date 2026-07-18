package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the failure-path contract for background runs
 *   ({@code POST /api/agents/{agentId}/runs/background} → {@code RunExecutionManager.submit}
 *   → virtual thread executes {@code AgentService.run}). The happy path is already pinned
 *   by {@code BackgroundRunsRuntimeTest}; this class covers the FAILED transition that
 *   fires when the chat model throws on the virtual thread.
 *
 *   <p><strong>Pre-pin reality:</strong> when a background run's chat call throws,
 *   {@code AgentService.run}'s catch block at line ~320 calls
 *   {@code agentRunFinalizer.finalizeRun(runId, FAILED, "Error: " + msg, ...)} before
 *   rethrowing as RuntimeException. The virtual thread's exception propagates up to
 *   {@code RunExecutionManager.submit}'s lambda where it's swallowed (the future is
 *   meant to be polled via the status endpoint, not via Future.get). Without this test,
 *   a future refactor that:
 *   <ul>
 *     <li>moves the finalize call outside the catch path,
 *     <li>renames or removes the {@code errorMessage} column,
 *     <li>or changes the FAILED status enum mapping,
 *   </ul>
 *   could silently leave failed background runs stuck at RUNNING forever — the worst
 *   possible UX (status polling reports RUNNING but the agent has long since crashed).
 *
 *   <p>Case: <strong>chat-model throw → FAILED + errorMessage persisted</strong>:
 *     script the FakeChatModel to throw RuntimeException on the virtual thread's chat
 *     call; assert {@code agent_runs.status=FAILED} and a non-null/non-empty
 *     {@code error_message} column lands on the row.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class BackgroundRunFailurePathRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private FakeChatModel fakeChat;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeChat.reset();
    }

    @Test
    void chatModelThrows_backgroundRunTransitionsToFailedWithErrorMessage() {
        HttpHeaders auth = authenticatedHeaders("bg-fail-runner");
        String agentId = createAgentViaApi(auth, "Background Failure Agent");

        // Script the FakeChatModel to throw on the virtual thread's chat call. The
        // RuntimeException propagates up through AgentService.run's catch block which
        // finalizes the run with status=FAILED + errorMessage before rethrowing.
        String injectedMessage = "S3 simulated chat-model outage " + UUID.randomUUID();
        fakeChat.respondWith(prompt -> { throw new RuntimeException(injectedMessage); });

        String sessionId = "session-bg-fail-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "trigger background failure",
                "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs/background"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "background-run enqueue must return 200 even when the underlying virtual "
                        + "thread is destined to throw — the failure is observed via status "
                        + "polling, not the enqueue response");
        String runId = (String) response.getBody().get("runId");

        // Poll for FAILED terminal state on the DB row. The virtual thread takes a few
        // millis but well under 10s.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status, error_message FROM agent_runs WHERE id = ?", runId);
            assertEquals("FAILED", runRow.get("status"),
                    "R3 pin: background run that throws must finalize to FAILED. RUNNING here "
                            + "means the catch-block finalizeRun call regressed and the row is "
                            + "stuck forever — polling clients would never observe the failure.");
            assertThat((String) runRow.get("error_message"))
                    .as("R3 pin: the failed run must persist a non-null errorMessage so "
                            + "operators and clients can diagnose. AgentService.run's catch "
                            + "block prefixes the message with 'Error: ' before passing to "
                            + "finalizeRun.")
                    .isNotNull()
                    .isNotBlank()
                    .contains(injectedMessage);
        });
    }

    // ─── helpers ───

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
        body.put("description", "Background failure path fixture");
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
                "fixture precondition: agent must exist before /runs/background reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-r3-1234",
                // ROLE_ADMIN required to create the fixture agent via /api/admin/agents (gated since #969).
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
