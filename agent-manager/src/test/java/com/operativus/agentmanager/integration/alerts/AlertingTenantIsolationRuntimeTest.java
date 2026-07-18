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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Black-box runtime coverage of tenant isolation across the Alerts
 *   subsystem (AlertRule + AlertEvent). Pre-wave-5, all alert rules and events were globally
 *   shared across tenants — any authenticated user could see/mutate every other org's data.
 *   This class pins the desired contract: list scoping, GET/PUT/DELETE 404 on cross-tenant,
 *   POST stamps caller's orgId (ignores body-injected), acknowledge 404 cross-tenant.
 *
 *   Per the wave-1..wave-5 pattern: cross-tenant requests surface as 404 (not 403) to avoid
 *   leaking existence.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AlertingTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void listReturnsOnlyCallerOrgRules() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-list", "alerts-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("alerts-iso-b-list", "alerts-iso-org-B");

        createRule(orgA, "rule-a-1", "A's CPU rule", "system.cpu.usage");
        createRule(orgA, "rule-a-2", "A's memory rule", "jvm.memory.used");
        createRule(orgB, "rule-b-1", "B's CPU rule", "system.cpu.usage");

        ResponseEntity<List<Map<String, Object>>> aList = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.GET, new HttpEntity<>(orgA), JSON_LIST);
        assertEquals(HttpStatus.OK, aList.getStatusCode());
        assertNotNull(aList.getBody());
        assertEquals(2, aList.getBody().size(),
                "Org A must see only A's 2 rules; got " + aList.getBody().size());
        aList.getBody().forEach(r -> assertNotEquals("rule-b-1", r.get("id"),
                "Org A must not see org B's rule"));
    }

    @Test
    void getById404ForCrossTenantRule() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-get", "alerts-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("alerts-iso-b-get", "alerts-iso-org-B");

        createRule(orgB, "rule-b-get", "B's rule", "system.cpu.usage");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/alerts/rules/rule-b-get"), HttpMethod.GET,
                new HttpEntity<>(orgA), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /api/alerts/rules/{B-id} as A must return 404; got " + resp.getStatusCode());
    }

    @Test
    void put404ForCrossTenantRule() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-put", "alerts-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("alerts-iso-b-put", "alerts-iso-org-B");

        String bId = createRule(orgB, "rule-b-put", "B's rule", "system.cpu.usage");

        Map<String, Object> updateBody = ruleBody(bId, "HIJACKED", "system.cpu.usage", "GT", 0.95, true);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/alerts/rules/" + bId), HttpMethod.PUT,
                new HttpEntity<>(updateBody, orgA), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PUT /api/alerts/rules/{B-id} as A must return 404; got " + resp.getStatusCode());

        Long unchangedNameRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE id = ? AND name <> 'HIJACKED'",
                Long.class, bId);
        assertEquals(1L, unchangedNameRows, "B's row must remain unmodified after the cross-tenant PUT attempt");
    }

    @Test
    void deleteCrossTenantRuleIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-del", "alerts-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("alerts-iso-b-del", "alerts-iso-org-B");

        String bId = createRule(orgB, "rule-b-del", "B's rule", "system.cpu.usage");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/alerts/rules/" + bId), HttpMethod.DELETE,
                new HttpEntity<>(orgA), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE /api/alerts/rules/{B-id} as A must return 404; got " + resp.getStatusCode());

        Long stillExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_rules WHERE id = ?", Long.class, bId);
        assertEquals(1L, stillExists, "B's rule must still exist after the cross-tenant DELETE attempt");
    }

    @Test
    void postStampsCallerOrgIdIgnoringBodyOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-post", "alerts-iso-org-A");

        Map<String, Object> body = ruleBody("rule-a-post", "A's rule", "system.cpu.usage", "GT", 0.90, true);
        body.put("orgId", "INJECTED-EVIL-ORG"); // body-injected orgId must be ignored

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.POST,
                new HttpEntity<>(body, orgA), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        String createdId = (String) resp.getBody().get("id"); // server-generated, not the body id

        String dbOrgId = jdbc.queryForObject(
                "SELECT org_id FROM alert_rules WHERE id = ?", String.class, createdId);
        assertEquals("alerts-iso-org-A", dbOrgId,
                "Created rule must carry the caller's orgId, not the body-injected value; got " + dbOrgId);
    }

    @Test
    void acknowledge404ForCrossTenantEvent() {
        HttpHeaders orgA = registerLoginWithOrg("alerts-iso-a-ack", "alerts-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("alerts-iso-b-ack", "alerts-iso-org-B");

        // Seed an alert rule + event for org B directly via JDBC (avoids dependency on the
        // scheduler firing). orgId is on both rule and event.
        String ruleId = "rule-b-ack-" + UUID.randomUUID();
        String eventId = "event-b-ack-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO alert_rules (id, name, metric_name, condition, threshold, window_seconds,
                                         severity, enabled, org_id)
                VALUES (?, ?, 'system.cpu.usage', 'GT', 0.9, 60, 'WARNING', true, ?)
                """, ruleId, "B's rule for ack", "alerts-iso-org-B");
        jdbc.update("""
                INSERT INTO alert_events (id, rule_id, metric_value, message, severity, acknowledged,
                                          fired_at, org_id)
                VALUES (?, ?, 0.99, 'fixture event', 'WARNING', false, now(), ?)
                """, eventId, ruleId, "alerts-iso-org-B");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/alerts/events/" + eventId + "/acknowledge"), HttpMethod.POST,
                new HttpEntity<>(orgA), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "POST acknowledge for {B-event-id} as A must return 404; got " + resp.getStatusCode());

        Boolean stillUnack = jdbc.queryForObject(
                "SELECT acknowledged FROM alert_events WHERE id = ?", Boolean.class, eventId);
        assertEquals(Boolean.FALSE, stillUnack, "B's event must remain unacknowledged");
    }

    // ─── helpers ───

    private String createRule(HttpHeaders auth, String id, String name, String metricName) {
        Map<String, Object> body = ruleBody(id, name, metricName, "GT", 0.90, true);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createRule fixture must succeed; got " + resp.getStatusCode());
        // AlertingController generates the rule id server-side — the AlertRuleRequest DTO drops the
        // client-supplied id to block mass-assignment (#1018). Return the REAL id so callers address
        // the persisted row, not the (ignored) requested id.
        return (String) resp.getBody().get("id");
    }

    private Map<String, Object> ruleBody(String id, String name, String metricName,
                                          String condition, double threshold, boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("metricName", metricName);
        body.put("condition", condition);
        body.put("threshold", threshold);
        body.put("windowSeconds", 60);
        body.put("severity", "WARNING");
        body.put("enabled", enabled);
        return body;
    }
}
