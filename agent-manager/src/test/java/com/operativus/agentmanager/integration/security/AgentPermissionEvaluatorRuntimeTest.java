package com.operativus.agentmanager.integration.security;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pins for {@code AgentPermissionEvaluator} — the custom
 *     {@code PermissionEvaluator} bean that backs
 *     {@code @PreAuthorize("hasPermission(#id, 'AgentDefinition', '…')")} on three
 *     {@code AgentAdminService} methods: {@code deleteAgent}, {@code restoreAgent},
 *     {@code getAgentHistory}. Before this PR, the evaluator had ZERO test coverage
 *     despite gating three production endpoints in {@code AgentAdminController}.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>Test surface (8 pins, 3 contracts):
 * <ul>
 *   <li><b>Role gate</b>: ROLE_USER → 403 / ROLE_ADMIN → 2xx on each of the 3 gated endpoints.</li>
 *   <li><b>Authentication gate</b>: unauthenticated → 401, regardless of endpoint.</li>
 *   <li><b>Cross-tenant existence-leak protection</b>: ADMIN in org-A targeting org-B's
 *       agent → 404 (NOT 403). Documented intent: tenant scoping is enforced in the service
 *       body via {@code findByIdAndOrgId}, so cross-tenant surfaces as resource-not-found
 *       to prevent existence leaks.</li>
 * </ul>
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentPermissionEvaluatorRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        seedModel("gpt-4o-mini");
    }

    // ─── ROLE_USER on gated endpoints — must be 403 ─────────────────────────────

    @Test
    void deleteAgent_asRoleUser_returns403() {
        HttpHeaders adminAuth = adminHeaders("eval-delete-setup-" + tag());
        String agentId = createAgent(adminAuth, "delete-403 probe");
        HttpHeaders userAuth = userHeaders("eval-delete-user-" + tag());

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId), HttpMethod.DELETE,
                new HttpEntity<>(userAuth), Void.class);
        assertEquals(403, resp.getStatusCode().value(),
                "ROLE_USER must be rejected with 403 on DELETE — evaluator.hasPermission returns false "
                        + "because the principal has no ROLE_ADMIN authority");
    }

    @Test
    void restoreAgent_asRoleUser_returns403() {
        HttpHeaders adminAuth = adminHeaders("eval-restore-setup-" + tag());
        String agentId = createAgent(adminAuth, "restore-403 probe");
        HttpHeaders userAuth = userHeaders("eval-restore-user-" + tag());

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/restore"), HttpMethod.POST,
                new HttpEntity<>(userAuth), Void.class);
        assertEquals(403, resp.getStatusCode().value(),
                "ROLE_USER must be rejected with 403 on POST /restore");
    }

    @Test
    void getAgentHistory_asRoleUser_returns403() {
        HttpHeaders adminAuth = adminHeaders("eval-history-setup-" + tag());
        String agentId = createAgent(adminAuth, "history-403 probe");
        HttpHeaders userAuth = userHeaders("eval-history-user-" + tag());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/history"), HttpMethod.GET,
                new HttpEntity<>(userAuth), JSON_MAP);
        assertEquals(403, resp.getStatusCode().value(),
                "ROLE_USER must be rejected with 403 on GET /history");
    }

    // ─── ROLE_ADMIN on gated endpoints — must pass authz, dispatch service body ──

    @Test
    void deleteAgent_asRoleAdmin_passesAuthzGate() {
        HttpHeaders adminAuth = adminHeaders("eval-delete-admin-" + tag());
        String agentId = createAgent(adminAuth, "delete-admin probe");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId), HttpMethod.DELETE,
                new HttpEntity<>(adminAuth), Void.class);
        assertEquals(204, resp.getStatusCode().value(),
                "ROLE_ADMIN must pass the evaluator and reach the service body — expected 204 No Content");
    }

    @Test
    void restoreAgent_asRoleAdmin_passesAuthzGate() {
        HttpHeaders adminAuth = adminHeaders("eval-restore-admin-" + tag());
        String agentId = createAgent(adminAuth, "restore-admin probe");
        // Soft-delete first so restore has work to do.
        rest.exchange(url("/api/admin/agents/" + agentId), HttpMethod.DELETE,
                new HttpEntity<>(adminAuth), Void.class);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/restore"), HttpMethod.POST,
                new HttpEntity<>(adminAuth), Void.class);
        assertEquals(204, resp.getStatusCode().value(),
                "ROLE_ADMIN must pass the evaluator and reach the restore service body — expected 204");
    }

    @Test
    void getAgentHistory_asRoleAdmin_passesAuthzGate() {
        HttpHeaders adminAuth = adminHeaders("eval-history-admin-" + tag());
        String agentId = createAgent(adminAuth, "history-admin probe");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/history"), HttpMethod.GET,
                new HttpEntity<>(adminAuth), JSON_MAP);
        assertEquals(200, resp.getStatusCode().value(),
                "ROLE_ADMIN must pass the evaluator and reach the history service body — expected 200");
    }

    // ─── Unauthenticated on gated endpoints — must be 401 ─────────────────────────

    @Test
    void deleteAgent_unauthenticated_returns401() {
        String agentId = "any-agent-id-no-auth";
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId), HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), Void.class);
        assertEquals(401, resp.getStatusCode().value(),
                "unauthenticated DELETE must be rejected at the entry point with 401 (no JWT) — "
                        + "evaluator's `authentication == null || !isAuthenticated()` branch is the inner check");
    }

    // ─── Cross-tenant existence-leak protection — must be 404, NOT 403 ──────────

    /**
     * Existence-leak-protection pattern: when ADMIN in org-A targets an agent that exists
     * only in org-B, the response must be 404 (not found) rather than 403 (forbidden).
     * 403 would leak that the agent EXISTS in some org — a side-channel for tenant enumeration.
     *
     * <p>Per the {@code AgentPermissionEvaluator} class docstring: "tenant scoping is enforced
     * in the service body via {@code findByIdAndOrgId} so cross-tenant requests surface as
     * 404, not 403." This test pins that contract end-to-end.
     */
    @Test
    void deleteAgent_asAdminInDifferentOrg_returns404_notLeakingExistence() {
        // registerLoginWithOrg already creates users with ROLE_USER + ROLE_ADMIN, exactly
        // what we need to reach the service body's tenant-scoping check.
        HttpHeaders orgAAdmin = registerLoginWithOrg("eval-xt-orgA-" + tag(), "org-permeval-A-" + tag());
        String orgAAgentId = createAgent(orgAAdmin, "org-A-only probe");

        HttpHeaders orgBAdmin = registerLoginWithOrg("eval-xt-orgB-" + tag(), "org-permeval-B-" + tag());

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + orgAAgentId), HttpMethod.DELETE,
                new HttpEntity<>(orgBAdmin), Void.class);
        assertEquals(404, resp.getStatusCode().value(),
                "ADMIN in a different org must see 404 (NOT 403) — existence-leak-protection. "
                        + "The evaluator passes (ROLE_ADMIN), then the service body's findByIdAndOrgId returns "
                        + "empty, throwing ResourceNotFoundException → 404. Got " + resp.getStatusCode());
    }

    // ─── helpers ───

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "AgentPermissionEvaluator fixture");
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
        assertTrue(response.getStatusCode().value() == 201,
                "fixture precondition: agent create must return 201 — got " + response.getStatusCode());
        return agentId;
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
        return authenticateAs(username, username + "@test.local", "pass-permeval-1234", List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-permeval-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
