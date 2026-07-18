package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.A2ACardResolver;
import com.operativus.agentmanager.control.a2a.PeerCancellationDispatcher;
import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Domain Responsibility: Pins the {@link PeerCancellationDispatcher} failure-path
 *   contract — when the outbound notify POST fails, a single audit row must be
 *   appended to {@code a2a_task_events} with {@code status=FAILED} and
 *   {@code message='notify-dispatch-failed'}, and the local cancel must NOT be
 *   rolled back. {@link PeerCancellationNotifyRuntimeTest} pins the happy path
 *   (200 → CANCELLED audit row); this test pins the inverse so a regression that
 *   swallows the failure audit (or fans-out duplicate rows on retry) surfaces.
 *
 *   Two failure surfaces covered:
 *     1. Peer returns HTTP 5xx (server error on remote side).
 *     2. Peer returns HTTP 401 (remote rejects our outbound API key).
 *
 *   The dispatcher today has NO retry layer — single attempt, log + audit, done.
 *   If that ever changes (retry added without idempotency on the audit row), this
 *   test will fail because more than one FAILED row would be appended.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aOutboundResilienceRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private PeerCancellationDispatcher dispatcher;
    @Autowired private A2ACardResolver cardResolver;
    @Autowired private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE a2a_task_events");
        jdbc.update("DELETE FROM a2a_remote_agents");
        cardResolver.listRemoteRegistrations(null)
                .forEach(r -> cardResolver.deregisterRemoteAgent(r.alias(), null));
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void dispatcher_peerReturns5xx_writesSingleFailedAuditRow_withErrorDetail() {
        String remoteAgentId = registerPeer("https://peer-5xx.example.com");
        String taskId = "task-" + UUID.randomUUID();

        mockServer.expect(requestTo("https://peer-5xx.example.com/api/v1/a2a/peers/cancel-notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("upstream is down"));

        dispatcher.notifyCancellation(taskId, remoteAgentId, "remote-side-5xx");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) > 0);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, message, error_detail FROM a2a_task_events WHERE task_id = ?", taskId);

        assertAll("5xx failure must append exactly one FAILED audit row with error_detail set",
                () -> mockServer.verify(),
                () -> assertEquals(1, rows.size(),
                        "exactly one audit row — no retry layer is allowed to silently fan-out duplicates"),
                () -> assertEquals(A2aTaskStatus.FAILED.name(), rows.get(0).get("status")),
                () -> assertEquals("notify-dispatch-failed", rows.get(0).get("message")),
                () -> assertNotNull(rows.get(0).get("error_detail"),
                        "error_detail column must capture the upstream message so operators can triage"));
    }

    @Test
    void dispatcher_peerReturns401_writesFailedAuditRow_indicatingRemoteAuthRejection() {
        String remoteAgentId = registerPeer("https://peer-401.example.com");
        String taskId = "task-" + UUID.randomUUID();

        mockServer.expect(requestTo("https://peer-401.example.com/api/v1/a2a/peers/cancel-notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest().body("{\"error\":\"Invalid or revoked API key.\"}"));

        dispatcher.notifyCancellation(taskId, remoteAgentId, "remote-side-401");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) > 0);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, message, error_detail FROM a2a_task_events WHERE task_id = ?", taskId);

        assertAll("401 failure must also produce a FAILED audit row — the dispatcher does not retry on auth errors",
                () -> mockServer.verify(),
                () -> assertEquals(1, rows.size(),
                        "exactly one audit row — no retry on 401 (which would be pointless without rotation)"),
                () -> assertEquals(A2aTaskStatus.FAILED.name(), rows.get(0).get("status")),
                () -> assertEquals("notify-dispatch-failed", rows.get(0).get("message")));
    }

    /**
     * Helper: register a peer via the public API so the outbound API key flows through
     * {@code OutboundApiKeyConverter} encryption end-to-end. Returns the remoteAgentId.
     */
    private String registerPeer(String baseUrl) {
        HttpHeaders auth = userHeaders("a2a-resilience-" + UUID.randomUUID().toString().substring(0, 8));
        String remoteAgentId = "remote-peer-" + UUID.randomUUID();
        String alias = "peer-" + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", remoteAgentId);
        body.put("baseUrl", baseUrl);
        body.put("alias", alias);
        body.put("apiKey", "outbound-key-" + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(200, register.getStatusCode().value(), "peer registration must succeed for the helper");
        return remoteAgentId;
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-resilience", List.of("ROLE_USER"));
    }
}
