package com.operativus.agentmanager.integration.agents;

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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/dx-metrics} —
 *   {@link com.operativus.agentmanager.control.controller.AgentAdminController#getDeveloperMetrics}.
 *
 *   <p>Contract from
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#getDeveloperMetrics}:
 *   <ul>
 *     <li>{@code testabilityScore = min(100, evaluationCount * 10)}</li>
 *     <li>{@code maintainabilityGrade} from {@code tools.size() + configuration.size()}:
 *         {@code >20 -> F, >15 -> D, >10 -> C, >5 -> B, else -> A}</li>
 *     <li>{@code evaluationCount} = number of rows in {@code evaluations} where
 *         {@code agent_id = id}</li>
 *     <li>Tenant-scoped: cross-tenant requests surface as 404 via {@code ResourceNotFoundException}</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentDeveloperMetricsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void agentWithZeroEvaluationsAndNoComplexity_returnsZeroScoreGradeA() {
        String orgId = "org-dx-zero-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("dx-zero", orgId);
        String agentId = seedAgent(orgId);

        Map<String, Object> body = getMetrics(auth, agentId);

        assertAll("zero-eval zero-complexity agent contract",
                () -> assertEquals(0.0, ((Number) body.get("testabilityScore")).doubleValue(), 0.001,
                        "0 evaluations -> testabilityScore=0; got " + body.get("testabilityScore")),
                () -> assertEquals("A", body.get("maintainabilityGrade"),
                        "no tools + no config -> grade A; got " + body.get("maintainabilityGrade")),
                () -> assertEquals(0L, ((Number) body.get("evaluationCount")).longValue(),
                        "0 evaluations expected"));
    }

    @Test
    void evaluationCountScales_5evals_yields50score_capsAt100() {
        String orgId = "org-dx-scale-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("dx-scale", orgId);
        String agentId = seedAgent(orgId);

        // Seed 5 evaluations -> score = 50.
        for (int i = 0; i < 5; i++) {
            seedEvaluation(agentId, "ev-scale-" + i);
        }

        Map<String, Object> body = getMetrics(auth, agentId);
        assertEquals(50.0, ((Number) body.get("testabilityScore")).doubleValue(), 0.001,
                "5 evals * 10 = 50; got " + body.get("testabilityScore"));
        assertEquals(5L, ((Number) body.get("evaluationCount")).longValue());
    }

    @Test
    void evaluationCountAbove10_testabilityScoreCapsAt100() {
        String orgId = "org-dx-cap-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("dx-cap", orgId);
        String agentId = seedAgent(orgId);

        // 15 evals -> would be 150 uncapped; the Math.min(100, ...) clamp keeps it at 100.
        for (int i = 0; i < 15; i++) {
            seedEvaluation(agentId, "ev-cap-" + i);
        }

        Map<String, Object> body = getMetrics(auth, agentId);
        assertEquals(100.0, ((Number) body.get("testabilityScore")).doubleValue(), 0.001,
                "15 evals must clamp at testabilityScore=100; got " + body.get("testabilityScore"));
        assertEquals(15L, ((Number) body.get("evaluationCount")).longValue(),
                "evaluationCount is unclamped and reports the actual row count");
    }

    @Test
    void unknownAgentId_returns404_notFiveHundred() {
        String orgId = "org-dx-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("dx-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/dx-metrics"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "unknown id must surface as 404 (ResourceNotFoundException → GlobalExceptionHandler)");
    }

    @Test
    void crossTenantAgentId_returns404_noScoreLeak() {
        String orgA = "org-dx-cross-A-" + UUID.randomUUID();
        String orgB = "org-dx-cross-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("dx-cross-a", orgA);
        registerLoginWithOrg("dx-cross-b", orgB);

        String foreignAgent = seedAgent(orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/dx-metrics"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant dx-metrics must 404 — metric values must not leak via response body");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String seedAgent(String orgId) {
        String agentId = "agent-dx-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "DX Metrics Test Agent", orgId);
        return agentId;
    }

    private void seedEvaluation(String agentId, String suffix) {
        String evId = "eval-" + suffix + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluations (id, name, agent_id, status, created_at)
                VALUES (?, ?, ?, 'PENDING', now())
                """, evId, "Evaluation " + suffix, agentId);
    }

    private Map<String, Object> getMetrics(HttpHeaders auth, String agentId) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/dx-metrics"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "happy path must return 200; got " + resp.getStatusCode());
        assertNotNull(resp.getBody());
        return resp.getBody();
    }
}
