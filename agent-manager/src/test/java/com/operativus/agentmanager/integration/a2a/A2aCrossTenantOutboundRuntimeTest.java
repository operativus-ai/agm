package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the cross-tenant boundary on
 *   {@code POST /api/v1/a2a/tasks}. The executor's tenant boundary
 *   (per A2ATaskExecutor.executeTask comment) is enforced by passing {@code callerOrgId}
 *   to {@code AgentRegistry.findById}; a cross-tenant lookup surfaces as
 *   "Agent not found" and lands in the FAILED branch — NOT a silent execution that
 *   would let a user in org B drive an agent owned by org A.
 *
 *   This test pins that contract by registering an agent under org A, then having a
 *   user in org B submit a task with that agent's id as {@code targetAgentId}. The
 *   task must end up with a FAILED audit row whose {@code error_detail} indicates the
 *   agent was not found in the caller's tenant scope.
 *
 *   Complements {@code A2aMeshRuntimeTest#crossOrgPeerIsolation_listPeersScopesByOrgId}
 *   which covers the LIST path; this covers SUBMIT.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aCrossTenantOutboundRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
        jdbc.update("TRUNCATE TABLE a2a_task_events");
    }

    @Test
    void submitTask_targetingAgent_inDifferentOrg_lands_inFAILED_branch_notSilentExecute() {
        // Org A user creates an agent.
        String orgA = "org-a-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("user-a-" + UUID.randomUUID(), orgA);
        String orgAAgentId = createAgent(authA, "org-A-owned agent");

        // Org B user submits an A2A task targeting org A's agent.
        String orgB = "org-b-" + UUID.randomUUID();
        HttpHeaders authB = registerLoginWithOrg("user-b-" + UUID.randomUUID(), orgB);

        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", orgAAgentId);  // ← cross-tenant target
        body.put("input", "cross-tenant attempt");
        body.put("initiatingAgentId", "peer-init-cross-tenant");
        body.put("sessionId", "sess-" + UUID.randomUUID());

        // Status only — text/event-stream emitter completes-with-error on the cross-tenant reject,
        // closing the chunked body abruptly; the outcome is asserted via a2a_task_events below.
        assertEquals(200, postForStatusNoBody("/api/v1/a2a/tasks", body, authB).value(),
                "POST /tasks still 200s — the SseEmitter is constructed; the cross-tenant rejection "
                        + "happens inside the executor's v-thread, not at request validation");

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status IN ('FAILED', 'COMPLETED')",
                        Integer.class, taskId) > 0);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, error_detail FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                taskId);

        Map<String, Object> terminal = rows.get(rows.size() - 1);
        assertEquals(A2aTaskStatus.FAILED.name(), terminal.get("status"),
                "cross-tenant target must land in FAILED — silent COMPLETED here would mean an "
                        + "org B user drove an org A agent, breaking the §22.7 tenant boundary");
        assertThat(terminal.get("error_detail"))
                .as("error_detail must indicate the lookup failed so operators can distinguish "
                        + "cross-tenant attempts from genuine downstream failures")
                .isNotNull();
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "cross-tenant fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201");
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }
}
