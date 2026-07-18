package com.operativus.agentmanager.integration.security;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code RoutingDecisionAdminController} tenant-scoping fix.
 *
 * <p>Pre-fix, the controller used the unscoped JpaRepository {@code findById(id)} on the
 * get-by-id path and accepted an arbitrary {@code ?orgId=} request param on the list path.
 * Because ADMIN is a per-org tenant role (RoleHierarchyConfig: SUPER_ADMIN &gt; ADMIN), an
 * ADMIN in org A could read org B's routing decisions by id (IDOR) or by passing org B's id
 * to the list filter (cross-tenant leak).
 *
 * <p>Post-fix, both paths resolve orgId from
 * {@link com.operativus.agentmanager.core.callback.AgentContextHolder} and a cross-org id
 * returns 404 — matching the existence-leak contract enforced by the sibling
 * {@code *TenantIsolationRuntimeTest} suite.
 */
@Tag("integration")
public class RoutingDecisionAdminTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void routingDecisionsAreTenantScoped_adminInOrgACannotReadOrgBDecisions() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-rd-A-" + tag;
        String orgB = "org-rd-B-" + tag;

        // ADMIN in org A only. registerLoginWithOrg grants ROLE_USER+ROLE_ADMIN bound to the org.
        HttpHeaders adminA = registerLoginWithOrg("rd-iso-adminA-" + tag, orgA);

        String decisionA = "rd-" + orgA;
        String decisionB = "rd-" + orgB;
        seedDecision(decisionA, orgA);
        seedDecision(decisionB, orgB);

        // 1. Own org's decision by id → 200.
        ResponseEntity<Map<String, Object>> getOwn = rest.exchange(
                url("/api/v1/admin/routing-decisions/" + decisionA), HttpMethod.GET,
                new HttpEntity<>(adminA), JSON_MAP);
        assertEquals(HttpStatus.OK, getOwn.getStatusCode(),
                "ADMIN must read a routing decision in their own org");
        assertEquals(decisionA, getOwn.getBody().get("id"));

        // 2. Cross-org decision by id → 404 (existence-leak protection, not 403/200).
        ResponseEntity<Map<String, Object>> getCross = rest.exchange(
                url("/api/v1/admin/routing-decisions/" + decisionB), HttpMethod.GET,
                new HttpEntity<>(adminA), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, getCross.getStatusCode(),
                "GET /routing-decisions/{org-B-id} as org-A ADMIN must return 404, not leak org B's row");

        // 3. List is scoped to caller's org — org B's decision must never appear, even though
        //    the pre-fix endpoint accepted an arbitrary ?orgId= param.
        ResponseEntity<Map<String, Object>> list = rest.exchange(
                url("/api/v1/admin/routing-decisions?size=200&orgId=" + orgB), HttpMethod.GET,
                new HttpEntity<>(adminA), JSON_MAP);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) list.getBody().get("content");
        List<Object> ids = content.stream().map(row -> row.get("id")).map(Object.class::cast).toList();
        assertTrue(ids.contains(decisionA), "list must include the caller-org decision");
        assertFalse(ids.contains(decisionB),
                "list must NOT include another org's decision even when ?orgId= names it");
    }

    private void seedDecision(String id, String orgId) {
        jdbc.update("""
                INSERT INTO routing_decisions (id, org_id, message_hash, resolution_status, strategy_used, created_at)
                VALUES (?, ?, ?, 'RESOLVED', 'DEFAULT_ROUTER', now())
                """, id, orgId, "hash-" + id);
    }
}
