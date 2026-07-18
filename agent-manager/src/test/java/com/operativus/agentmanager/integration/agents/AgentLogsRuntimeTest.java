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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/logs}. Returns
 *   {@code List<String>}: synthetic placeholder lines when the agent has no runs,
 *   real log lines aggregated from agent_runs when it does. Tenant-scoped.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentLogsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<String>> JSON_LIST_STRING =
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
    void logsForAgentWithNoRuns_returnsSyntheticPlaceholderLines() {
        String orgId = "org-logs-empty-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("logs-empty", orgId);
        String agentId = seedAgent(orgId);

        ResponseEntity<List<String>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/logs"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST_STRING);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().size() >= 2,
                "no-run agent must return at least 2 placeholder lines; got "
                        + resp.getBody().size());
        assertTrue(resp.getBody().get(0).toUpperCase().contains("INITIALIZED"),
                "first placeholder must signal initialization; got '" + resp.getBody().get(0) + "'");
    }

    @Test
    void logsForUnknownAgentId_returns404() {
        String orgId = "org-logs-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("logs-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/logs"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void logsForCrossTenantAgent_returns404_noLogLeak() {
        String orgA = "org-logs-A-" + UUID.randomUUID();
        String orgB = "org-logs-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("logs-a", orgA);
        registerLoginWithOrg("logs-b", orgB);
        String foreignAgent = seedAgent(orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/logs"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant logs must 404 — log lines must not leak");
    }

    private String seedAgent(String orgId) {
        String agentId = "agent-logs-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Logs test agent", orgId);
        return agentId;
    }
}
