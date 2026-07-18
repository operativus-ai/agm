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
 * Pins that {@code AlertingController} write methods reject non-admin callers.
 * Reads (GET /api/alerts/rules, GET /api/alerts/rules/{id}, GET /api/alerts/events)
 * remain open to any authenticated tenant member; only mutations require ROLE_ADMIN
 * (matching the SchedulesController / ApprovalsController pattern from §28).
 * Org isolation is enforced at the {@code AlertingService} layer via
 * {@code AgentContextHolder.getOrgId()} on every method, so it's safe to leave reads
 * open.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
class AlertingControllerRbacRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void alertRuleMutationsAndAckRequireAdmin_403ForRoleUser() {
        HttpHeaders userOnly = authenticateAs(
                "alerting-rbac-user",
                "alerting-rbac-user@test.local",
                "pw-rbac-1234",
                List.of("ROLE_USER"));

        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "rbac probe");
        rule.put("metricName", "finops.agent.burn_rate.usd_per_hour");
        rule.put("condition", "GT");
        rule.put("threshold", 5.0);
        rule.put("severity", "WARNING");
        rule.put("enabled", true);

        ResponseEntity<Map<String, Object>> post = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.POST,
                new HttpEntity<>(rule, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, post.getStatusCode(),
                "ROLE_USER must be rejected by AlertingController.createRule @PreAuthorize");

        ResponseEntity<Map<String, Object>> put = rest.exchange(
                url("/api/alerts/rules/non-existent-id"), HttpMethod.PUT,
                new HttpEntity<>(rule, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, put.getStatusCode(),
                "ROLE_USER must be rejected by AlertingController.updateRule @PreAuthorize");

        ResponseEntity<Void> delete = rest.exchange(
                url("/api/alerts/rules/non-existent-id"), HttpMethod.DELETE,
                new HttpEntity<>(userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, delete.getStatusCode(),
                "ROLE_USER must be rejected by AlertingController.deleteRule @PreAuthorize");

        ResponseEntity<Void> ack = rest.exchange(
                url("/api/alerts/events/non-existent-id/acknowledge"), HttpMethod.POST,
                new HttpEntity<>(userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, ack.getStatusCode(),
                "ROLE_USER must be rejected by AlertingController.acknowledgeAlert @PreAuthorize");
    }

    @Test
    void alertRuleReadsAreOpenToAuthenticatedUsers_notGatedByAdmin() {
        HttpHeaders userOnly = authenticateAs(
                "alerting-read-user",
                "alerting-read-user@test.local",
                "pw-rbac-1234",
                List.of("ROLE_USER"));

        ResponseEntity<List> list = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.GET,
                new HttpEntity<>(userOnly), List.class);
        assertEquals(HttpStatus.OK, list.getStatusCode(),
                "ROLE_USER must be able to read their org's rules (only writes are admin-gated)");

        ResponseEntity<Map> events = rest.exchange(
                url("/api/alerts/events?page=0&size=10"), HttpMethod.GET,
                new HttpEntity<>(userOnly), Map.class);
        assertEquals(HttpStatus.OK, events.getStatusCode(),
                "ROLE_USER must be able to read their org's fired alerts page");
    }
}
