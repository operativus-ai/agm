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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-3c end-to-end through the REAL production resume path — not the
 *   executor directly. With the DAG engine at its default-on configuration, a workflow whose entry node is a
 *   ROUTER HITL selector runs via the frontier scheduler, pauses to {@code AWAITING_ROUTE_SELECTION}
 *   with a persisted {@code dag_frontier}, and the {@code POST /runs/{id}/continue} endpoint (→
 *   WorkflowContinueJobHandler → WorkflowService.continueAfterRouteSelection → resumeViaDag) re-enters
 *   the exact graph, activating ONLY the chosen branch, finishing COMPLETED. Closes the gap that the
 *   executor-level {@code DagResumeRuntimeTest} leaves (the WorkflowService routing + JSONB persistence
 *   + pause-kind→status mapping). Offline {@code ECHO} model — no mocks, no LLM key.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class, JobQueueTestSupport.class})
public class DagResumeLivePathRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;

    @Test
    void routerHitl_pausesAwaitingRouteSelection_thenContinueResumesChosenBranch() {
        HttpHeaders auth = newCaller();
        seedEchoModel();
        String wf = createWorkflow(auth, "DAG resume live");

        // ROUTER(entry, HITL) → {approve, reject}; the HITL selector pauses the run.
        String router = insertStep(wf, 1, null, "ROUTER");
        String approve = insertStep(wf, 2, seedEchoAgent("Approve (echo)"), "AGENT");
        String reject = insertStep(wf, 2, seedEchoAgent("Reject (echo)"), "AGENT");
        setRouterConfigHitl(router, Map.of("approve", approve, "reject", reject));
        insertEdge(wf, router, approve, "approve");
        insertEdge(wf, router, reject, "reject");

        seedSession("sess-dag-resume-live");
        runWorkflow(auth, wf, "sess-dag-resume-live", "{}");

        // 1. The DAG path paused at the ROUTER → AWAITING_ROUTE_SELECTION with a persisted frontier.
        awaitWorkflowStatus(wf, "AWAITING_ROUTE_SELECTION");
        String runId = jdbc.queryForObject("SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
        String frontier = jdbc.queryForObject(
                "SELECT dag_frontier::text FROM workflow_runs WHERE id = ?", String.class, runId);
        assertNotNull(frontier, "dag_frontier must be persisted on the paused run");
        assertTrue(frontier.contains(router), "frontier must name the paused ROUTER node: " + frontier);
        assertEquals(1, nodeRunCount(runId), "only the ROUTER ran before the pause");

        // 1b. The route-options picker resolves the ROUTER via the frontier (the DAG path never
        //     sets current_step_order — the flat-only resolution returned an empty picker).
        ResponseEntity<Map<String, Object>> options = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/route-options"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, options.getStatusCode());
        assertEquals(Boolean.TRUE, options.getBody().get("awaitingRouteSelection"));
        assertEquals(List.of("approve", "reject"), options.getBody().get("choiceKeys"),
                "picker must list the paused DAG ROUTER's choices: " + options.getBody());

        // 2. POST /continue with the operator's choice → resumes via the DAG path.
        ResponseEntity<Map<String, Object>> cont = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/continue"), HttpMethod.POST,
                new HttpEntity<>(Map.of("choiceKey", "approve"), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, cont.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(cont.getBody().get("jobId")), Duration.ofSeconds(20));

        // 3. The run completes, having executed ONLY the chosen branch; the frontier is cleared.
        awaitWorkflowStatus(wf, "COMPLETED");
        String payload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, runId);
        assertTrue(payload.contains("[Approve (echo)]"), "chosen branch missing from final payload: " + payload);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ? AND node_id = ?",
                Integer.class, runId, reject), "reject branch must never run");
        String clearedFrontier = jdbc.queryForObject(
                "SELECT dag_frontier::text FROM workflow_runs WHERE id = ?", String.class, runId);
        assertEquals(null, clearedFrontier, "frontier must be cleared once the run completes");
    }

    // ─── Helpers (echo harness, mirrors DagLivePathWorkflowRuntimeTest) ────────

    private int nodeRunCount(String runId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ?",
                Integer.class, runId);
        return n == null ? 0 : n;
    }

    private HttpHeaders newCaller() {
        String username = "dag-resume-live-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local", "pwd-dag-rl-1234",
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
                VALUES (?, 'dag-resume-live-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private String insertStep(String wf, int order, String agentId, String action) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, ?)", id, wf, order, agentId, action);
        return id;
    }

    private void setRouterConfigHitl(String stepId, Map<String, String> choices) {
        StringBuilder choicesJson = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> e : choices.entrySet()) {
            if (i++ > 0) choicesJson.append(",");
            choicesJson.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        choicesJson.append("}");
        String json = "{\"selectorType\":\"HITL\",\"selectorExpression\":null,\"choices\":"
                + choicesJson + ",\"defaultChoice\":null}";
        jdbc.update("UPDATE workflow_steps SET router_config = ?::jsonb WHERE id = ?", json, stepId);
    }

    private void insertEdge(String wf, String from, String to, String port) {
        jdbc.update("INSERT INTO workflow_edges (id, workflow_id, from_step_id, to_step_id, condition, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", "edge-" + UUID.randomUUID(), wf, from, to, port);
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
}
