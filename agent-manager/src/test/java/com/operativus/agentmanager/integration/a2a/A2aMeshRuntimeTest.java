package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.A2ACardResolver;
import com.operativus.agentmanager.control.a2a.PeerHealthMonitor;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the A2A (Agent-to-Agent)
 *   interoperability mesh — the {@code /api/v1/a2a/**} REST surface
 *   ({@link com.operativus.agentmanager.control.controller.A2AController}), the
 *   {@link A2ACardResolver} in-memory peer registry, the
 *   {@link PeerHealthMonitor} JDBC-backed health-check loop, and the
 *   {@link com.operativus.agentmanager.control.security.OutboundApiKeyConverter}
 *   AES-256-GCM encryption layer.
 * State: Stateless. Autowires the live {@link A2ACardResolver} and
 *   {@link PeerHealthMonitor} beans for white-box assertions where the HTTP
 *   surface alone cannot prove the contract.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §22.
 *
 * Pre-existing bugs fixed alongside this test (see commit history):
 *   1. {@code A2aRemoteAgentEntity.cached_card} now carries
 *      {@code @JdbcTypeCode(SqlTypes.JSON)} so Hibernate binds the String as JSON;
 *      previously {@code PeerHealthMonitor} saves crashed silently in the virtual
 *      thread and the trust flip never persisted.
 *   2. {@link A2ACardResolver} now persists peer registrations to
 *      {@code a2a_remote_agents} via {@code A2aRemoteAgentRepository} on
 *      {@code registerRemoteAgent} / {@code deregisterRemoteAgent} — controller
 *      writes are now visible to {@link PeerHealthMonitor} and the
 *      {@link OutboundApiKeyConverter} encrypts the outbound key at rest.
 *
 * Resolved §22.5 cross-peer cancellation notify (see {@code PeerCancellationDispatcher}):
 *   {@code A2ATaskExecutor} now fires a best-effort notify POST to the initiating
 *   peer on cancel, and {@code POST /api/v1/a2a/peers/cancel-notify} is wired as
 *   the inbound receive hook. Full round-trip coverage lives in
 *   {@code PeerCancellationNotifyRuntimeTest}; case 5 below keeps a 404 shape
 *   assertion for the unknown-task branch.
 *
 * Resolved §22.6 key rotation: covered by
 *   {@link OutboundApiKeyRotationRuntimeTest} which exercises the versioned-key
 *   wire format and {@code OutboundApiKeyMigrationService} end-to-end against
 *   real Postgres.
 *
 * Resolved §22.7 cross-org isolation (migration 025, A2ACardResolver scoping):
 *   peers are now keyed by {@code (orgId, alias)} both in memory and in
 *   {@code a2a_remote_agents}, and {@link com.operativus.agentmanager.control.controller.A2AController}
 *   reads {@code X-Org-Id} on register / list / deregister. Case 7 asserts the
 *   isolation — org B must not see org A's peers.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aMeshRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;
    @Autowired private A2ACardResolver cardResolver;
    @Autowired private PeerHealthMonitor peerHealthMonitor;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
        // The in-memory remote registry survives across tests — wipe both the DB
        // and the unscoped (legacy) partition of the in-memory map so case
        // ordering does not contaminate cross-org / list-leak assertions. Per-org
        // partitions added by case 7 are fresh UUIDs so cross-run bleed is safe.
        jdbc.update("DELETE FROM a2a_remote_agents");
        cardResolver.listRemoteRegistrations(null)
                .forEach(r -> cardResolver.deregisterRemoteAgent(r.alias(), null));
    }

    // ─── §22 Case 1 — Register peer; row persisted with ciphertext at rest ───

    /**
     * Spec §22.1. Expectation: "Register peer with encrypted API key → ciphertext at
     *   rest; peer row visible in list." {@link A2ACardResolver} now writes through
     *   to {@code a2a_remote_agents} via {@code A2aRemoteAgentRepository}, so each
     *   {@code POST /api/v1/a2a/peers} produces exactly one persisted row whose
     *   {@code outbound_api_key} column is AES-256-GCM-encrypted by
     *   {@link OutboundApiKeyConverter} (the test profile sets
     *   {@code agm.security.outbound-key-encryption-key} so encryption is active).
     *
     * We assert: (a) the registration is visible via {@code GET /peers}, (b) a
     *   single row exists in {@code a2a_remote_agents}, and (c) the raw column
     *   value differs from the plaintext we sent — proving the converter ran on
     *   the write path.
     */
    @Test
    void registerPeer_persistsRow_andEncryptsOutboundApiKeyAtRest() {
        HttpHeaders auth = userHeaders("a2a-case1");
        String alias = "peer-" + UUID.randomUUID();
        String plaintextKey = "secret-outbound-key-" + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-agent-1");
        body.put("baseUrl", "https://peer.example.com");
        body.put("alias", alias);
        body.put("apiKey", plaintextKey);

        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(200, register.getStatusCode().value(),
                "POST /peers must succeed for a valid public-internet base URL");
        assertNotNull(register.getBody());
        assertEquals(alias, register.getBody().get("alias"));

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(200, list.getStatusCode().value());
        assertNotNull(list.getBody());
        boolean visible = list.getBody().stream().anyMatch(p -> alias.equals(p.get("alias")));

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ?", Integer.class, alias);
        String storedCiphertext = jdbc.queryForObject(
                "SELECT outbound_api_key FROM a2a_remote_agents WHERE alias = ?", String.class, alias);

        assertAll("case 1 — peer persisted with encrypted outbound key",
                () -> assertTrue(visible, "registered peer must be visible via GET /peers"),
                () -> assertEquals(1, persistedRows,
                        "exactly one a2a_remote_agents row must be inserted by the registration call"),
                () -> assertNotNull(storedCiphertext, "outbound_api_key column must not be null after register"),
                () -> assertFalse(plaintextKey.equals(storedCiphertext),
                        "OutboundApiKeyConverter must transform the plaintext on write — the raw column "
                                + "value must differ from the API-supplied plaintext (proves AES-256-GCM "
                                + "encryption is on the write path for controller-registered peers)."));
    }

    // ─── §22 Case 2 — Delete peer removes the registration ───

    /**
     * Spec §22.2. Expectation: "Delete peer (DELETE /peers/{alias}) → row gone;
     *   outbound auth to that peer immediately fails." As-shipped, deregistration
     *   removes the entry from the in-memory map; the second DELETE returns 404.
     *
     * "Outbound auth fails" cannot be asserted in-process — the resolver no
     *   longer has the credential, so {@code resolveCard} returns
     *   {@link java.util.Optional#empty()} for any subsequent lookup, which is
     *   the proxy we assert here.
     */
    @Test
    void deregisterPeer_removesRegistration_andDeletesPersistedRow() {
        HttpHeaders auth = userHeaders("a2a-case2");
        String alias = "peer-" + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-agent-2");
        body.put("baseUrl", "https://peer.example.com");
        body.put("alias", alias);
        body.put("apiKey", "k-" + UUID.randomUUID());
        rest.exchange(url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        ResponseEntity<Void> firstDelete = rest.exchange(
                url("/api/v1/a2a/peers/" + alias), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        ResponseEntity<Void> secondDelete = rest.exchange(
                url("/api/v1/a2a/peers/" + alias), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        boolean stillInMemory = cardResolver.listRemoteRegistrations(null).stream()
                .anyMatch(r -> alias.equals(r.alias()));
        Integer persistedRowsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ?", Integer.class, alias);

        assertAll("case 2 — deregistration is idempotent and clears both stores",
                () -> assertEquals(204, firstDelete.getStatusCode().value(),
                        "first DELETE returns 204 No Content"),
                () -> assertEquals(404, secondDelete.getStatusCode().value(),
                        "second DELETE returns 404 — the alias is gone"),
                () -> assertFalse(stillInMemory,
                        "deregistered alias must not appear in A2ACardResolver's in-memory registry"),
                () -> assertEquals(0, persistedRowsAfter,
                        "the persisted a2a_remote_agents row must be deleted alongside the in-memory entry "
                                + "so PeerHealthMonitor stops health-checking the deregistered peer"));
    }

    // ─── §22 Case 3 — PeerHealthMonitor marks JDBC-backed peers untrusted after 3 failures ───

    /**
     * Spec §22.3. Expectation: "PeerHealthMonitor marks unreachable peers
     *   unhealthy; metric updates." {@link PeerHealthMonitor} reads
     *   {@code a2a_remote_agents} via {@code A2aRemoteAgentRepository} and flips
     *   {@code trusted = false} after {@code FAILURE_THRESHOLD = 3} consecutive
     *   RestTemplate failures against {@code {baseUrl}/api/v1/health}.
     *
     * Strategy: register a peer through the public API at a guaranteed-unreachable
     *   URL ({@code http://127.0.0.1:1}) — this exercises the
     *   {@link A2ACardResolver}-to-JPA write path so a real persisted row exists.
     *   Then invoke {@code checkPeerHealth()} repeatedly with brief pauses so each
     *   spawned virtual thread can merge its failure into the
     *   {@code consecutiveFailures} counter and (with the {@code @JdbcTypeCode(SqlTypes.JSON)}
     *   fix on {@code cached_card}) persist {@code trusted = false}.
     */
    @Test
    void peerHealthMonitor_marksUnreachablePeerUntrustedAfterThreeFailures() throws Exception {
        HttpHeaders auth = userHeaders("a2a-case3");
        String alias = "peer-health-" + UUID.randomUUID();

        // Insert directly via JDBC — the public registration endpoint rejects
        // loopback URLs via the SSRF guard, so we can't use POST /peers for an
        // unreachable target. The entity-level cached_card binding fix means
        // PeerHealthMonitor's save() now succeeds. outbound_api_key is left NULL
        // because OutboundApiKeyConverter would try to AES-decrypt a raw plaintext
        // seed and fail on read — case 1 already covers the encryption path.
        String peerId = "peer-id-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO a2a_remote_agents
                    (id, remote_agent_id, base_url, alias, outbound_api_key,
                     security_tier, trusted, created_at, updated_at)
                VALUES (?, ?, 'http://127.0.0.1:1', ?, NULL,
                        1, true, now(), now())
                """, peerId, "remote-3", alias);

        // checkPeerHealth() probes the unreachable base URL asynchronously (spawning a virtual
        // thread per trusted peer that, after FAILURE_THRESHOLD failures, persists trusted=false),
        // so we keep driving the check and poll the DB until trusted flips — robust to async timing.
        //
        // Poll every 2s (not 500ms): the probe to 127.0.0.1:1 refuses instantly, so 3 failures
        // accumulate within ~6s regardless of cadence; a 500ms cadence instead spawned ~120
        // probe+save virtual threads over the window, and in the nightly full-suite run that swarm
        // — competing with every other suite for the shared Hikari pool — left the trusted=false
        // save unable to land within 60s (the run failed with a Hikari "connection closed"
        // mid-wait). The slower cadence shrinks this test's pool footprint ~4x; 90s adds headroom.
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> {
                    peerHealthMonitor.checkPeerHealth();
                    return Boolean.FALSE.equals(jdbc.queryForObject(
                            "SELECT trusted FROM a2a_remote_agents WHERE alias = ?", Boolean.class, alias));
                });

        Boolean trusted = jdbc.queryForObject(
                "SELECT trusted FROM a2a_remote_agents WHERE alias = ?", Boolean.class, alias);

        Map<String, Object> healthStatus = peerHealthMonitor.getPeerHealthStatus();
        Map<String, Object> peerStatus = (Map<String, Object>) healthStatus.get(peerId);
        int failures = peerStatus != null
                ? ((Number) peerStatus.getOrDefault("consecutiveFailures", 0)).intValue()
                : 0;

        assertFalse(Boolean.TRUE.equals(trusted),
                "PeerHealthMonitor must flip trusted=false after >= 3 consecutive failures against the "
                        + "unreachable base URL. Observed consecutiveFailures=" + failures
                        + ", DB trusted column=" + trusted + ". Proves both the read AND save paths against "
                        + "a2a_remote_agents are wired end-to-end.");
        assertNotNull(auth, "auth fixture exists so the helper does not warn about an unused method");
    }

    // ─── §22 Case 4 — submitTask returns SSE and persists lifecycle audit rows ───

    /**
     * Spec §22.4. Expectation: "Submit a2a task (POST /tasks, SSE) → streams
     *   progress; completes with result; row reflects end state." As-shipped,
     *   {@code A2ATaskExecutor} returns an
     *   {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
     *   immediately AND persists every lifecycle transition to
     *   {@code a2a_task_events} via {@code A2ATaskExecutor#audit} (which dispatches
     *   the JPA save on a separate virtual thread so DB latency cannot starve the
     *   SSE stream).
     *
     * We assert: (a) {@code POST /tasks} returns 200 with
     *   {@code Content-Type: text/event-stream}, and (b) at least one
     *   {@code a2a_task_events} row appears for the submitted task ID, carrying
     *   the {@code initiatingAgentId} we sent — proves the audit pipeline is live.
     *   Audit writes are async (separate virtual thread per event) so we poll up
     *   to 5s before failing.
     */
    @Test
    void submitTask_returnsSseStream_andPersistsLifecycleAuditRow() throws Exception {
        HttpHeaders auth = userHeaders("a2a-case4");
        String agentId = createAgent(auth, "T044 case 4 target agent");
        fakeModel.respondWith("ack");

        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "hello from a2a peer");
        body.put("initiatingAgentId", "peer-init-case4");
        body.put("sessionId", "sess-" + UUID.randomUUID());

        ResponseEntity<String> sse = rest.exchange(
                url("/api/v1/a2a/tasks"), HttpMethod.POST, new HttpEntity<>(body, auth), String.class);

        long deadline = System.currentTimeMillis() + 5_000L;
        Integer eventRows = 0;
        while (System.currentTimeMillis() < deadline) {
            eventRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?", Integer.class, taskId);
            if (eventRows != null && eventRows > 0) break;
            Thread.sleep(100);
        }
        Integer rowsFinal = eventRows;

        assertAll("case 4 — SSE wired and audit pipeline persists lifecycle events",
                () -> assertEquals(200, sse.getStatusCode().value(),
                        "POST /tasks returns 200 — SseEmitter is constructed and the response is streamed"),
                () -> assertTrue(sse.getHeaders().getContentType() != null
                                && sse.getHeaders().getContentType().toString().startsWith("text/event-stream"),
                        "Content-Type must be text/event-stream — confirms the SSE produces() contract"),
                () -> assertTrue(rowsFinal != null && rowsFinal > 0,
                        "a2a_task_events must contain at least one row for the submitted taskId — "
                                + "A2ATaskExecutor.audit() dispatches the save on a virtual thread for "
                                + "every lifecycle transition (SUBMITTED, WORKING, COMPLETED, …)."));
    }

    // ─── §22 Case 5 — Cancel an unknown task returns 404 (shape pin) ───

    /**
     * Spec §22.5. Expectation: "Cancel a2a task (DELETE /tasks/{id}) →
     *   downstream peer is notified; local row = CANCELLED." As-shipped:
     *   {@code A2ATaskExecutor#cancelTask} returns true if the task is
     *   interrupted, false if unknown/terminal; the interrupt branch now
     *   fires {@code PeerCancellationDispatcher#notifyCancellation} so the
     *   originating peer learns of the propagation.
     *
     * This case keeps a narrow shape assertion — DELETE against an unknown
     *   task ID returns 404 and no notify is fired (there's no task to cancel).
     *   Full outbound dispatch + inbound receive coverage lives in
     *   {@code PeerCancellationNotifyRuntimeTest}.
     */
    @Test
    void cancelTask_unknownIdReturns404() {
        HttpHeaders auth = userHeaders("a2a-case5");
        String unknownId = "task-unknown-" + UUID.randomUUID();

        ResponseEntity<Void> cancel = rest.exchange(
                url("/api/v1/a2a/tasks/" + unknownId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(404, cancel.getStatusCode().value(),
                "DELETE /tasks/{unknownId} returns 404 — A2ATaskExecutor.cancelTask returns false for "
                        + "unknown task IDs. Full cross-peer notify round-trip is covered by "
                        + "PeerCancellationNotifyRuntimeTest.");
    }

    // §22 Case 6 — Encryption key rotation: covered by OutboundApiKeyRotationRuntimeTest.

    // ─── §22 Case 7 — Cross-org peer isolation (org B must not see org A's peers) ───

    /**
     * Spec §22.7. Expectation: "Cross-org peer isolation: org A cannot see or
     *   invoke org B peers." After migration 025 + {@link A2ACardResolver}
     *   org-scoping, peer registrations are keyed by {@code (orgId, alias)} in
     *   both the in-memory map and the {@code a2a_remote_agents} table, and
     *   {@link com.operativus.agentmanager.control.controller.A2AController}
     *   reads the {@code X-Org-Id} header on register / list / deregister.
     *
     * Strategy: register a peer with {@code X-Org-Id: org-A}, then list with
     *   {@code X-Org-Id: org-B}. Org B must NOT see org A's alias. We also
     *   verify that the same list call with {@code X-Org-Id: org-A} DOES see
     *   the peer — proving the scoping is the mechanism, not an empty-result
     *   false positive.
     */
    @Test
    void crossOrgPeerIsolation_listPeersScopesByOrgId() {
        // TenantContextFilter precedence is: (1) JWT org_id claim → (2) X-Org-Id header →
        // (3) SecurityContext. Since the JWT carries the user's bound org claim post-PR
        // #927, the header in this test would be ignored. Use registerLoginWithOrg so the
        // JWT itself carries the target org_id.
        HttpHeaders authA = registerLoginWithOrg("a2a-case7-orgA-" + UUID.randomUUID(), "org-A");
        HttpHeaders authB = registerLoginWithOrg("a2a-case7-orgB-" + UUID.randomUUID(), "org-B");

        String aliasA = "orgA-peer-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-orgA");
        body.put("baseUrl", "https://orgA-peer.example.com");
        body.put("alias", aliasA);
        body.put("apiKey", "orgA-key-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, authA), JSON_MAP);
        assertEquals(200, register.getStatusCode().value());

        ResponseEntity<List<Map<String, Object>>> listAsB = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.GET, new HttpEntity<>(authB), JSON_LIST);
        ResponseEntity<List<Map<String, Object>>> listAsA = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.GET, new HttpEntity<>(authA), JSON_LIST);

        assertNotNull(listAsB.getBody());
        assertNotNull(listAsA.getBody());
        boolean orgBSeesOrgA = listAsB.getBody().stream().anyMatch(p -> aliasA.equals(p.get("alias")));
        boolean orgASeesOrgA = listAsA.getBody().stream().anyMatch(p -> aliasA.equals(p.get("alias")));

        Integer persistedOrgId = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ? AND org_id = ?",
                Integer.class, aliasA, "org-A");

        assertAll("case 7 — cross-org peer isolation enforced end-to-end",
                () -> assertEquals(200, listAsB.getStatusCode().value()),
                () -> assertEquals(200, listAsA.getStatusCode().value()),
                () -> assertFalse(orgBSeesOrgA,
                        "§22.7: org B must NOT see org A's peer alias — A2ACardResolver now scopes "
                                + "registrations by (orgId, alias) and GET /peers filters by X-Org-Id."),
                () -> assertTrue(orgASeesOrgA,
                        "§22.7 sanity: org A must still see its own peer — proves the scoping is the "
                                + "mechanism, not a false-positive empty-result for every caller."),
                () -> assertEquals(1, persistedOrgId,
                        "§22.7: the persisted row must carry org_id = org-A so PeerHealthMonitor and "
                                + "future migrations can still discover tenant ownership."));
    }

    // ─── helpers ───

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T044 fixture");
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
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201 before a2a tests reference it");
        return agentId;
    }

    /**
     * Pins {@code GET /api/v1/a2a/cards} — the local-capability discovery endpoint
     *   that remote A2A peers hit to introspect what this AGM instance can do.
     *   {@link com.operativus.agentmanager.control.controller.A2AController#listCards}
     *   delegates to {@link A2ACardResolver#listLocalCards()} which reads the live
     *   {@code AgentRegistry}. This test pins the wire-shape contract:
     *   (a) authenticated callers receive 200 with a JSON array body; (b)
     *   unauthenticated requests are rejected at the JWT filter (401) — the
     *   endpoint is not anonymous-discoverable.
     *
     * Audit anchor: {@code docs/analysis/agm-left.md} §11 — the doc flagged the
     *   {@code A2ACardResolver} REST wiring as "verify if shipped." This test
     *   pins the shipped contract.
     */
    @Test
    void listLocalCards_returnsArrayForAuthAndRejectsUnauth() {
        HttpHeaders auth = userHeaders("a2a-cards-listener");

        ResponseEntity<List<Map<String, Object>>> authedResp = rest.exchange(
                url("/api/v1/a2a/cards"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(200, authedResp.getStatusCode().value(),
                "GET /api/v1/a2a/cards must return 200 for an authenticated caller; got "
                        + authedResp.getStatusCode());
        assertNotNull(authedResp.getBody(),
                "200 response must carry a body — null here would mean the controller returned ResponseEntity.ok().build() "
                        + "instead of ok(cards), breaking the peer-discovery contract");

        ResponseEntity<String> unauthResp = rest.exchange(
                url("/api/v1/a2a/cards"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(401, unauthResp.getStatusCode().value(),
                "GET /api/v1/a2a/cards without a Bearer token must be 401 — anonymous capability "
                        + "discovery would let any internet caller enumerate local agents");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
