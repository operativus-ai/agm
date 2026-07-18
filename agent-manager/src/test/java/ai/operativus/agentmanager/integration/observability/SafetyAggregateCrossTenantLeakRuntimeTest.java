package ai.operativus.agentmanager.integration.observability;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
 * Domain Responsibility: Pins tenant scoping on {@code /api/v1/observability/aggregates/safety}.
 *   {@link ai.operativus.agentmanager.control.repository.RunRepository#findFlaggedRunsTop}
 *   returns rows from {@code agent_runs} where {@code safety_risk_score IS NOT NULL}
 *   filtered by the standard {@code (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)}
 *   predicate. A regression that drops the orgId binding would leak another tenant's
 *   flagged-run IDs into the caller's response — including the runId, agentId, and
 *   safety risk score (highly sensitive — exposes which tenants had which violations).
 *
 *   <p>Pin: seed one flagged run per org and assert the org-A caller's flagged list
 *   contains only their runId.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SafetyAggregateCrossTenantLeakRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void safetyAggregates_orgACallerSeesOnlyOwnFlaggedRuns() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-safe-A-" + tag;
        String orgB = "org-safe-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("safe-userA-" + tag, orgA);

        // FK precondition: agents row for each org_id used on the run rows.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentA = "agent-safe-A-" + tag;
        String agentB = "agent-safe-B-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now()),
                       (?, 'agent B', 'gpt-4o-mini', true, ?, now(), now())
                """, agentA, orgA, agentB, orgB);

        // One flagged run per org. Distinctive safety_risk_score per side so a leak
        // also shows up in the score values returned.
        String runA = "run-safe-A-" + tag;
        String runB = "run-safe-B-" + tag;
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, user_id, status, input, output,
                                        safety_risk_score, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'COMPLETED', 'a-in', 'a-out', 0.81, now() - interval '1 hour', now() - interval '1 hour'),
                       (?, ?, ?, NULL, 'COMPLETED', 'b-in', 'b-out', 0.92, now() - interval '1 hour', now() - interval '1 hour')
                """, runA, agentA, orgA, runB, agentB, orgB);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/observability/aggregates/safety?window=30"),
                HttpMethod.GET, new HttpEntity<>(userA), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flagged = (List<Map<String, Object>>) body.get("flaggedRunsTopN");
        assertNotNull(flagged, "response must carry a flaggedRunsTopN list");

        assertTrue(flagged.stream().anyMatch(r -> runA.equals(r.get("runId"))),
                "org-A caller must see their own flagged run; got: " + flagged);
        assertTrue(flagged.stream().noneMatch(r -> runB.equals(r.get("runId"))),
                "org-A caller must NOT see org-B's flagged runId. Presence here means "
                        + "findFlaggedRunsTop's orgId filter leaks — cross-tenant safety "
                        + "IDOR (which is particularly sensitive: it discloses which other "
                        + "tenants had safety violations).");
    }
}
