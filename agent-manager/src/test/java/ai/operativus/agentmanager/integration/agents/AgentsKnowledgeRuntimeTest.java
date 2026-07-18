package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the two agent↔knowledge seams —
 *   {@code POST /api/agents/{agentId}/knowledge/load} (handled by
 *   {@link ai.operativus.agentmanager.control.controller.AgentsController#loadKnowledge},
 *   which enqueues a {@code KNOWLEDGE_INGESTION} job dispatched by
 *   {@link ai.operativus.agentmanager.control.service.queue.KnowledgeIngestionJobHandler}
 *   →{@link ai.operativus.agentmanager.compute.service.AgentService#loadKnowledge}) and
 *   {@code POST/DELETE /api/v1/knowledge-bases/{id}/agents/{agentId}} (handled by
 *   {@link ai.operativus.agentmanager.control.controller.KnowledgeBaseController}
 *   →{@link ai.operativus.agentmanager.control.service.KnowledgeBaseService}).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §5.12 (knowledge load enqueues
 *   {@code KnowledgeIngestionJobHandler}) plus the KB-binding surface referenced in §20
 *   (Knowledge Bases & RAG, "bind an agent to a KB").
 *
 * Implementation notes / gaps these tests pin:
 *   - The controller is fire-and-forget — it returns {@code 202 Accepted} with
 *     {@code {"jobId": "..."}} and never blocks on execution. The enqueued
 *     {@code background_jobs} row carries {@code job_type=KNOWLEDGE_INGESTION},
 *     {@code agent_id=<agentId>}, and a payload of {@code {"agentId":"<id>"}}.
 *   - {@code AgentService.loadKnowledge} throws
 *     {@code IllegalArgumentException("No bootstrapKnowledgeUrls configured for agent: <id>")}
 *     when the agent's {@code configuration.bootstrapKnowledgeUrls} is absent. We pin this
 *     failure path explicitly rather than drive a real URL ingestion — hitting Jsoup against
 *     a network endpoint would make the suite flaky and is out of scope for the harness
 *     (decision 4.5 in {@code agm-runtime-testing-spec.md}).
 *   - KB ↔ Agent binding is stored on the agent side as a JSONB {@code knowledge_base_ids}
 *     array on the {@code agents} row (not a join table), so assertions verify the column
 *     text includes the KB id. {@code getAssignedAgents} reverses the lookup via
 *     {@code AgentRepository.findByKnowledgeBaseIdsContaining}.
 *   - The response shape from {@code GET /knowledge-bases/{id}/agents} is
 *     {@link ai.operativus.agentmanager.control.controller.model.AgentSummary} — a record
 *     with field name {@code agentId} (NOT {@code id}). Tests must key off {@code agentId}
 *     when mapping responses to {@code Map<String,Object>}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, JobQueueTestSupport.class})
public class AgentsKnowledgeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private JobQueueTestSupport jobs;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §5.12 — POST /api/agents/{id}/knowledge/load enqueues a KNOWLEDGE_INGESTION job with the
    // correct payload shape; when the agent has no bootstrapKnowledgeUrls configured, the
    // handler propagates an IllegalArgumentException from AgentService.loadKnowledge and the
    // queue's retry/DLQ machinery lands the job in a terminal error state with the message
    // intact. Pins four things at once:
    //   (1) controller enqueues via PersistentJobQueueService (202 + jobId returned immediately),
    //   (2) background_jobs row carries job_type + agent_id + payload as expected,
    //   (3) KnowledgeIngestionJobHandler dispatches to AgentOperations.loadKnowledge,
    //   (4) AgentService.loadKnowledge's "missing bootstrap URLs" guard surfaces through
    //       the queue's errorMessage capture.
    @Test
    void knowledgeLoadEnqueuesKnowledgeIngestionJobAndFailsWithBootstrapMessageWhenUnconfigured() {
        HttpHeaders auth = authenticatedHeaders("knowledge-loader");
        String agentId = createAgentViaApi(auth, "Unconfigured Agent");

        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/agents/" + agentId + "/knowledge/load"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode(),
                "knowledge/load is fire-and-forget: 202 Accepted with {\"jobId\":...} returned immediately");
        String jobId = (String) accepted.getBody().get("jobId");
        assertNotNull(jobId);

        Map<String, Object> jobRow = jdbc.queryForMap(
                "SELECT job_type, agent_id, payload FROM background_jobs WHERE id = ?", jobId);
        assertEquals("KNOWLEDGE_INGESTION", jobRow.get("job_type"),
                "controller must enqueue with the exact job_type KnowledgeIngestionJobHandler is registered under");
        assertEquals(agentId, jobRow.get("agent_id"),
                "background_jobs.agent_id must carry the target agentId for observability filtering");
        assertTrue(((String) jobRow.get("payload")).contains("\"agentId\":\"" + agentId + "\""),
                "payload must be a Payload(agentId) JSON so the handler can deserialize it; got: " + jobRow.get("payload"));

        // Collapse the outer retry window so one processNow() pass lands the job terminal.
        jdbc.update("UPDATE background_jobs SET max_retries = 0 WHERE id = ?", jobId);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobFailure(jobId, Duration.ofSeconds(10));
        String status = terminal.getStatus().name();
        assertTrue("DLQ".equals(status) || "FAILED".equals(status),
                "missing bootstrapKnowledgeUrls must land the job in DLQ/FAILED; got " + status);
        assertNotNull(terminal.getErrorMessage());
        assertTrue(terminal.getErrorMessage().contains("bootstrapKnowledgeUrls"),
                "error message must mention the missing config key so operators can diagnose without logs; got: "
                        + terminal.getErrorMessage());
    }

    // §20 (KB↔Agent binding) — assign + list + remove round-trip through KnowledgeBaseService.
    // Pins that the binding is stored on agents.knowledge_base_ids JSONB (not a join table),
    // that getAssignedAgents reverses the lookup via the JSONB containment query, and that
    // DELETE removes the binding idempotently without affecting the agent row itself.
    @Test
    void assignAgentToKnowledgeBaseWritesJsonbAndIsObservableViaGetAssignedAgents() {
        HttpHeaders auth = authenticatedHeaders("kb-binder");
        String agentId = createAgentViaApi(auth, "KB Target Agent");

        Map<String, Object> kbBody = Map.of(
                "name", "Procurement KB " + UUID.randomUUID(),
                "description", "KB fixture for AgentsKnowledgeRuntimeTest");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST, new HttpEntity<>(kbBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, kbCreated.getStatusCode(),
                "POST /api/v1/knowledge-bases returns 200 (controller returns the saved entity with no explicit @ResponseStatus)");
        String kbId = (String) kbCreated.getBody().get("id");
        assertNotNull(kbId, "KB response must carry the UUID primary key");

        ResponseEntity<Void> assigned = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents/" + agentId),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, assigned.getStatusCode(),
                "assignment endpoint must return 204 No Content on success");

        String jsonb = jdbc.queryForObject(
                "SELECT knowledge_base_ids::text FROM agents WHERE id = ?", String.class, agentId);
        assertNotNull(jsonb, "agents.knowledge_base_ids must be populated after assignment");
        assertTrue(jsonb.contains(kbId),
                "assignAgentToKb writes into the JSONB array on the agents row; got: " + jsonb);

        ResponseEntity<List<Map<String, Object>>> assignedList = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, assignedList.getStatusCode());
        assertNotNull(assignedList.getBody());
        // Response shape is AgentSummary(agentId, name, description) — the record field is agentId, not id.
        boolean visible = assignedList.getBody().stream().anyMatch(a -> agentId.equals(a.get("agentId")));
        assertTrue(visible,
                "getAssignedAgents must reverse the lookup via findByKnowledgeBaseIdsContaining and surface the bound agent");

        ResponseEntity<Void> removed = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents/" + agentId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, removed.getStatusCode());

        ResponseEntity<List<Map<String, Object>>> afterRemove = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, afterRemove.getStatusCode());
        assertTrue(afterRemove.getBody() == null || afterRemove.getBody().isEmpty(),
                "DELETE must remove the binding so the agent no longer surfaces under the KB");

        Long stillPresent = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(1L, stillPresent, "removing the binding must leave the agent row itself intact");
    }

    // ─── helpers ───

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Knowledge test owner");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before knowledge operations reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kn-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
