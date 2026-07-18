package com.operativus.agentmanager.integration.agents;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins G4 — the AgentsController run/stream/runs-background endpoints no longer trust
 * the request body's {@code orgId} field. Pre-G4, a tenant user could spoof attribution
 * of their agent run to ANY org by setting {@code request.orgId} in the POST body.
 * Post-G4, the controller reads from JWT-bound {@link com.operativus.agentmanager.core.callback.AgentContextHolder#getOrgId()};
 * the body field is only honored when no principal is bound (super-admin / unauthenticated).
 *
 * <p>This test:
 * <ol>
 *   <li>Logs in a tenant user bound via JWT claim to {@code org_id=org-victim}.</li>
 *   <li>Creates an agent owned by that org (stamped server-side).</li>
 *   <li>Hits {@code POST /api/agents/{id}/runs} with a body claiming {@code orgId=org-attacker}.</li>
 *   <li>Asserts the run is attributed to {@code org-victim} (the JWT-bound value),
 *       NOT {@code org-attacker} (the spoof attempt), by inspecting
 *       {@code agent_runs.org_id}.</li>
 * </ol>
 *
 * <p>FakeChatModelConfig is imported so the run completes synchronously without hitting
 * a real LLM provider — we care about the orgId attribution, not the chat output.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentRunOrgIdSpoofRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void agentRun_spoofedOrgIdInBody_isIgnored_runAttributedToJwtBoundOrg() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String victimOrg = "org-victim-" + tag;
        String attackerOrg = "org-attacker-" + tag;

        HttpHeaders victimAuth = registerLoginWithOrg("agent-run-spoof-victim-" + tag, victimOrg);

        // Seed a model row first to satisfy agents.model_id FK, then the agent under
        // the victim org. Skipping the admin create flow keeps this test focused on the
        // run-path spoof rather than the agent-creation path.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-spoof-probe-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "spoof probe agent", victimOrg);

        long runsBefore = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE agent_id = ?",
                Long.class, agentId);

        // The spoof: body claims orgId=attackerOrg, but JWT carries org_id=victimOrg.
        Map<String, Object> body = new HashMap<>();
        body.put("message", "spoof probe");
        body.put("sessionId", "session-spoof-" + tag);
        body.put("orgId", attackerOrg);
        body.put("userId", "user-attacker-pretender");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, victimAuth), JSON_MAP);

        // Accept any non-5xx — the run may succeed (200) or fail for unrelated reasons
        // (model resolution, etc.) but the orgId attribution to the persisted row is
        // what we're pinning here. The run-path always inserts an agent_runs row even
        // for failed runs (FAILED status).
        assertEquals(false, resp.getStatusCode().is5xxServerError(),
                "controller must not 500 on a regular tenant run attempt; got: " + resp.getStatusCode());

        // Verify a new agent_runs row landed for this agent.
        long runsAfter = jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE agent_id = ?",
                Long.class, agentId);
        assertEquals(runsBefore + 1, runsAfter,
                "POST /api/agents/{id}/runs must insert exactly one agent_runs row for this run");

        // The IDOR pin: the run was attributed to the JWT-bound orgId, NOT the body-spoofed one.
        @SuppressWarnings("unchecked")
        List<String> orgIds = (List<String>) (List<?>) jdbc.queryForList(
                "SELECT org_id FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, agentId);
        assertEquals(1, orgIds.size(), "exactly one run row expected for this agent");
        String runOrgId = orgIds.get(0);

        assertEquals(victimOrg, runOrgId,
                "G4: the agent_runs row must be attributed to the JWT-bound orgId ("
                        + victimOrg + "), NOT the body-spoofed orgId (" + attackerOrg + "). "
                        + "Pre-G4 this assertion would fail because AgentsController.run "
                        + "passed request.orgId() directly to agentOperations.run.");
        assertNotEquals(attackerOrg, runOrgId,
                "the attacker-supplied orgId must not appear on the persisted run row");
    }
}
