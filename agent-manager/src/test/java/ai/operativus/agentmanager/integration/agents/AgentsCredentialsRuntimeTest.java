package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.compute.security.AgentIdentityService;
import ai.operativus.agentmanager.core.entity.AgentCredential;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Agent credential surface —
 *   {@code POST/PUT/DELETE /api/v1/agents/{agentId}/credentials} (handled by
 *   {@link ai.operativus.agentmanager.control.controller.AgentCredentialController}
 *   → {@link AgentIdentityService}). Pins two first-class invariants:
 *   (a) {@code AgentIdentityService#updateCredential} calls {@code evictCachedToken(id)}
 *   after persisting, so the in-memory token cache cannot serve a stale secret after a
 *   rotation; (b) the REST CRUD contract round-trips through the real repository and
 *   produces the row shapes the rest of the system depends on.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §5.10 (credential rotation:
 *   POST new key → old key unusable, new key resolves on next run) plus a
 *   lifecycle-and-delete case (§5.10 subsidiary) that guards the
 *   {@code AgentCredentialController} wiring end-to-end.
 *
 * Implementation notes / gaps these tests pin:
 *   - Cached tokens live in a {@link java.util.concurrent.ConcurrentHashMap} keyed by
 *     credential id inside {@link AgentIdentityService}. Any mutation path that can move
 *     the underlying secret — {@code updateCredential}, {@code deleteCredential} — MUST
 *     call {@code evictCachedToken} before returning, or tool calls minted after the
 *     mutation will resolve the previous secret up to the cache TTL. Both tests below
 *     call {@code mintToken} before and after the REST mutation; a missing eviction would
 *     surface as the second mintToken returning the original secret.
 *   - Credential listing via {@code GET /credentials} delegates to
 *     {@code findByAgentId(agentId)} (returns ALL rows, including {@code enabled=false}).
 *     A different method — {@code findByAgentIdAndEnabledTrue} — is used by the
 *     tool-resolution path ({@code resolveAgentCredentials}). Tests in this file scope
 *     themselves to the CRUD surface, not the resolve path.
 *   - Passwords land on {@code encrypted_secret} verbatim (no encryption at rest in the
 *     current service path — the column name is aspirational). If encryption is added
 *     later, the assertions below should flip to decrypt before comparing.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentsCredentialsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private AgentIdentityService agentIdentityService;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §5.10 — Rotation: PUT with a new encrypted_secret must (a) persist the new value,
    // (b) evict the tokenCache entry keyed by the credential id, and (c) cause the next
    // mintToken call to return the NEW secret rather than the cached OLD one. This pins
    // the `evictCachedToken(id)` call inside AgentIdentityService.updateCredential — without
    // it, tool invocations would continue to receive the old secret for up to the cache TTL
    // window after an operator rotated the key.
    @Test
    void rotateCredentialViaPutEvictsTokenCacheAndNextMintReturnsFreshSecret() {
        HttpHeaders auth = authenticatedHeaders("cred-rotator");
        String agentId = createAgentViaApi(auth, "Credential Owner");

        Map<String, Object> createBody = apiKeyCredentialBody("stripe", "old-secret-001");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials"),
                HttpMethod.POST, new HttpEntity<>(createBody, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String credentialId = (String) created.getBody().get("id");
        assertNotNull(credentialId, "POST /credentials must return the generated id");

        // Prime the in-memory token cache by minting against the current credential.
        AgentCredential before = agentIdentityService.getCredential(credentialId, agentId);
        String tokenBefore = agentIdentityService.mintToken(before);
        assertEquals("old-secret-001", tokenBefore,
                "API_KEY mintToken returns the stored secret directly (no cache miss on first call)");

        // Rotate. Controller copies {agentId} from path variable; we set the remaining fields.
        Map<String, Object> updateBody = apiKeyCredentialBody("stripe", "new-secret-002");
        updateBody.put("id", credentialId);
        ResponseEntity<Map<String, Object>> rotated = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.PUT, new HttpEntity<>(updateBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        assertEquals("new-secret-002", rotated.getBody().get("encryptedSecret"),
                "PUT response must echo the rotated secret");

        String persisted = jdbc.queryForObject(
                "SELECT encrypted_secret FROM agent_credentials WHERE id = ?", String.class, credentialId);
        assertEquals("new-secret-002", persisted,
                "agent_credentials.encrypted_secret must be the new value on disk");

        // The critical assertion: mintToken after the rotation must return the NEW secret.
        // If evictCachedToken(id) were missing from updateCredential, this would still return
        // "old-secret-001" from the tokenCache map until natural expiry.
        AgentCredential after = agentIdentityService.getCredential(credentialId, agentId);
        String tokenAfter = agentIdentityService.mintToken(after);
        assertEquals("new-secret-002", tokenAfter,
                "rotation must evict the token cache — mintToken after PUT must return the fresh secret");
    }

    // §5.10 subsidiary — Credential lifecycle: POST creates, GET lists, DELETE removes.
    // Pins that (a) getCredentials returns ALL rows for the agent (findByAgentId, not
    // findByAgentIdAndEnabledTrue), (b) deleteCredential removes the row from the DB,
    // (c) a subsequent GET-by-id returns 404 via ResourceNotFoundException, and (d) the
    // token cache is evicted on delete so a dangling cached token cannot outlive the row.
    @Test
    void createListAndDeleteCredentialRoundTripsThroughRepositoryAndEvictsCache() {
        HttpHeaders auth = authenticatedHeaders("cred-lifecycler");
        String agentId = createAgentViaApi(auth, "Credential Holder");

        Map<String, Object> createBody = apiKeyCredentialBody("github", "token-abc");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials"),
                HttpMethod.POST, new HttpEntity<>(createBody, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String credentialId = (String) created.getBody().get("id");

        // Prime the cache so the delete-path eviction is observable.
        AgentCredential primed = agentIdentityService.getCredential(credentialId, agentId);
        assertEquals("token-abc", agentIdentityService.mintToken(primed));

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        boolean visible = list.getBody().stream().anyMatch(c -> credentialId.equals(c.get("id")));
        assertTrue(visible, "GET /credentials must include the just-created credential (findByAgentId scope)");

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "DELETE /credentials/{id} must return 204 No Content on success");

        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM agent_credentials WHERE id = ?", Long.class, credentialId);
        assertEquals(0L, remaining, "delete must remove the row (hard delete — no soft-delete column)");

        ResponseEntity<Map<String, Object>> afterGet = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, afterGet.getStatusCode(),
                "GET after DELETE must 404 via ResourceNotFoundException, not 500/NPE");
    }

    // ─── helpers ───

    /**
     * Minimal API_KEY credential body. The controller overrides {@code agentId} from the
     * path variable via {@code credential.setAgentId(agentId)} before handing to the
     * service, so we omit it here.
     */
    private Map<String, Object> apiKeyCredentialBody(String provider, String secret) {
        Map<String, Object> body = new HashMap<>();
        body.put("credentialType", "API_KEY");
        body.put("providerName", provider);
        body.put("encryptedSecret", secret);
        body.put("enabled", true);
        return body;
    }

    /**
     * Creates a minimal agent via {@code POST /api/admin/agents} (same path T011 pins) so
     * the credential's {@code fk_agent_credentials_agent_id → agents(id)} FK is satisfied.
     */
    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Credential test owner");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before credentials can reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-cred-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
