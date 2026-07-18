package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.A2ACardResolver;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
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
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the uniqueness contract on
 *   {@code POST /api/v1/a2a/peers}. The schema declares
 *   {@code UNIQUE(org_id, alias)} (Liquibase changeset 025) and {@code A2ACardResolver}
 *   implements an upsert on top of that — a second POST with the same {@code (orgId, alias)}
 *   updates the existing row rather than creating a duplicate or returning 409. This test
 *   pins the upsert behavior so a regression that either (a) starts returning 409,
 *   (b) creates duplicate rows, or (c) silently keeps the OLD baseUrl/apiKey, surfaces.
 *
 *   Also pins {@code remote_agent_id} uniqueness within an org. Changeset 064 adds
 *   {@code UNIQUE(org_id, remote_agent_id)} and the controller pre-checks via
 *   {@code A2aRemoteAgentRepository.findByRemoteAgentIdAndOrgId}: a second register
 *   under a DIFFERENT alias with the same {@code remoteAgentId} returns 409 Conflict
 *   (the SAME-alias case still upserts in place via the alias-keyed branch above).
 *   PeerCancellationDispatcher.findByRemoteAgentId can now assume at most one row per
 *   (org, remoteAgentId).
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aPeerUniquenessRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private A2ACardResolver cardResolver;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM a2a_remote_agents");
        cardResolver.listRemoteRegistrations(null)
                .forEach(r -> cardResolver.deregisterRemoteAgent(r.alias(), null));
    }

    @Test
    void registerPeer_sameOrgSameAlias_secondRegister_upsertsRow_inPlace() {
        HttpHeaders auth = userHeaders("a2a-uniqueness-alias");
        String alias = "peer-" + UUID.randomUUID();

        Map<String, Object> first = peerBody(alias, "remote-agent-original",
                "https://peer-original.example.com", "key-original");
        ResponseEntity<Map<String, Object>> r1 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(first, auth), JSON_MAP);
        assertEquals(200, r1.getStatusCode().value());

        Map<String, Object> second = peerBody(alias, "remote-agent-replacement",
                "https://peer-new.example.com", "key-replacement");
        ResponseEntity<Map<String, Object>> r2 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(second, auth), JSON_MAP);
        assertEquals(200, r2.getStatusCode().value(),
                "second register for (org, alias) must succeed — upsert contract, not 409");

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ?", Integer.class, alias);
        Map<String, Object> persisted = jdbc.queryForMap(
                "SELECT remote_agent_id, base_url FROM a2a_remote_agents WHERE alias = ?", alias);

        assertAll("second register must upsert in place — one row, latest values",
                () -> assertEquals(1, persistedRows,
                        "exactly one row — the second register must NOT create a duplicate (would violate UNIQUE)"),
                () -> assertEquals("remote-agent-replacement", persisted.get("remote_agent_id"),
                        "remote_agent_id reflects the SECOND register — first values are overwritten"),
                () -> assertEquals("https://peer-new.example.com", persisted.get("base_url"),
                        "base_url reflects the SECOND register — first URL is gone"));
    }

    @Test
    void registerPeer_sameRemoteAgentId_differentAliases_secondRegister_returns409() {
        // §22.5 follow-on. Closes the prior known gap: two peers under the same org
        // sharing a remote_agent_id used to both succeed (200/200) and PeerCancellationDispatcher
        // .findByRemoteAgentId resolved arbitrarily. Changeset 064 adds
        // UNIQUE(org_id, remote_agent_id) and the controller pre-checks via
        // findByRemoteAgentIdAndOrgId, returning 409 Conflict on the second register.
        HttpHeaders auth = userHeaders("a2a-uniqueness-remote-id");
        String sharedRemoteId = "remote-agent-shared-" + UUID.randomUUID();

        Map<String, Object> aliasA = peerBody("peer-A-" + UUID.randomUUID(), sharedRemoteId,
                "https://peer-a.example.com", "key-a");
        Map<String, Object> aliasB = peerBody("peer-B-" + UUID.randomUUID(), sharedRemoteId,
                "https://peer-b.example.com", "key-b");

        ResponseEntity<Map<String, Object>> r1 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(aliasA, auth), JSON_MAP);
        ResponseEntity<Map<String, Object>> r2 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(aliasB, auth), JSON_MAP);

        Integer rowsForRemoteId = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE remote_agent_id = ?",
                Integer.class, sharedRemoteId);

        assertAll("duplicate remote_agent_id under different alias must be 409, exactly one row persists",
                () -> assertEquals(200, r1.getStatusCode().value(),
                        "first register with the shared remote_agent_id must succeed"),
                () -> assertEquals(409, r2.getStatusCode().value(),
                        "second register under a DIFFERENT alias must be 409 Conflict — a 200 here "
                                + "would mean the duplicate-peer guard regressed or the controller's "
                                + "findByRemoteAgentIdAndOrgId pre-check was removed; a 500 would "
                                + "mean the pre-check is gone AND the DataIntegrityViolationException "
                                + "from UNIQUE(org_id, remote_agent_id) (changeset 064) is now leaking "
                                + "out of the controller unhandled"),
                () -> assertThat(rowsForRemoteId)
                        .as("exactly one row must persist after the conflict — the 409 must precede "
                                + "the cardResolver.registerRemoteAgent save")
                        .isEqualTo(1));
    }

    @Test
    void registerPeer_sameRemoteAgentId_sameAlias_upsertsInPlace_doesNotConflict() {
        // Forward guard: the duplicate-peer guard MUST only fire when the alias differs.
        // A second register with the same (orgId, alias, remoteAgentId) is the documented
        // upsert path (see registerPeer_sameOrgSameAlias_secondRegister_upsertsRow_inPlace
        // above for the canonical contract). If the duplicate-peer guard fires for the
        // same-alias case, every legitimate re-register (e.g., apiKey rotation by the
        // FE settings page) breaks.
        HttpHeaders auth = userHeaders("a2a-uniqueness-same-alias-same-remote");
        String alias = "peer-stable-" + UUID.randomUUID();
        String stableRemoteId = "remote-agent-stable-" + UUID.randomUUID();

        Map<String, Object> first = peerBody(alias, stableRemoteId,
                "https://peer-stable-v1.example.com", "key-v1");
        Map<String, Object> second = peerBody(alias, stableRemoteId,
                "https://peer-stable-v2.example.com", "key-v2");

        ResponseEntity<Map<String, Object>> r1 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(first, auth), JSON_MAP);
        ResponseEntity<Map<String, Object>> r2 = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(second, auth), JSON_MAP);

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE remote_agent_id = ?",
                Integer.class, stableRemoteId);
        String persistedBaseUrl = jdbc.queryForObject(
                "SELECT base_url FROM a2a_remote_agents WHERE remote_agent_id = ?",
                String.class, stableRemoteId);

        assertAll("same alias re-register must upsert, not 409",
                () -> assertEquals(200, r1.getStatusCode().value()),
                () -> assertEquals(200, r2.getStatusCode().value(),
                        "same (orgId, alias, remoteAgentId) re-register must still be 200 — the "
                                + "duplicate-peer guard only rejects when the alias DIFFERS"),
                () -> assertEquals(1, persistedRows,
                        "exactly one row — upsert by alias merged the second register"),
                () -> assertEquals("https://peer-stable-v2.example.com", persistedBaseUrl,
                        "second register's baseUrl is now persisted — proving upsert ran, not no-op"));
    }

    private Map<String, Object> peerBody(String alias, String remoteAgentId,
                                          String baseUrl, String apiKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("alias", alias);
        body.put("remoteAgentId", remoteAgentId);
        body.put("baseUrl", baseUrl);
        body.put("apiKey", apiKey);
        return body;
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-uniqueness", List.of("ROLE_USER"));
    }
}
