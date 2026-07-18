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
 * Domain Responsibility: Pins tenant scoping on {@code /api/v1/observability/aggregates/tools}.
 *   {@link com.operativus.agentmanager.control.repository.AgentRunEventRepository#aggregateToolUsage}
 *   reads {@code agent_run_events} rows of type {@code TOOL_COMPLETED} and groups by
 *   {@code payload->>'toolName'}. The query has the standard
 *   {@code (:orgId IS NULL OR org_id IS NULL OR org_id = :orgId)} predicate; a regression
 *   that drops the binding or that mis-applies the JSONB extraction would surface
 *   another tenant's tool-usage rollup.
 *
 *   <p>Pin: seed {@code TOOL_COMPLETED} events per org with distinct toolNames so the
 *   leak — if present — would surface as the wrong toolName appearing in the response.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ToolAggregateCrossTenantLeakRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void toolAggregates_orgACallerSeesOnlyOwnToolEvents() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-tool-A-" + tag;
        String orgB = "org-tool-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("tool-userA-" + tag, orgA);

        // Two distinct tool names per org so a leak surfaces as the wrong toolName in
        // the response, not as a count discrepancy that could be masked by aggregation.
        String orgAToolName = "tool_A_" + tag;
        String orgBToolName = "tool_B_" + tag;

        // Seed one TOOL_COMPLETED event per org. event_ts must be recent enough to
        // fall inside the default 30-day window the aggregate filters on.
        jdbc.update("""
                INSERT INTO agent_run_events (event_type, run_id, agent_id, parent_run_id,
                                              session_id, org_id, orchestration_depth, payload, event_ts)
                VALUES ('TOOL_COMPLETED', ?, 'agent-x', NULL, 'sess-x', ?, 0,
                        jsonb_build_object('toolName', ?::text, 'status', 'ok', 'durationMs', 100),
                        now() - interval '1 hour')
                """, "run-tool-A-" + tag, orgA, orgAToolName);
        jdbc.update("""
                INSERT INTO agent_run_events (event_type, run_id, agent_id, parent_run_id,
                                              session_id, org_id, orchestration_depth, payload, event_ts)
                VALUES ('TOOL_COMPLETED', ?, 'agent-y', NULL, 'sess-y', ?, 0,
                        jsonb_build_object('toolName', ?::text, 'status', 'ok', 'durationMs', 200),
                        now() - interval '1 hour')
                """, "run-tool-B-" + tag, orgB, orgBToolName);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/observability/aggregates/tools?window=30&granularity=DAY"),
                HttpMethod.GET, new HttpEntity<>(userA), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertNotNull(tools, "response must carry a tools list");

        assertTrue(tools.stream().anyMatch(t -> orgAToolName.equals(t.get("toolName"))),
                "org-A caller must see their own tool in the rollup — got: " + tools);
        assertTrue(tools.stream().noneMatch(t -> orgBToolName.equals(t.get("toolName"))),
                "org-A caller must NOT see org-B's toolName in the rollup. Presence here "
                        + "means aggregateToolUsage's orgId filter leaks — cross-tenant "
                        + "tool-usage IDOR. tools list: " + tools);
    }
}
