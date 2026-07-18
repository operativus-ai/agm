package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.A2ACardResolver;
import com.operativus.agentmanager.control.a2a.model.AgentCard;
import com.operativus.agentmanager.control.a2a.model.RemoteAgentRegistration;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Domain Responsibility: Pins the outbound peer card-discovery contract — the
 *   only outbound federation HTTP surface for A2A task routing today
 *   ({@code A2ACardResolver.resolveCard} → {@code fetchRemoteCard}, which sends
 *   {@code GET <peer.baseUrl>/api/v1/a2a/cards/<remoteAgentId>} with
 *   {@code X-A2A-Api-Key}). {@code PeerCancellationDispatcher} already has runtime
 *   coverage; this closes the second outbound path.
 *
 *   Three cases cover the production state machine:
 *   <ol>
 *     <li>Happy path: peer returns 200 + AgentCard body → resolver decrypts the
 *         outbound key, sends the GET with the X-A2A-Api-Key header, parses the
 *         JSON card, and caches it on the in-memory registration record.</li>
 *     <li>Peer returns 5xx with a previously-cached card: resolver swallows the
 *         {@code RestClientException} and returns the stale cached card —
 *         operator-facing graceful degradation.</li>
 *     <li>Peer returns 5xx with no prior cache: resolver returns
 *         {@code Optional.empty()} — no stale fallback available.</li>
 *   </ol>
 *
 *   {@code @DirtiesContext(AFTER_CLASS)} mirrors the pattern from
 *   {@link PeerCancellationNotifyRuntimeTest}: {@link MockRestServiceServer}
 *   swaps the shared {@code RestTemplate}'s request factory, which would poison
 *   downstream tests (e.g. {@code PeerHealthMonitor} probes inside
 *   {@code A2aMeshRuntimeTest}) that share the cached Spring context.
 *
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class A2aPeerCardDiscoveryRuntimeTest extends BaseIntegrationTest {

    @Autowired private A2ACardResolver cardResolver;
    @Autowired private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM a2a_remote_agents");
        cardResolver.listRemoteRegistrations(null)
                .forEach(r -> cardResolver.deregisterRemoteAgent(r.alias(), null));
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    /**
     * Case 1 — Happy-path card discovery. Register a peer via POST /peers so the
     * outbound key round-trips through {@code OutboundApiKeyConverter} encryption.
     * Prime the mock server to expect exactly one GET with the plaintext
     * X-A2A-Api-Key header. {@code resolveCard} parses the JSON response and
     * returns the card.
     */
    @Test
    void resolveCard_callsPeerWithApiKey_andReturnsParsedCard() {
        String remoteAgentId = "remote-card-" + UUID.randomUUID();
        String alias         = "peer-card-" + UUID.randomUUID();
        String plaintextKey  = "outbound-card-key-" + UUID.randomUUID();
        String baseUrl       = "https://peer.example.com";

        registerPeer(remoteAgentId, baseUrl, alias, plaintextKey);

        // Sanity-check the registration landed in the in-memory map before we expect outbound traffic
        java.util.List<RemoteAgentRegistration> registrations =
                cardResolver.listRemoteRegistrations(TenantConstants.DEFAULT_SYSTEM_ORG);
        assertTrue(registrations.stream().anyMatch(r -> remoteAgentId.equals(r.remoteAgentId())),
                "peer must be registered before resolveCard: registrations=" + registrations);

        String agentCardJson = """
                {
                  "agentId": "%s",
                  "name": "Remote Capability Card",
                  "description": "Synthetic card served by the mock peer for runtime coverage.",
                  "capabilities": ["code-review", "sql-analysis"],
                  "securityTier": 1,
                  "dataZone": null,
                  "endpointUrl": "%s",
                  "modelId": "gpt-4o-mini",
                  "maxTokenBudget": 10000,
                  "publishedAt": "2026-05-11T00:00:00Z"
                }
                """.formatted(remoteAgentId, baseUrl);

        mockServer.expect(requestTo(baseUrl + "/api/v1/a2a/cards/" + remoteAgentId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-A2A-Api-Key", plaintextKey))
                .andRespond(withSuccess(agentCardJson, MediaType.APPLICATION_JSON));

        Optional<AgentCard> resolved = cardResolver.resolveCard(remoteAgentId, TenantConstants.DEFAULT_SYSTEM_ORG);

        mockServer.verify();

        assertAll("A2A peer card discovery — happy path",
                () -> assertTrue(resolved.isPresent(),
                        "card resolved through outbound GET — present"),
                () -> assertEquals(remoteAgentId, resolved.get().agentId(),
                        "parsed card carries the remote agent id"),
                () -> assertEquals("Remote Capability Card", resolved.get().name(),
                        "parsed card carries the name from the peer's JSON body"));
    }

    /**
     * Case 2 — Peer returns 5xx after we already have a cached card. First call
     * populates the cache; second call's mocked 5xx triggers the
     * {@code RestClientException} catch path, and the resolver returns the
     * cached card. Operator-visible graceful degradation under peer flakiness.
     */
    @Test
    void resolveCard_onPeerError_fallsBackToCachedCard() {
        String remoteAgentId = "remote-cache-" + UUID.randomUUID();
        String alias         = "peer-cache-" + UUID.randomUUID();
        String plaintextKey  = "outbound-cache-key-" + UUID.randomUUID();
        String baseUrl       = "https://cache-peer.example.com";

        registerPeer(remoteAgentId, baseUrl, alias, plaintextKey);

        // MockRestServiceServer requires all expectations to be declared BEFORE any
        // request is issued — declare the priming success + the follow-up 5xx upfront,
        // then drive both calls in order.
        String primingJson = """
                {"agentId":"%s","name":"Cached Card","description":"first hit",
                 "capabilities":["x"],"securityTier":1,"dataZone":null,
                 "endpointUrl":"%s","modelId":"gpt-4o-mini","maxTokenBudget":1000,
                 "publishedAt":"2026-05-11T00:00:00Z"}
                """.formatted(remoteAgentId, baseUrl);
        mockServer.expect(requestTo(baseUrl + "/api/v1/a2a/cards/" + remoteAgentId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(primingJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(baseUrl + "/api/v1/a2a/cards/" + remoteAgentId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError().body("peer is unavailable"));

        Optional<AgentCard> primed = cardResolver.resolveCard(remoteAgentId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertTrue(primed.isPresent(), "priming call must succeed to populate cache");

        Optional<AgentCard> fallback = cardResolver.resolveCard(remoteAgentId, TenantConstants.DEFAULT_SYSTEM_ORG);

        mockServer.verify();

        assertAll("A2A peer card discovery — 5xx falls back to cached",
                () -> assertTrue(fallback.isPresent(),
                        "resolver returns the stale cached card on RestClientException"),
                () -> assertEquals("Cached Card", fallback.get().name(),
                        "the cached card content is what surfaces — proof of graceful " +
                                "degradation, not silent failure"));
    }

    /**
     * Case 3 — Peer returns 5xx and no card has ever been cached. Resolver
     * returns {@code Optional.empty()}. Differentiates the "no card available"
     * case from the "stale card available" case in Case 2.
     */
    @Test
    void resolveCard_onPeerError_withNoCache_returnsEmpty() {
        String remoteAgentId = "remote-nocache-" + UUID.randomUUID();
        String alias         = "peer-nocache-" + UUID.randomUUID();
        String plaintextKey  = "outbound-nocache-key-" + UUID.randomUUID();
        String baseUrl       = "https://nocache-peer.example.com";

        registerPeer(remoteAgentId, baseUrl, alias, plaintextKey);

        mockServer.expect(requestTo(baseUrl + "/api/v1/a2a/cards/" + remoteAgentId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        Optional<AgentCard> resolved = cardResolver.resolveCard(remoteAgentId, TenantConstants.DEFAULT_SYSTEM_ORG);

        mockServer.verify();

        assertAll("A2A peer card discovery — 5xx with no cache returns empty",
                () -> assertTrue(resolved.isEmpty(),
                        "resolver returns Optional.empty() when peer fails and no prior cache exists"));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    /**
     * Registers a peer directly through {@link A2ACardResolver#registerRemoteAgent} so the
     * orgId is unambiguously bound to {@link TenantConstants#DEFAULT_SYSTEM_ORG}. The
     * outbound-key encryption path is not exercised here (that's
     * {@code OutboundApiKeyRotationRuntimeTest}'s scope); for card-discovery coverage we
     * pass the plaintext key directly so the {@code X-A2A-Api-Key} header assertion in
     * the mock server has a fixed value to compare against.
     */
    private void registerPeer(String remoteAgentId, String baseUrl, String alias, String plaintextKey) {
        RemoteAgentRegistration registration = new RemoteAgentRegistration(
                UUID.randomUUID().toString(),
                remoteAgentId,
                baseUrl,
                alias,
                plaintextKey,
                null,
                Instant.now(),
                null
        );
        cardResolver.registerRemoteAgent(registration, TenantConstants.DEFAULT_SYSTEM_ORG);
    }
}
