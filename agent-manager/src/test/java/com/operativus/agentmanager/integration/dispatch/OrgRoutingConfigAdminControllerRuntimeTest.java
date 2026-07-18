package com.operativus.agentmanager.integration.dispatch;

import com.operativus.agentmanager.control.dto.OrgRoutingConfigRequest;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigResponse;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.universal-dispatch.enabled=true")
public class OrgRoutingConfigAdminControllerRuntimeTest extends BaseIntegrationTest {

    // --- CRUD happy paths ---

    @Test
    void upsertConfig_admin_returns200WithCreatedRow() {
        HttpHeaders auth = adminHeaders("rcfg-create");
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(null, null, true, true, null, null);

        ResponseEntity<OrgRoutingConfigResponse> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(req, auth), OrgRoutingConfigResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("DEFAULT_SYSTEM_ORG", resp.getBody().orgId());
        assertTrue(resp.getBody().llmClassifierEnabled());
        assertTrue(resp.getBody().ruleClassifierEnabled());
    }

    @Test
    void getConfig_afterUpsert_returnsRow() {
        HttpHeaders auth = adminHeaders("rcfg-get");
        upsert(auth, new OrgRoutingConfigRequest(null, null, false, true, null, null));

        ResponseEntity<OrgRoutingConfigResponse> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.GET,
                new HttpEntity<>(auth), OrgRoutingConfigResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().ruleClassifierEnabled());
        assertFalse(resp.getBody().llmClassifierEnabled());
    }

    @Test
    void getConfig_noUpsertYet_returns404() {
        HttpHeaders auth = adminHeaders("rcfg-no-row-" + UUID.randomUUID().toString().substring(0, 6));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.GET,
                new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET before any upsert must surface as 404 (no config row exists for the org)");
    }

    @Test
    void deleteConfig_admin_returns204AndSubsequentGetIs404() {
        HttpHeaders auth = adminHeaders("rcfg-del");
        upsert(auth, new OrgRoutingConfigRequest(null, null, true, false, null, null));

        ResponseEntity<Void> del = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());

        ResponseEntity<String> get = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.GET,
                new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, get.getStatusCode());
    }

    @Test
    void upsertConfig_repeatedCalls_idempotentOnOrg() {
        HttpHeaders auth = adminHeaders("rcfg-idem");
        OrgRoutingConfigResponse first = upsert(auth, new OrgRoutingConfigRequest(null, null, true, false, null, null));
        OrgRoutingConfigResponse second = upsert(auth, new OrgRoutingConfigRequest(null, null, false, true, null, null));

        // Repository has uq on org_id → second upsert mutates the same row, doesn't INSERT
        assertEquals(first.id(), second.id(),
                "two upserts for the same org must update the same row, not duplicate");
        assertFalse(second.llmClassifierEnabled());
        assertTrue(second.ruleClassifierEnabled());
    }

    // --- Cross-tenant 404 ---

    @Test
    void upsertConfig_crossTenantDefaultRouter_returns404() {
        // Model FK must exist before the agent insert can succeed.
        seedFakeModel();
        // Org A persists a config; Org B tries to point its config at Org A's "agent"
        HttpHeaders orgA = registerLoginWithOrg("rcfg-org-a", "ROUTING-ORG-A");
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, security_tier, compliance_tier)
                VALUES ('orga-agent', ?, 'Org A agent', 'd', 'i', 'gpt-4o-mini', true, false, false, 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, "ROUTING-ORG-A");

        HttpHeaders orgB = registerLoginWithOrg("rcfg-org-b", "ROUTING-ORG-B");
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest("orga-agent", null, null, null, null, null);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(req, orgB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "Cross-tenant default_router_agent_id must return 404 (not 403) per §79");
    }

    // --- DR-FR-1 default_router must be a ROUTER-strategy team ---

    @Test
    void upsertConfig_defaultRouterIsNotRouterTeam_returns400() {
        seedFakeModel();
        String orgId = "ROUTING-ORG-NONROUTER";
        HttpHeaders auth = registerLoginWithOrg("rcfg-nonrouter", orgId);
        // Seed a SEQUENTIAL team agent in the caller's org — passes ownership, fails
        // the ROUTER-strategy check at DR-FR-1 validation.
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, team_mode, security_tier, compliance_tier)
                VALUES ('seq-team', ?, 'Sequential team', 'd', 'i', 'gpt-4o-mini', true, false, true,
                        'SEQUENTIAL', 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, orgId);

        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest("seq-team", null, null, null, null, null);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(req, auth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "default_router_agent_id pointing at a non-ROUTER team must return 400");
    }

    // --- Non-admin 403 ---

    @Test
    void getConfig_nonAdmin_returns403() {
        HttpHeaders userOnly = authenticateAs("rcfg-user",
                "rcfg-user@test.local", "pass-rcfg-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.GET,
                new HttpEntity<>(userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void upsertConfig_nonAdmin_returns403() {
        HttpHeaders userOnly = authenticateAs("rcfg-upd-user",
                "rcfg-upd-user@test.local", "pass-rcfg-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(new OrgRoutingConfigRequest(null, null, true, true, null, null), userOnly),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // --- Helpers ---

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-rcfg-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private OrgRoutingConfigResponse upsert(HttpHeaders auth, OrgRoutingConfigRequest req) {
        ResponseEntity<OrgRoutingConfigResponse> resp = rest.exchange(
                url("/api/v1/routing-config"), HttpMethod.PUT,
                new HttpEntity<>(req, auth), OrgRoutingConfigResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "upsert helper must succeed");
        return resp.getBody();
    }

    private void seedFakeModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
