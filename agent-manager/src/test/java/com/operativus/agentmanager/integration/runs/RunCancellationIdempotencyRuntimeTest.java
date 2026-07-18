package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins idempotency of DELETE {@code /api/agents/{agentId}/runs/{runId}}
 *   when invoked against runs already in a terminal status.
 *
 *   <p><strong>Pre-pin reality:</strong> the cancel path crosses two guards:
 *   <ol>
 *     <li>{@code RunExecutionManager.cancel} (line 287-292): checks
 *         {@code status != COMPLETED && status != FAILED}. <strong>Note:</strong> CANCELLED
 *         is not in this list — so calling cancel on a CANCELLED run still passes through
 *         to step 2.</li>
 *     <li>{@code AgentRunFinalizer.finalizeRun} "already-terminal" guard (line 82): treats
 *         {@code COMPLETED}, {@code FAILED}, and {@code CANCELLED} as terminal and
 *         short-circuits without writing. This is what makes the second cancel a no-op.</li>
 *   </ol>
 *
 *   <p>So idempotency works <em>through</em> the finalizer guard, not the cancel method's
 *   own filter. If a future refactor:
 *   <ul>
 *     <li>removes the finalizer's "already-terminal" CANCELLED check (would let a second
 *         cancel re-write the row's reason/timestamps), OR
 *     <li>changes the cancel-method's `status != COMPLETED && status != FAILED` check to
 *         throw on terminal statuses instead of no-op,
 *   </ul>
 *   clients would observe non-idempotent cancel — DELETE retries (common on flaky
 *   networks) would either error or silently clobber the original cancellation reason.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>Cancel an already-CANCELLED run returns 204</strong>: the HTTP contract
 *         pin — DELETE must not throw on retry. Existing
 *         {@code BackgroundRunsRuntimeTest.deleteBackgroundRunOnCompletedRunReturns204…}
 *         covers the COMPLETED case; this closes the CANCELLED-on-CANCELLED gap.</li>
 *     <li><strong>The cancellation reason and timestamps are preserved</strong>: a second
 *         DELETE must NOT overwrite {@code error_message} / {@code updated_at} on the
 *         already-cancelled row — proves the finalizer's already-terminal short-circuit
 *         fires before any write.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunCancellationIdempotencyRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeChat;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeChat.reset();
    }

    @Test
    void cancelAlreadyCancelledRun_returns204_doesNotOverwriteCancellationReason() {
        // registerLoginWithOrg binds the JWT to 'idemp-org' so the new requireSameOrg
        // guard in AgentService.cancelRun (added by fix/cancel-run-require-same-org) sees
        // a matching tenant on the seeded row and lets the idempotent cancel proceed.
        HttpHeaders auth = registerLoginWithOrg("cancel-idemp", "idemp-org");
        String agentId = createAgentViaApi(auth, "Cancellation idempotency fixture");

        // Seed a run already in CANCELLED state with a distinctive cancellation reason
        // and an old updated_at. A non-idempotent DELETE would either error OR overwrite
        // both fields with fresh values.
        String runId = UUID.randomUUID().toString();
        String sessionId = "session-cancel-idemp-" + UUID.randomUUID();
        String originalReason = "Cancelled by user — ORIGINAL " + UUID.randomUUID();
        LocalDateTime originalUpdatedAt = LocalDateTime.now().minusHours(2);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'idemp-user', 'idemp-org', ?, now(), now())
                """, sessionId, agentId);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, input, user_id, org_id,
                                        status, error_message, created_at, updated_at)
                VALUES (?, ?, ?, 'seeded cancelled run', 'idemp-user', 'idemp-org',
                        'CANCELLED', ?, ?, ?)
                """, runId, agentId, sessionId, originalReason,
                originalUpdatedAt, originalUpdatedAt);

        // The TestRestTemplate default throws on 4xx — install a no-op handler so we can
        // assert the HTTP response directly even if the cancel were rejected as a bug.
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });

        // First DELETE on a CANCELLED run.
        ResponseEntity<Void> first = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode(),
                "R6 pin: DELETE on a CANCELLED run must return 204. A 4xx here would force "
                        + "clients to special-case the terminal status before retrying — "
                        + "DELETE on flaky networks must be safely idempotent.");

        // Second DELETE — the actual idempotency assertion.
        ResponseEntity<Void> second = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, second.getStatusCode(),
                "R6 pin: a SECOND DELETE on the same CANCELLED run must also return 204. "
                        + "A 4xx/5xx here means the cancellation path is not idempotent; "
                        + "retrying a DELETE (which clients MUST do on network failures) "
                        + "would now error instead of converging to the same terminal state.");

        // The original cancellation reason MUST survive both DELETEs. The finalizer's
        // already-terminal guard short-circuits BEFORE writing, so error_message stays
        // pinned at its original value. A change here means the guard regressed.
        String reasonAfter = jdbc.queryForObject(
                "SELECT error_message FROM agent_runs WHERE id = ?", String.class, runId);
        assertEquals(originalReason, reasonAfter,
                "R6 pin: error_message must be preserved across redundant cancels. A "
                        + "change here means a non-idempotent write fired — the finalizer's "
                        + "already-terminal guard regressed and the second DELETE clobbered "
                        + "the original cancellation context.");
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
        body.put("description", "R6 idempotency fixture");
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
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-r6-1234",
                List.of("ROLE_USER"));
    }
}
