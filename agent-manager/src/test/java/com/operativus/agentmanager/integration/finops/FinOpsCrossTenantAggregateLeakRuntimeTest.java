package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Domain Responsibility: Pins that the dashboard-facing FinOps aggregate endpoints
 *   ({@code /allocations}, {@code /roi-stats}, {@code /trends}) DO NOT leak Org B's
 *   data into Org A's response — even via summed totals or aggregate counts.
 *
 *   <p>Complements {@code FinOpsRuntimeTest}'s existing org-scoping cases by specifically
 *   exercising the dashboard endpoints with two orgs' data seeded simultaneously and
 *   asserting Org A's response contains no Org B identifiers.
 *
 *   <p>The scoping mechanism is {@code AgentContextHolder.getOrgId()} flowing through
 *   {@code FinOpsAnalyticsService.getCostAllocations(days, orgId)} etc., per the docs
 *   in {@code FinOpsRuntimeTest:417-419}.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li>Two orgs each have an agent + a completed run. Org A's authenticated
 *         {@code /allocations} response body does NOT contain Org B's agent ID.</li>
 *     <li>Same for {@code /trends} and {@code /roi-stats}.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class FinOpsCrossTenantAggregateLeakRuntimeTest extends BaseIntegrationTest {

    private String agentA;
    private String agentB;
    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();

        // Authenticate as an admin. authenticateAs creates the user with a generated
        // orgId; agents we seed with that orgId belong to the "A" tenant from this user's
        // perspective. We seed Org B's agent with an explicit different orgId.
        adminAuth = authenticateAs("finops-leak-admin",
                "finops-leak-admin@test.local", "pass-fla-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        String orgA = jdbc.queryForObject(
                "SELECT org_id FROM users WHERE username = ?",
                String.class, "finops-leak-admin");

        String orgB = "org-B-leak-target-" + UUID.randomUUID();

        agentA = "agent-A-" + UUID.randomUUID();
        agentB = "agent-B-LEAK-CHECK-" + UUID.randomUUID();
        seedAgent(agentA, orgA);
        seedAgent(agentB, orgB);

        seedRun(agentA, orgA, "COMPLETED");
        seedRun(agentB, orgB, "COMPLETED");
    }

    @Test
    void allocationsDoesNotLeakOrgBAgentIntoOrgAResponse() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/allocations?days=7"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody() == null ? "" : response.getBody();
        assertFalse(body.contains(agentB),
                "Org A's /allocations response leaked Org B's agent id ("
                        + agentB + "); body: " + body);
    }

    @Test
    void trendsDoesNotLeakOrgBAgentIntoOrgAResponse() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/trends?days=7"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody() == null ? "" : response.getBody();
        assertFalse(body.contains(agentB),
                "Org A's /trends response leaked Org B's agent id (" + agentB + ")");
    }

    @Test
    void roiStatsDoesNotLeakOrgBAgentIntoOrgAResponse() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/roi-stats"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody() == null ? "" : response.getBody();
        assertFalse(body.contains(agentB),
                "Org A's /roi-stats response leaked Org B's agent id (" + agentB + ")");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id, String orgId) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'noop', NULL, true, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, id, "desc-" + id, orgId);
    }

    private void seedRun(String agentId, String orgId, String status) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, status, input, org_id,
                                         created_at, updated_at)
                VALUES (?, ?, NULL, ?, 'fixture-input', ?, NOW(), NOW())
                """, "run-" + UUID.randomUUID(), agentId, status, orgId);
    }
}
