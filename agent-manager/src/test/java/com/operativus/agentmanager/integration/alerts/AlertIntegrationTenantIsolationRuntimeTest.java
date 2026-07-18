package com.operativus.agentmanager.integration.alerts;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the AlertIntegration HTTP surface.
 *   Pins that ROLE_USER of org B cannot list, update, or delete alert integrations
 *   belonging to org A, and that create stamps org_id from the caller's security context.
 *   <p>
 *   Behavioral contracts (AlertIntegrationService + AlertIntegrationController):
 *   <ul>
 *     <li>{@code GET /api/alerts/integrations}       — only own-org integrations visible.</li>
 *     <li>{@code POST /api/alerts/integrations}      — org_id stamped from caller's security context; body value ignored.</li>
 *     <li>{@code PUT /api/alerts/integrations/{id}}  — 404 cross-tenant (existence-leak protection via getIntegration).</li>
 *     <li>{@code DELETE /api/alerts/integrations/{id}} — 204 but row NOT deleted (silent no-op guard).</li>
 *   </ul>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AlertIntegrationTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void listIntegrationsReturnsOnlyCallerOrgIntegrations() {
        HttpHeaders orgA = registerLoginWithOrg("ai-iso-a-list", "ai-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("ai-iso-b-list", "ai-iso-org-B");

        insertIntegrationRow("int-a-1-" + UUID.randomUUID().toString().substring(0, 8), "ai-iso-org-A");
        insertIntegrationRow("int-a-2-" + UUID.randomUUID().toString().substring(0, 8), "ai-iso-org-A");
        insertIntegrationRow("int-b-1-" + UUID.randomUUID().toString().substring(0, 8), "ai-iso-org-B");

        ResponseEntity<List<Map<String, Object>>> aResp = rest.exchange(
                url("/api/alerts/integrations"),
                HttpMethod.GET, new HttpEntity<>(orgA), JSON_LIST);
        assertEquals(HttpStatus.OK, aResp.getStatusCode());
        assertEquals(2, aResp.getBody().size(),
                "org A must see exactly its 2 integrations — a value of 3 means cross-tenant integrations leaked");

        ResponseEntity<List<Map<String, Object>>> bResp = rest.exchange(
                url("/api/alerts/integrations"),
                HttpMethod.GET, new HttpEntity<>(orgB), JSON_LIST);
        assertEquals(HttpStatus.OK, bResp.getStatusCode());
        assertEquals(1, bResp.getBody().size(),
                "org B must see exactly its 1 integration — a value of 3 means cross-tenant integrations leaked");
    }

    @Test
    void createIntegrationStampsCallerOrg() {
        HttpHeaders orgA = registerLoginWithOrg("ai-iso-a-create", "ai-iso-org-A-create");

        Map<String, Object> body = Map.of(
                "name", "org-A webhook",
                "type", "WEBHOOK",
                "endpointUrl", "https://example.com/hook",
                "enabled", true
        );

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/alerts/integrations"),
                HttpMethod.POST, new HttpEntity<>(body, orgA), JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());

        String createdId = (String) created.getBody().get("id");
        assertNotNull(createdId);

        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM alert_integrations WHERE id = ?", String.class, createdId);
        assertEquals("ai-iso-org-A-create", storedOrgId,
                "org_id on the created row must match the caller's org — server-derived, not body-supplied");
    }

    @Test
    void updateIntegrationCrossTenantReturns404() {
        HttpHeaders orgA = registerLoginWithOrg("ai-iso-a-upd", "ai-iso-org-A-upd");
        HttpHeaders orgB = registerLoginWithOrg("ai-iso-b-upd", "ai-iso-org-B-upd");

        String integrationId = "int-a-upd-" + UUID.randomUUID().toString().substring(0, 8);
        insertIntegrationRow(integrationId, "ai-iso-org-A-upd");

        Map<String, Object> updateBody = Map.of(
                "name", "org-B attack",
                "type", "WEBHOOK",
                "endpointUrl", "https://evil.example.com/hook",
                "enabled", true
        );

        // org B cannot update org A's integration — getIntegration scopes by orgId, returning 404
        ResponseEntity<Map<String, Object>> crossUpd = rest.exchange(
                url("/api/alerts/integrations/" + integrationId),
                HttpMethod.PUT, new HttpEntity<>(updateBody, orgB), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, crossUpd.getStatusCode(),
                "cross-tenant PUT /api/alerts/integrations/{id} must return 404 — org B cannot overwrite org A's integration");

        // Verify org A's row is unchanged
        String storedUrl = jdbc.queryForObject(
                "SELECT endpoint_url FROM alert_integrations WHERE id = ?", String.class, integrationId);
        assertEquals("https://original.example.com/hook", storedUrl,
                "org A's endpoint_url must not be mutated by org B's update attempt");

        // org A can update its own integration
        Map<String, Object> ownUpdateBody = Map.of(
                "name", "org-A updated",
                "type", "WEBHOOK",
                "endpointUrl", "https://example.com/hook-updated",
                "enabled", true
        );
        ResponseEntity<Map<String, Object>> ownUpd = rest.exchange(
                url("/api/alerts/integrations/" + integrationId),
                HttpMethod.PUT, new HttpEntity<>(ownUpdateBody, orgA), JSON_MAP);
        assertEquals(HttpStatus.OK, ownUpd.getStatusCode(),
                "org A must be able to update its own integration");
    }

    @Test
    void deleteIntegrationCrossTenantIsNoOpRowSurvives() {
        HttpHeaders orgA = registerLoginWithOrg("ai-iso-a-del", "ai-iso-org-A-del");
        HttpHeaders orgB = registerLoginWithOrg("ai-iso-b-del", "ai-iso-org-B-del");

        String integrationId = "int-a-del-" + UUID.randomUUID().toString().substring(0, 8);
        insertIntegrationRow(integrationId, "ai-iso-org-A-del");

        // org B attempts to delete org A's integration
        ResponseEntity<Void> crossDel = rest.exchange(
                url("/api/alerts/integrations/" + integrationId),
                HttpMethod.DELETE, new HttpEntity<>(orgB), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, crossDel.getStatusCode(),
                "DELETE returns 204 unconditionally — the controller does not signal whether the row existed to prevent existence-leak");

        // the row must still exist
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM alert_integrations WHERE id = ?", Long.class, integrationId);
        assertEquals(1L, count,
                "org A's integration must survive org B's delete attempt — service-level existsByIdAndOrgId guard must block the deletion");

        // org A can still delete its own integration
        ResponseEntity<Void> ownDel = rest.exchange(
                url("/api/alerts/integrations/" + integrationId),
                HttpMethod.DELETE, new HttpEntity<>(orgA), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ownDel.getStatusCode());
        Long afterCount = jdbc.queryForObject(
                "SELECT count(*) FROM alert_integrations WHERE id = ?", Long.class, integrationId);
        assertEquals(0L, afterCount,
                "org A must be able to delete its own integration");
    }

    // ─── helpers ───

    private void insertIntegrationRow(String id, String orgId) {
        jdbc.update("""
                INSERT INTO alert_integrations (id, name, type, endpoint_url, enabled, org_id, retry_count, created_at)
                VALUES (?, ?, 'WEBHOOK', 'https://original.example.com/hook', true, ?, 0, now())
                """, id, id, orgId);
    }
}
