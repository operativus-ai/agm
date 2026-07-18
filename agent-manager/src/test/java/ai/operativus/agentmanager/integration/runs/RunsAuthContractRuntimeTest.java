package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.SseTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the auth contract on the chat run surface
 *   ({@code POST /api/agents/{id}/runs}, {@code POST /api/agents/{id}/runs/stream},
 *   {@code DELETE /api/agents/{id}/runs/{runId}}). The FE chat-api.ts relies on a
 *   401 response to clear localStorage and bounce the user to /login — without this
 *   pin, a regression in SecurityConfig that silently widened anonymous access on
 *   any of these endpoints would either leak run data or hang the FE in an
 *   unauthenticated-but-streaming state.
 *
 *   <p>Three pins:
 *   <ol>
 *     <li>Anonymous {@code POST /api/agents/{id}/runs} → 401 (or 403). NOT 200.
 *         NOT 500 with a partial body.</li>
 *     <li>Anonymous {@code POST /api/agents/{id}/runs/stream} → 401 (or 403) with
 *         NO SSE frames in the body. A partial SSE frame leak here would mean the
 *         filter chain let the request hit the controller before bouncing — clients
 *         consuming via {@code @microsoft/fetch-event-source} would render whatever
 *         leaked before treating the connection as closed.</li>
 *     <li>Anonymous {@code DELETE /api/agents/{id}/runs/{runId}} → 401 (or 403).
 *         An anonymous DELETE that succeeded would be a denial-of-service / IDOR
 *         (cancel any run with a guessed id; sibling of PR-A3).</li>
 *   </ol>
 *
 *   <p>The test accepts either 401 or 403 because Spring Security's anonymous-filter
 *   behavior depends on whether {@code AnonymousAuthenticationFilter} surfaces the
 *   anonymous principal (→ 403 from authz) or short-circuits at the JWT filter
 *   (→ 401). Either is acceptable as long as the controller does NOT see the request.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RunsAuthContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(10);

    private SseTestClient sse;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        sse = new SseTestClient("http://localhost:" + port);
        installPermissiveErrorHandler();
    }

    @Test
    void anonymousPostSyncRun_returnsUnauthorizedOrForbidden_andNoAgentRunRowIsPersisted() {
        // Use a made-up agentId — a real agent isn't required because the request must be
        // rejected BEFORE it reaches the controller. If we observed 404 here that would
        // mean auth was bypassed and the controller rejected on agent-not-found.
        String agentId = "agent-anon-probe-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "message", "anonymous probe",
                "sessionId", "session-anon-probe-" + UUID.randomUUID());

        // No Authorization header.
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, noAuth), JSON_MAP);

        HttpStatus status = HttpStatus.resolve(resp.getStatusCode().value());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous POST /runs must be 401 or 403 — anything else (200, 404, 500) "
                        + "means the request reached the controller. FE chat-api.ts depends on "
                        + "401 to bounce to /login. Actual: " + status);
        assertNotEquals(HttpStatus.NOT_FOUND, status,
                "404 here means auth was bypassed and the controller's agent-not-found check "
                        + "fired — a real auth regression. Auth must reject BEFORE the controller.");

        // No agent_runs row must materialize from an anonymous attempt.
        Long runCount = jdbc.queryForObject("SELECT count(*) FROM agent_runs", Long.class);
        assertEquals(0L, runCount,
                "anonymous POST /runs must not persist an agent_runs row — would be both a "
                        + "DoS vector (DB writes) and an audit-trail spoof");
    }

    @Test
    void anonymousPostStreamRun_returnsUnauthorizedOrForbidden_withNoSseFramesInBody() {
        String agentId = "agent-anon-stream-" + UUID.randomUUID();
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "message", "anonymous stream probe",
                    "sessionId", "session-anon-stream-" + UUID.randomUUID()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Bearer "" so the JWT filter sees a present-but-invalid header. This is the worst
        // case for SSE leak: if the controller starts emitting before authn fires, an
        // empty-bearer fetch would render whatever leaked.
        SseTestClient.SseResponse response = sse.postWithStatus(
                "/api/agents/" + agentId + "/runs/stream", body, "", SSE_TIMEOUT);

        HttpStatus status = HttpStatus.resolve(response.statusCode());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous POST /runs/stream must be 401 or 403 BEFORE the SSE handshake. "
                        + "Anything else means the controller saw the request and may have "
                        + "started emitting frames. Actual: " + status);

        // The body may contain a JSON error payload, but it must NOT contain any parsed
        // SSE frames carrying agent stream events. An SSE frame would mean the controller
        // started emitting before authn — any leak (even a single START) is a regression.
        for (ServerSentEvent<String> frame : response.events()) {
            String data = frame.data();
            if (data == null || data.isBlank()) continue;
            assertTrue(!data.contains("\"event\":\"START\"")
                            && !data.contains("\"event\":\"CONTENT_DELTA\"")
                            && !data.contains("\"event\":\"STOP\""),
                    "anonymous /runs/stream must NOT emit any SSE frame carrying an "
                            + "AgentStreamEvent payload. Body contained: " + data);
        }
    }

    @Test
    void anonymousDeleteCancelRun_returnsUnauthorizedOrForbidden_doesNotMutateRow() {
        // Seed a RUNNING run so we can verify the row is untouched after the rejected DELETE.
        String tag = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-anon-delete-" + tag;
        String runId = "run-anon-delete-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'anon-delete fixture', 'gpt-4o-mini', true, 'org-anon-fixture', now(), now())
                """, agentId);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, user_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, 'org-anon-fixture', NULL, 'RUNNING', 'fixture', NULL, now(), now())
                """, runId, agentId);

        HttpHeaders noAuth = new HttpHeaders();
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(noAuth), Void.class);

        HttpStatus status = HttpStatus.resolve(resp.getStatusCode().value());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous DELETE /runs/{id} must be 401 or 403 — a 204 here means anonymous "
                        + "callers can cancel any run with a guessed id (DoS + IDOR family). "
                        + "Actual: " + status);

        String statusAfter = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertEquals("RUNNING", statusAfter,
                "row must remain RUNNING after rejected anonymous DELETE — a CANCELLED here "
                        + "means the filter chain let the request hit the controller before bouncing");
    }

    // ─── helpers ───

    private void installPermissiveErrorHandler() {
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
    }
}
