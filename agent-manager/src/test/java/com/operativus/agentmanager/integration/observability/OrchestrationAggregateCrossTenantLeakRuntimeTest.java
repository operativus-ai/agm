package com.operativus.agentmanager.integration.observability;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins tenant scoping on the {@code OrchestrationAggregateController}
 *   surface ({@code /api/v1/observability/aggregates/orchestration} +
 *   {@code /orchestration-decisions}). The controller resolves orgId from
 *   {@link com.operativus.agentmanager.core.callback.AgentContextHolder#getOrgId()} and
 *   passes it to the repository. A regression that drops the orgId binding or that
 *   uses an unscoped query method would leak orchestration-decision history across
 *   tenants — same family as {@code RunsControllerCrossTenantIdorRuntimeTest} for runs.
 *
 *   <p>Two pins:
 *   <ol>
 *     <li><strong>/orchestration</strong>: org-A caller sees their strategy distribution
 *         + over-time series for ROUTER only. The org-B ROUTER row contributed by the
 *         victim tenant must NOT appear in org-A's response totals.</li>
 *     <li><strong>/orchestration-decisions</strong>: paginated drill-down by strategy
 *         must filter on the caller-bound orgId — org-A asking for "ROUTER" decisions
 *         must NOT see org-B's runId in the page content.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class OrchestrationAggregateCrossTenantLeakRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void orchestrationAggregates_orgACallerSeesOnlyOrgADecisions_notOrgBs() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-orch-A-" + tag;
        String orgB = "org-orch-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("orch-userA-" + tag, orgA);

        String runA = "run-orch-A-" + tag;
        String runB = "run-orch-B-" + tag;
        // Seed one ROUTER decision per org. The same strategy on both sides isolates
        // tenant scoping as the only variable in the test.
        jdbc.update("""
                INSERT INTO orchestration_decisions
                    (run_id, org_id, strategy, decision_type, selected_agent_id, rationale, created_at)
                VALUES (?, ?, 'ROUTER', 'SELECT', 'agent-x', 'org A rationale', now() - interval '1 hour'),
                       (?, ?, 'ROUTER', 'SELECT', 'agent-y', 'org B rationale', now() - interval '1 hour')
                """, runA, orgA, runB, orgB);

        // ── /orchestration ───────────────────────────────────────────────
        ResponseEntity<Map<String, Object>> aggResp = rest.exchange(
                url("/api/v1/observability/aggregates/orchestration?window=30&granularity=DAY"),
                HttpMethod.GET, new HttpEntity<>(userA), JSON_MAP);
        assertEquals(HttpStatus.OK, aggResp.getStatusCode(),
                "tenant caller must read their own orchestration aggregates");

        Map<String, Object> body = aggResp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> distribution = (List<Map<String, Object>>) body.get("distribution");
        assertNotNull(distribution, "response must carry a distribution list");

        long routerCountForOrgA = distribution.stream()
                .filter(d -> "ROUTER".equals(d.get("strategy")))
                .mapToLong(d -> ((Number) d.get("count")).longValue())
                .sum();
        assertEquals(1L, routerCountForOrgA,
                "org-A caller must see exactly ONE ROUTER decision in the distribution — "
                        + "their own. A 2 here means org-B's ROUTER row leaked into the "
                        + "aggregate (cross-tenant leak family — same as FinOps aggregate IDOR).");

        // ── /orchestration-decisions ─────────────────────────────────────
        ResponseEntity<Map<String, Object>> drillResp = rest.exchange(
                url("/api/v1/observability/aggregates/orchestration-decisions?strategy=ROUTER&page=0&size=50"),
                HttpMethod.GET, new HttpEntity<>(userA), JSON_MAP);
        assertEquals(HttpStatus.OK, drillResp.getStatusCode());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) drillResp.getBody().get("content");
        assertNotNull(content);
        assertTrue(content.stream().anyMatch(r -> runA.equals(r.get("runId"))),
                "org-A drill-down must include their own ROUTER decision");
        assertTrue(content.stream().noneMatch(r -> runB.equals(r.get("runId"))),
                "org-A drill-down must NOT include org-B's runId. Presence here means the "
                        + "Pageable repo query for orchestration_decisions doesn't apply the "
                        + "orgId filter or applies it with a null-safe bypass that lets the "
                        + "other tenant through.");

        // Belt-and-suspenders: every returned row's orgId field must equal orgA.
        for (Map<String, Object> row : content) {
            assertEquals(orgA, row.get("orgId"),
                    "every decision returned to an org-A caller must carry orgId=" + orgA
                            + "; got: " + row);
        }
    }
}
