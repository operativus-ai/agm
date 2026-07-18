package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.a2a.A2ACardResolver;
import ai.operativus.agentmanager.control.a2a.PeerCancellationDispatcher;
import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import ai.operativus.agentmanager.core.entity.A2aTaskEventEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

/**
 * Domain Responsibility: §22.5 focused coverage of the cross-peer cancellation
 *   notification round-trip:
 *   <ul>
 *     <li>Outbound: {@link PeerCancellationDispatcher} resolves the initiating
 *         peer and POSTs to {@code {peer.baseUrl}/api/v1/a2a/peers/cancel-notify}
 *         with {@code X-A2A-Api-Key}.</li>
 *     <li>Inbound: {@code POST /api/v1/a2a/peers/cancel-notify} appends an audit
 *         row to {@code a2a_task_events} tagged {@code "notify-received"}.</li>
 *   </ul>
 *
 * Strategy: outbound calls are intercepted with {@link MockRestServiceServer}
 *   bound to the shared {@link RestTemplate} bean — no real network traffic.
 *   The dispatcher writes the outbound POST on a fresh virtual thread, so
 *   tests poll for the resulting audit row before verifying the mock server.
 *   {@code @DirtiesContext(AFTER_CLASS)} forces Spring to rebuild the
 *   {@link RestTemplate} bean after this class finishes — {@link MockRestServiceServer}
 *   swaps the bean's {@code ClientHttpRequestFactory} with one that returns
 *   {@code createUnexpectedRequestError} for any non-scripted call, which
 *   would otherwise poison downstream tests (e.g. {@code PeerHealthMonitor}
 *   in {@code A2aMeshRuntimeTest}) that share the cached context.
 *
 * State: Stateless. Autowires the dispatcher, repository, and the shared
 *   {@code RestTemplate} so the mock server and the production code share the
 *   same HTTP client instance.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class PeerCancellationNotifyRuntimeTest extends BaseIntegrationTest {

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

    // ─── §22.5 Case 1 — Dispatcher POSTs to initiating peer with auth header ───

    /**
     * Registers a peer through the public API (so the outbound API key flows
     * through {@code OutboundApiKeyConverter} end-to-end), primes the mock
     * server to expect exactly one POST with the notify payload + plaintext
     * {@code X-A2A-Api-Key}, then invokes the dispatcher directly. Proves
     * that the v-thread dispatch path (a) resolves the peer by
     * {@code remoteAgentId}, (b) decrypts the stored outbound key, (c) POSTs
     * the correct URL and body, and (d) appends a dispatched audit row.
     */
    @Test
    void dispatcher_postsCancelNotifyToInitiatingPeer_andAppendsAuditRow() {
        HttpHeaders auth = userHeaders("notify-case1");
        String remoteAgentId = "remote-peer-" + UUID.randomUUID();
        String alias         = "peer-" + UUID.randomUUID();
        String plaintextKey  = "outbound-key-" + UUID.randomUUID();
        String baseUrl       = "https://peer.example.com";

        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", remoteAgentId);
        body.put("baseUrl", baseUrl);
        body.put("alias", alias);
        body.put("apiKey", plaintextKey);
        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(200, register.getStatusCode().value(), "peer registration must succeed");

        String taskId = "task-" + UUID.randomUUID();
        String reason = "cancel-reason-" + UUID.randomUUID();

        mockServer.expect(requestTo(baseUrl + "/api/v1/a2a/peers/cancel-notify"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-A2A-Api-Key", plaintextKey))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("{\"taskId\":\"" + taskId + "\",\"reason\":\"" + reason + "\"}"))
                .andRespond(withNoContent());

        dispatcher.notifyCancellation(taskId, remoteAgentId, reason);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND message LIKE 'notify-dispatched%'",
                        Integer.class, taskId) > 0);

        List<A2aTaskStatus> statuses = jdbc.query(
                "SELECT status FROM a2a_task_events WHERE task_id = ? AND message LIKE 'notify-dispatched%'",
                (rs, i) -> A2aTaskStatus.valueOf(rs.getString(1)),
                taskId);

        assertAll("case 1 — dispatcher posts to initiating peer",
                () -> mockServer.verify(),
                () -> assertEquals(1, statuses.size(),
                        "dispatcher must append exactly one notify-dispatched audit row"),
                () -> assertEquals(A2aTaskStatus.CANCELLED, statuses.get(0),
                        "dispatched audit row must carry status=CANCELLED — the notify is a cancel signal"));
    }

    // ─── §22.5 Case 2 — Unknown initiatingAgentId no-ops silently ───

    /**
     * If the {@code initiatingAgentId} has no matching registration, the
     * dispatcher logs at INFO and returns without spawning the outbound
     * virtual thread. No HTTP call, no audit row. Proves the cancel path
     * is robust against peer-less deployments and internal-only initiations.
     */
    @Test
    void dispatcher_noPeerRegistered_noOutboundCall_noAuditRow() {
        String taskId = "task-" + UUID.randomUUID();
        String unknownInitiator = "unknown-peer-" + UUID.randomUUID();

        dispatcher.notifyCancellation(taskId, unknownInitiator, "should not dispatch");

        // The dispatcher returns synchronously when no peer is found, so a brief pause
        // is sufficient to assert "no audit row appeared" without racing a v-thread.
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?", Integer.class, taskId);

        assertAll("case 2 — unknown initiator is a no-op",
                () -> mockServer.verify(),
                () -> assertEquals(0, auditRows,
                        "no audit row must be written when the initiator has no registered peer — "
                                + "the local CANCELLED audit from A2ATaskExecutor is the only trail"));
    }

    // ─── §22.5 Case 3 — Inbound /cancel-notify appends CANCELLED audit row ───

    /**
     * Direct HTTP hit on the new receive endpoint asserts that a well-formed
     * notification is 204'd and durably audited. Proves the symmetry of the
     * contract: what we dispatch, we accept.
     */
    @Test
    void inboundCancelNotify_appendsCancelledAuditRowTaggedNotifyReceived() {
        HttpHeaders auth = userHeaders("notify-case3");
        String taskId = "task-" + UUID.randomUUID();
        String reason = "peer-said-cancel";

        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("reason", reason);

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"),
                HttpMethod.POST, new HttpEntity<>(body, auth), Void.class);

        List<A2aTaskEventEntity> events = jdbc.query(
                "SELECT task_id, status, message, target_agent_id FROM a2a_task_events WHERE task_id = ?",
                (rs, i) -> {
                    A2aTaskEventEntity e = new A2aTaskEventEntity();
                    e.setTaskId(rs.getString(1));
                    e.setStatus(A2aTaskStatus.valueOf(rs.getString(2)));
                    e.setMessage(rs.getString(3));
                    e.setTargetAgentId(rs.getString(4));
                    return e;
                },
                taskId);

        assertAll("case 3 — inbound endpoint persists CANCELLED audit row",
                () -> assertEquals(204, response.getStatusCode().value(),
                        "POST /peers/cancel-notify returns 204 No Content on valid payload"),
                () -> assertEquals(1, events.size(),
                        "exactly one a2a_task_events row must be appended for the notify"),
                () -> assertEquals(A2aTaskStatus.CANCELLED, events.get(0).getStatus(),
                        "inbound notify is recorded as CANCELLED — mirrors the outbound semantic"),
                () -> assertNotNull(events.get(0).getMessage()),
                () -> assertTrue(events.get(0).getMessage().startsWith("notify-received"),
                        "message must be tagged 'notify-received' so operators can distinguish "
                                + "peer-origin cancellations from local executor cancels"),
                () -> assertTrue(events.get(0).getMessage().contains(reason),
                        "reason payload must be preserved in the audit message"),
                () -> assertEquals("peer-notify-inbound", events.get(0).getTargetAgentId(),
                        "target_agent_id is a sentinel for inbound notifies — no local agent is "
                                + "the target of the receive-side audit row"));
    }

    // ─── §22.5 Case 4 — Inbound rejects missing taskId with 400 ───

    /**
     * Guard contract: the correlation {@code taskId} is required. A missing
     * or blank value must produce 400 so a misconfigured peer surfaces the
     * error loudly instead of silently appending orphan audit rows.
     */
    @Test
    void inboundCancelNotify_missingTaskIdReturns400_noAuditRow() {
        HttpHeaders auth = userHeaders("notify-case4");
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "oops");

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"),
                HttpMethod.POST, new HttpEntity<>(body, auth), Void.class);

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events", Integer.class);

        assertAll("case 4 — missing taskId rejected without side effect",
                () -> assertEquals(400, response.getStatusCode().value(),
                        "POST /peers/cancel-notify must return 400 when taskId is absent"),
                () -> assertFalse(auditRows > 0,
                        "no audit row must be appended for a rejected request — guard is pre-persistence"));
    }

    // ─── helpers ───

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-notify-1234", List.of("ROLE_USER"));
    }
}
