package com.operativus.agentmanager.integration.alerts;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that {@code AlertIntegrationController} write methods reject non-admin callers.
 * Mirror of {@link AlertingControllerRbacRuntimeTest} for the sibling controller.
 *
 * <p>Background: prior to the @PreAuthorize gates added here, any authenticated tenant
 * member could POST / PUT / DELETE alert integrations. The blast radius was real —
 * an attacker user could plant an attacker-owned webhook URL on their own tenant and
 * then exfiltrate every subsequent alert (the SsrfGuard on {@code endpointUrl} only
 * blocks RFC-1918 / cloud-metadata targets — a public attacker URL passes). Sibling
 * {@link AlertingController} write paths were already ROLE_ADMIN — the inconsistency
 * is closed here. The test-fire endpoint already had its own @PreAuthorize.
 *
 * <p>Reads (GET /api/alerts/integrations) remain open to any authenticated tenant
 * member; the service filters by caller's orgId so cross-tenant reads return empty.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
class AlertIntegrationControllerRbacRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void alertIntegrationMutationsRequireAdmin_403ForRoleUser() {
        HttpHeaders userOnly = authenticateAs(
                "alert-integration-rbac-user",
                "alert-integration-rbac-user@test.local",
                "pw-rbac-1234",
                List.of("ROLE_USER"));

        Map<String, Object> integration = new HashMap<>();
        integration.put("name", "rbac probe");
        integration.put("type", "WEBHOOK");
        integration.put("endpointUrl", "https://hooks.example.com/probe");
        integration.put("enabled", true);

        ResponseEntity<Map<String, Object>> post = rest.exchange(
                url("/api/alerts/integrations"), HttpMethod.POST,
                new HttpEntity<>(integration, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, post.getStatusCode(),
                "ROLE_USER must be rejected by AlertIntegrationController.create @PreAuthorize");

        ResponseEntity<Map<String, Object>> put = rest.exchange(
                url("/api/alerts/integrations/non-existent-id"), HttpMethod.PUT,
                new HttpEntity<>(integration, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, put.getStatusCode(),
                "ROLE_USER must be rejected by AlertIntegrationController.update @PreAuthorize");

        ResponseEntity<Void> delete = rest.exchange(
                url("/api/alerts/integrations/non-existent-id"), HttpMethod.DELETE,
                new HttpEntity<>(userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, delete.getStatusCode(),
                "ROLE_USER must be rejected by AlertIntegrationController.delete @PreAuthorize");

        // /test was already gated before this PR — pinned here for completeness so a
        // future careless refactor that removes its @PreAuthorize is caught alongside
        // the create/update/delete gates added in this commit.
        ResponseEntity<Void> testFire = rest.exchange(
                url("/api/alerts/integrations/non-existent-id/test"), HttpMethod.POST,
                new HttpEntity<>(userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, testFire.getStatusCode(),
                "ROLE_USER must be rejected by AlertIntegrationController.testFire @PreAuthorize");
    }

    @Test
    void alertIntegrationListIsOpenToAuthenticatedUsers_notGatedByAdmin() {
        HttpHeaders userOnly = authenticateAs(
                "alert-integration-read-user",
                "alert-integration-read-user@test.local",
                "pw-rbac-1234",
                List.of("ROLE_USER"));

        ResponseEntity<List> list = rest.exchange(
                url("/api/alerts/integrations"), HttpMethod.GET,
                new HttpEntity<>(userOnly), List.class);
        assertEquals(HttpStatus.OK, list.getStatusCode(),
                "ROLE_USER must be able to list their org's integrations (only writes "
                        + "and test-fire are admin-gated; reads are tenant-scoped at the service).");
    }
}
