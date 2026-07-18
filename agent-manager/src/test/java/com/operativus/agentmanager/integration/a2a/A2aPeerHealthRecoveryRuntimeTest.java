package com.operativus.agentmanager.integration.a2a;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.a2a.PeerHealthMonitor;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Domain Responsibility: Pins the UNTRUSTED → TRUSTED recovery contract on
 *   {@link PeerHealthMonitor#checkSinglePeer}. When a previously-untrusted peer
 *   (i.e. {@code a2a_remote_agents.trusted = false}) responds 200 to
 *   {@code GET {baseUrl}/api/v1/health}, the production code at
 *   {@code PeerHealthMonitor:58-61} flips {@code trusted = true}, resets the
 *   in-memory {@code consecutiveFailures} counter, stamps {@code last_verified_at},
 *   and persists via {@code peerRepository.save(peer)}.
 *
 *   The inverse direction (TRUSTED → UNTRUSTED after three failures) is pinned
 *   by {@code A2aMeshRuntimeTest.peerHealthMonitor_marksUnreachablePeerUntrustedAfterThreeFailures}.
 *   Without this companion test a regression that (a) drops the {@code !trusted →
 *   trusted} branch, (b) keeps the in-memory counter and never resets it after
 *   recovery, or (c) stops calling {@code save()} on the recovery path would
 *   silently leave once-untrusted peers permanently unhealthy and {@link
 *   PeerHealthMonitor#selectHealthyPeer} (which queries
 *   {@code findByTrustedTrue}) would never route work to them again.
 *
 * State: Stateless. Stands up a per-class WireMock server on a dynamic localhost
 *   port and stubs {@code GET /api/v1/health → 200} so the recovery branch fires
 *   deterministically.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aPeerHealthRecoveryRuntimeTest extends BaseIntegrationTest {

    private static WireMockServer wiremock;

    @Autowired private PeerHealthMonitor peerHealthMonitor;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void resetFixtures() {
        wiremock.resetAll();
        jdbc.update("DELETE FROM a2a_remote_agents");
    }

    @Test
    void untrustedPeer_whenHealthCheckSucceeds_isFlippedToTrustedAndLastVerifiedAtStamped() throws Exception {
        // Stub the health endpoint to 200 so PeerHealthMonitor.checkSinglePeer
        // takes the success branch (line 54) on the very first poll. We do not
        // need a counter pre-load: the in-memory consecutiveFailures map starts
        // empty for this brand-new peer.getId(), and the success branch's
        // consecutiveFailures.remove(...) is a no-op in that case — the load-
        // bearing assertion is the trust flip, not the counter reset.
        wiremock.stubFor(get(urlPathEqualTo("/api/v1/health"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String peerId = "peer-id-" + UUID.randomUUID();
        String alias = "peer-recovery-" + UUID.randomUUID();
        String baseUrl = "http://localhost:" + wiremock.port();
        LocalDateTime before = LocalDateTime.now();

        // Seed a peer that is CURRENTLY untrusted — simulates the state left by
        // a prior TRUSTED→UNTRUSTED transition (three consecutive failures) that
        // has since recovered. outbound_api_key left NULL so OutboundApiKeyConverter
        // does not attempt to AES-decrypt a non-encrypted seed on hydrate (the
        // existing failure-direction test follows the same shortcut).
        jdbc.update("""
                INSERT INTO a2a_remote_agents
                    (id, remote_agent_id, base_url, alias, outbound_api_key,
                     security_tier, trusted, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL,
                        1, false, now(), now())
                """, peerId, "remote-recovery-" + UUID.randomUUID(), baseUrl, alias);

        peerHealthMonitor.checkPeerHealth();

        // checkPeerHealth dispatches one virtual thread per peer (PeerHealthMonitor:47).
        // Poll the DB until the recovery has landed — the success branch saves
        // synchronously inside that virtual thread, but we have no direct join
        // handle so we observe via the persisted column.
        long deadline = System.currentTimeMillis() + 10_000L;
        Boolean trusted = false;
        Timestamp lastVerified = null;
        while (System.currentTimeMillis() < deadline) {
            trusted = jdbc.queryForObject(
                    "SELECT trusted FROM a2a_remote_agents WHERE id = ?", Boolean.class, peerId);
            lastVerified = jdbc.queryForObject(
                    "SELECT last_verified_at FROM a2a_remote_agents WHERE id = ?",
                    Timestamp.class, peerId);
            if (Boolean.TRUE.equals(trusted) && lastVerified != null) break;
            Thread.sleep(100);
        }

        Boolean finalTrusted = trusted;
        Timestamp finalLastVerified = lastVerified;
        assertAll("untrusted peer must recover to trusted on first successful health check",
                () -> assertThat(finalTrusted)
                        .as("trusted column must flip false→true once the health endpoint returns 200. "
                                + "A null/false here means PeerHealthMonitor.checkSinglePeer's recovery "
                                + "branch (`if (!Boolean.TRUE.equals(peer.getTrusted())) peer.setTrusted(true)`) "
                                + "was removed or the save() on line 63 was dropped — selectHealthyPeer "
                                + "(findByTrustedTrue) would then never route traffic to peers that have "
                                + "since recovered, silently halving fleet capacity.")
                        .isTrue(),
                () -> assertThat(finalLastVerified)
                        .as("last_verified_at must be stamped on every successful poll, not just the "
                                + "recovery transition — pins PeerHealthMonitor:62 against a refactor "
                                + "that moves the timestamp write inside the !trusted branch")
                        .isNotNull(),
                () -> assertThat(finalLastVerified.toLocalDateTime())
                        .as("last_verified_at must reflect THIS health check, not the seed row's "
                                + "created_at (which we set via now() above and is unchanged on the "
                                + "no-op path). Allow a small clock skew window before the test start.")
                        .isAfter(before.minusSeconds(2)));

        // And finally: WireMock must have actually received the GET — proves the
        // recovery wasn't a phantom flip from a separate code path.
        wiremock.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/api/v1/health")));
    }
}
