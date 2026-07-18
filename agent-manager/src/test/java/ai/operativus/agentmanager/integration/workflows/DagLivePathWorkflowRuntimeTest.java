package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-3b — the gated live dispatch path. With
 *   the DAG engine at its (default-on) configuration AND a workflow that has explicit {@code workflow_edges},
 *   the normal REST run path (POST /run → WorkflowExecutionJobHandler → executeWorkflowAsync)
 *   delegates to the {@code DagWorkflowExecutor} frontier scheduler instead of the flat step_order
 *   loop, finalizing {@code workflow_runs} to COMPLETED with the threaded payload. The presence of
 *   {@code workflow_node_runs} rows (written ONLY by the DAG path) is the proof the DAG engine ran.
 *
 *   <p>Uses the offline {@code provider='ECHO'} model — no mocks, no LLM key — so the threaded
 *   per-node payload is directly assertable.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class, JobQueueTestSupport.class})
public class DagLivePathWorkflowRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;

    @Test
    void dagEnabled_workflowWithEdges_runsViaFrontierScheduler_diamondFanIn() {
        HttpHeaders auth = newCaller();
        seedEchoModel();
        String wf = createWorkflow(auth, "DAG live diamond");

        // Diamond: A → {B,C} → D, expressed as explicit edges (the DAG path walks these, not step_order).
        String a = insertAgentStep(wf, 1, seedEchoAgent("A (echo)"));
        String b = insertAgentStep(wf, 2, seedEchoAgent("B (echo)"));
        String c = insertAgentStep(wf, 2, seedEchoAgent("C (echo)"));
        String d = insertAgentStep(wf, 3, seedEchoAgent("D (echo)"));
        insertEdge(wf, a, b);
        insertEdge(wf, a, c);
        insertEdge(wf, b, d);
        insertEdge(wf, c, d);

        seedSession("sess-dag-live");
        runWorkflow(auth, wf, "sess-dag-live", "assess vendor X");
        awaitWorkflowStatus(wf, "COMPLETED");

        String runId = jdbc.queryForObject("SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
        // workflow_node_runs rows exist ONLY when the DAG executor ran — proof the gated path engaged.
        Integer nodeRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ?", Integer.class, runId);
        assertEquals(4, nodeRuns, "DAG path must persist one workflow_node_runs row per node");

        String payload = finalPayload(wf);
        assertTrue(payload.contains("[D (echo)]"), "terminal D echo missing: " + payload);
        assertTrue(payload.contains("[B (echo)]"), "fan-in missing B branch: " + payload);
        assertTrue(payload.contains("[C (echo)]"), "fan-in missing C branch: " + payload);
    }

    @Test
    void dagEnabled_legacyWorkflowWithoutEdges_stillUsesFlatEngine() {
        HttpHeaders auth = newCaller();
        seedEchoModel();
        String wf = createWorkflow(auth, "DAG live legacy (no edges)");
        insertAgentStep(wf, 1, seedEchoAgent("A (echo)"));
        insertAgentStep(wf, 2, seedEchoAgent("B (echo)"));
        // No edges → the flag is on but the flat step_order engine must still run this workflow.

        seedSession("sess-dag-legacy");
        runWorkflow(auth, wf, "sess-dag-legacy", "kick off");
        awaitWorkflowStatus(wf, "COMPLETED");

        String runId = jdbc.queryForObject("SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
        Integer nodeRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ?", Integer.class, runId);
        assertEquals(0, nodeRuns, "edge-less workflow must take the flat engine (no node runs)");

        String payload = finalPayload(wf);
        assertTrue(payload.contains("[A (echo)]") && payload.contains("[B (echo)]"),
                "flat engine must still thread both steps: " + payload);
    }

    @Test
    void nodeRunsEndpoint_returnsDagTrace_andIsTenantScoped() {
        HttpHeaders auth = newCaller();
        seedEchoModel();
        String wf = createWorkflow(auth, "DAG trace");
        String a = insertAgentStep(wf, 1, seedEchoAgent("A (echo)"));
        String b = insertAgentStep(wf, 2, seedEchoAgent("B (echo)"));
        insertEdge(wf, a, b);

        seedSession("sess-trace");
        runWorkflow(auth, wf, "sess-trace", "go");
        awaitWorkflowStatus(wf, "COMPLETED");
        String runId = jdbc.queryForObject("SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/node-runs"), HttpMethod.GET,
                new HttpEntity<>(auth), new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> trace = resp.getBody();
        assertEquals(2, trace.size(), "two AGENT nodes ran");
        assertTrue(trace.stream().allMatch(r -> "AGENT".equals(r.get("kind"))), "kind serialized");
        assertTrue(trace.stream().allMatch(r -> Boolean.TRUE.equals(r.get("success"))), "both succeeded");
        assertTrue(trace.stream().anyMatch(r -> String.valueOf(r.get("content")).contains("[B (echo)]")),
                "trace carries threaded node content");

        // Unknown runId → 404 (existence-leak protection).
        ResponseEntity<String> missing = rest.exchange(
                url("/api/v1/workflows/runs/" + java.util.UUID.randomUUID() + "/node-runs"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    // ─── Helpers (echo harness, mirrors DemoEchoWorkflowRuntimeTest) ──────────

    private HttpHeaders newCaller() {
        String username = "dag-live-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local", "pwd-dag-live-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private void seedEchoModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('echo-test', 'Echo (test)', 'ECHO', 'echo-demo', false, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private String seedEchoAgent(String name) {
        String id = "agent-echo-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'echo-test', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, name);
        return id;
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'dag-live-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private String insertAgentStep(String wf, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'AGENT')", id, wf, order, agentId);
        return id;
    }

    private void insertEdge(String wf, String from, String to) {
        jdbc.update("INSERT INTO workflow_edges (id, workflow_id, from_step_id, to_step_id, created_at) "
                + "VALUES (?, ?, ?, ?, now())", "edge-" + UUID.randomUUID(), wf, from, to);
    }

    private void runWorkflow(HttpHeaders auth, String wf, String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + wf + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(accepted.getBody().get("jobId")), Duration.ofSeconds(20));
    }

    private void awaitWorkflowStatus(String wf, String expected) {
        Awaitility.await("workflow_runs.status=" + expected + " for " + wf)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = ?",
                            Integer.class, wf, expected);
                    return n != null && n >= 1;
                });
    }

    private String finalPayload(String wf) {
        return jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
    }
}
