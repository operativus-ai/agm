package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.a2a.A2ACardResolver;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Fills the cross-tenant gap in
 *   {@code DELETE /api/v1/a2a/peers/&#123;alias&#125;} coverage. A2aMeshRuntimeTest case 7
 *   pins the cross-org LIST scoping; A2aMeshRuntimeTest case 2 pins the same-org
 *   happy-path deregister + idempotent 404. This class fills the missing DELETE
 *   cross-tenant negative.
 *
 *   <p>Contract pinned (§22.7): "a caller in org A cannot drop a peer registered
 *   under org B." Implementation: {@code A2ACardResolver.deregisterRemoteAgent}
 *   returns false when no row matches {@code (alias, orgId)}; controller maps to 404.
 *
 *   <p>Why this matters: without cross-tenant DELETE scoping, any user in any tenant
 *   could deregister peers in any other tenant — silently breaking outbound A2A
 *   integrations for the victim org with no audit trail beyond a "row removed"
 *   debug log. The 404 + persisted-row assertion prevents a future refactor that
 *   loosened the orgId filter from sliding through.
 *
 * State: Stateless. The peer-registry in-memory map survives between tests; the
 *   {@link #resetState()} wipe matches the pattern from sibling A2A test classes.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class A2aPeerDeregisterCrossTenantRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private A2ACardResolver cardResolver;

    @BeforeEach
    void resetState() {
        // Mirror the pattern from A2aMeshRuntimeTest: wipe DB + the unscoped partition
        // of the in-memory registry. Per-test orgs are fresh UUIDs so cross-org bleed
        // is safe.
        jdbc.update("DELETE FROM a2a_remote_agents");
        cardResolver.listRemoteRegistrations(null)
                .forEach(r -> cardResolver.deregisterRemoteAgent(r.alias(), null));
    }

    // ─── X1 — cross-tenant DELETE returns 404 + leaves org A's peer intact ────

    @Test
    void deregisterPeer_callerInOrgB_targetingOrgA_returns404_andOrgAPeerIsPreserved() {
        String orgA = "org-x1-A-" + UUID.randomUUID();
        String orgB = "org-x1-B-" + UUID.randomUUID();
        HttpHeaders authA = userHeadersForOrg("a2a-x1-orgA", orgA);
        HttpHeaders authB = userHeadersForOrg("a2a-x1-orgB", orgB);

        // Org A registers a peer.
        String aliasA = "orgA-peer-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-x1-A");
        body.put("baseUrl", "https://orgA-peer.example.com");
        body.put("alias", aliasA);
        body.put("apiKey", "orgA-key-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST,
                new HttpEntity<>(body, authA), JSON_MAP);
        assertEquals(HttpStatus.OK, register.getStatusCode(),
                "fixture: org A's peer must register cleanly before the cross-tenant probe");

        // Org B tries to deregister org A's alias.
        ResponseEntity<Void> crossDelete = rest.exchange(
                url("/api/v1/a2a/peers/" + aliasA), HttpMethod.DELETE,
                new HttpEntity<>(authB), Void.class);

        Integer persistedAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ? AND org_id = ?",
                Integer.class, aliasA, orgA);
        boolean inMemoryAfter = cardResolver.listRemoteRegistrations(orgA).stream()
                .anyMatch(r -> aliasA.equals(r.alias()));

        assertAll("§22.7 cross-tenant DELETE rejected end-to-end",
                () -> assertEquals(HttpStatus.NOT_FOUND, crossDelete.getStatusCode(),
                        "cross-tenant deregister must return 404 — same shape as 'alias does "
                                + "not exist', avoiding existence-leak of org A's peer to org B"),
                () -> assertEquals(1, persistedAfter.intValue(),
                        "org A's persisted a2a_remote_agents row MUST be preserved — a 0 here "
                                + "means the orgId scoping was lost and any org could nuke any "
                                + "peer registration"),
                () -> assertTrue(inMemoryAfter,
                        "org A's in-memory registration MUST be preserved — A2ACardResolver "
                                + "keys by (orgId, alias); the cross-tenant call must not match"));
    }

    // ─── X2 — sanity: org A deregistering its OWN peer still works ────────────

    @Test
    void deregisterPeer_owningOrg_returns204_andPeerIsRemoved() {
        String orgA = "org-x2-A-" + UUID.randomUUID();
        HttpHeaders authA = userHeadersForOrg("a2a-x2-orgA", orgA);

        String aliasA = "orgA-peer-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-x2-A");
        body.put("baseUrl", "https://orgA-peer.example.com");
        body.put("alias", aliasA);
        body.put("apiKey", "orgA-key-" + UUID.randomUUID());
        rest.exchange(url("/api/v1/a2a/peers"), HttpMethod.POST,
                new HttpEntity<>(body, authA), JSON_MAP);

        ResponseEntity<Void> selfDelete = rest.exchange(
                url("/api/v1/a2a/peers/" + aliasA), HttpMethod.DELETE,
                new HttpEntity<>(authA), Void.class);

        Integer persistedAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ? AND org_id = ?",
                Integer.class, aliasA, orgA);

        assertAll("sanity: owning-org deregister still works (proves X1's 404 is the cross-tenant gate, not a broken endpoint)",
                () -> assertEquals(HttpStatus.NO_CONTENT, selfDelete.getStatusCode()),
                () -> assertEquals(0, persistedAfter.intValue(),
                        "owning-org deregister MUST remove the persisted row"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Registers a user, force-binds them to the given org via JDBC, and returns
     * Bearer-auth headers + an X-Org-Id header (the A2AController reads orgId from
     * AgentContextHolder; the tenant filter pulls it from the JWT claim populated
     * at login by way of users.org_id). Mirrors the userHeaders pattern from
     * A2aMeshRuntimeTest case 7.
     */
    private HttpHeaders userHeadersForOrg(String username, String orgId) {
        String tagged = username + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = authenticateAs(tagged, tagged + "@test.local",
                "pass-x-1234", List.of("ROLE_USER"));
        // The X-Org-Id header is read by some A2A paths in addition to the JWT claim;
        // setting it explicitly here matches A2aMeshRuntimeTest case 7's approach and
        // makes the test's intent obvious.
        headers.add("X-Org-Id", orgId);
        // Force the underlying users.org_id to match so the JWT carries the same orgId
        // claim on subsequent re-issues / Tenant filter reads.
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, tagged);
        // Re-authenticate so the new JWT carries the updated orgId claim.
        return reAuthenticate(tagged, "pass-x-1234", orgId);
    }

    private HttpHeaders reAuthenticate(String username, String password, String orgId) {
        Map<String, String> login = Map.of("username", username, "password", password);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(login, HttpHeaders.EMPTY), JSON_MAP);
        String token = (String) resp.getBody().get("token");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add("X-Org-Id", orgId);
        return headers;
    }
}
