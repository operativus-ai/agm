package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the IDOR fix on the agent-credential surface. Before the fix,
 *   {@code GET/PUT/DELETE /api/v1/agents/{agentId}/credentials/{credentialId}} resolved the
 *   credential by {@code credentialId} alone — any caller who knew (or guessed) a valid UUID
 *   could read or mutate another tenant's encrypted secrets, OAuth tokens, or API keys.
 *
 * <p>Two boundaries are tested in depth:</p>
 * <ol>
 *   <li><b>Org boundary</b> — org-B cannot access credentials belonging to an agent in org-A,
 *       even if it supplies the exact agentId and credentialId from org-A.</li>
 *   <li><b>Agent boundary</b> — within the same org, a caller cannot access credential-X by
 *       routing through a different agent's URL (findByIdAndAgentId scoping).</li>
 * </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentCredentialTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
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

    // §27.C1 — Cross-org GET-by-id: org-B cannot read a credential belonging to org-A's agent.
    // Before the fix, AgentCredentialController.getCredential(agentId, credentialId) called
    // agentIdentityService.getCredential(credentialId) ignoring agentId — a bare findById with
    // no org scoping. The fix adds requireAgentOwnedByCallerOrg(agentId) (returns 404 when the
    // agent is not visible to the caller's org) and getCredential(credentialId, agentId)
    // (findByIdAndAgentId so the credential itself is also scoped).
    @Test
    void crossOrgGetCredentialByIdReturns404() {
        HttpHeaders orgA = registerLoginWithOrg("cred-iso-a-get", "cred-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("cred-iso-b-get", "cred-iso-org-B");

        String agentId = createAgentViaApi(orgA, "Org-A credential holder");
        String credentialId = createCredentialViaApi(orgA, agentId, "stripe", "secret-a");

        // Org-B tries to read org-A's credential via the exact agentId + credentialId.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.GET, new HttpEntity<>(orgB), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "org-B must not be able to read org-A's credential (IDOR guard)");
    }

    // §27.C2 — Cross-org GET list: org-B calling GET /agents/{orgA-agentId}/credentials returns 404.
    @Test
    void crossOrgListCredentialsReturns404() {
        HttpHeaders orgA = registerLoginWithOrg("cred-iso-a-list", "cred-iso-org-A-list");
        HttpHeaders orgB = registerLoginWithOrg("cred-iso-b-list", "cred-iso-org-B-list");

        String agentId = createAgentViaApi(orgA, "Org-A credential owner");
        createCredentialViaApi(orgA, agentId, "github", "token-a");

        // Extract as String (status-only): the cross-org 404 returns an application/problem+json
        // OBJECT body (ResourceNotFoundException handler), which cannot deserialize into a List —
        // that extraction error, not the 404 itself, was what failed this test. We only assert status.
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials"),
                HttpMethod.GET, new HttpEntity<>(orgB), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /credentials list for another org's agent must return 404, not the credential list");
    }

    // §27.C3 — Cross-org PUT: org-B cannot update org-A's credential.
    @Test
    void crossOrgUpdateCredentialReturns404() {
        HttpHeaders orgA = registerLoginWithOrg("cred-iso-a-put", "cred-iso-org-A-put");
        HttpHeaders orgB = registerLoginWithOrg("cred-iso-b-put", "cred-iso-org-B-put");

        String agentId = createAgentViaApi(orgA, "Org-A agent for PUT test");
        String credentialId = createCredentialViaApi(orgA, agentId, "slack", "old-token");

        Map<String, Object> updateBody = apiKeyCredentialBody("slack", "stolen-new-token");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.PUT, new HttpEntity<>(updateBody, orgB), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "org-B must not be able to update org-A's credential");

        // The original secret must survive untouched.
        String persisted = jdbc.queryForObject(
                "SELECT encrypted_secret FROM agent_credentials WHERE id = ?", String.class, credentialId);
        assertEquals("old-token", persisted, "org-A's secret must be unchanged after the rejected PUT");
    }

    // §27.C4 — Cross-org DELETE: org-B cannot delete org-A's credential.
    @Test
    void crossOrgDeleteCredentialReturns404AndRowSurvives() {
        HttpHeaders orgA = registerLoginWithOrg("cred-iso-a-del", "cred-iso-org-A-del");
        HttpHeaders orgB = registerLoginWithOrg("cred-iso-b-del", "cred-iso-org-B-del");

        String agentId = createAgentViaApi(orgA, "Org-A agent for DELETE test");
        String credentialId = createCredentialViaApi(orgA, agentId, "aws", "access-key");

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials/" + credentialId),
                HttpMethod.DELETE, new HttpEntity<>(orgB), Void.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "org-B must not be able to delete org-A's credential");

        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM agent_credentials WHERE id = ?", Long.class, credentialId);
        assertEquals(1L, remaining, "credential row must survive the rejected cross-org DELETE");
    }

    // §27.C5 — Agent boundary (same org, wrong agent): supplying a credentialId that belongs to
    // agent-1 via agent-2's URL path must 404, not return the credential. This pins the
    // findByIdAndAgentId scoping in AgentIdentityService — it is independent of the org guard.
    @Test
    void crossAgentGetCredentialReturns404() {
        HttpHeaders auth = registerLoginWithOrg("cred-iso-cross-agent", "cred-iso-org-cross");

        String agent1Id = createAgentViaApi(auth, "Agent 1");
        String agent2Id = createAgentViaApi(auth, "Agent 2");
        String credentialId = createCredentialViaApi(auth, agent1Id, "openai", "sk-secret");

        // Correct route: agent1/credentials/{credentialId} — must succeed.
        ResponseEntity<Map<String, Object>> correct = rest.exchange(
                url("/api/v1/agents/" + agent1Id + "/credentials/" + credentialId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, correct.getStatusCode(),
                "owner agent can read its own credential");

        // Cross-agent route: agent2/credentials/{credentialId from agent1} — must 404.
        ResponseEntity<Map<String, Object>> wrong = rest.exchange(
                url("/api/v1/agents/" + agent2Id + "/credentials/" + credentialId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, wrong.getStatusCode(),
                "GET via a different agent's URL must return 404 (findByIdAndAgentId scoping)");
    }

    // ─── helpers ───

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Credential isolation test agent");
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
                "fixture: agent creation must succeed");
        return agentId;
    }

    private String createCredentialViaApi(HttpHeaders auth, String agentId, String provider, String secret) {
        Map<String, Object> body = apiKeyCredentialBody(provider, secret);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/agents/" + agentId + "/credentials"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture: credential creation must succeed");
        return (String) response.getBody().get("id");
    }

    private Map<String, Object> apiKeyCredentialBody(String provider, String secret) {
        Map<String, Object> body = new HashMap<>();
        body.put("credentialType", "API_KEY");
        body.put("providerName", provider);
        body.put("encryptedSecret", secret);
        body.put("enabled", true);
        return body;
    }
}
