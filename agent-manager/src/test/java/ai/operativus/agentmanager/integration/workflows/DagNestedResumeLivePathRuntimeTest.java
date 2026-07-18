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
 * Domain Responsibility: Proves nested-frontier resume through the REAL production path — a parent
 *   workflow whose entry WORKFLOW node wraps a child containing a ROUTER-HITL gate. The run pauses
 *   to {@code AWAITING_ROUTE_SELECTION} (the child's ROUTE pause kind bubbles up through the
 *   WORKFLOW node) with a persisted {@code dag_frontier} carrying {@code nestedPauses}; the
 *   {@code POST /runs/{id}/continue} endpoint validates the operator's choiceKey against the
 *   INNERMOST child ROUTER's config (the nested descent in {@code dagSettledRouteOutput}) and the
 *   resume routes back into the child, finishing COMPLETED with only the chosen branch executed.
 *   Closes the control-layer gap the executor-level {@code DagNestedResumeRuntimeTest} leaves.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class, JobQueueTestSupport.class})
public class DagNestedResumeLivePathRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;

    @Test
    void nestedRouterHitl_pausesAwaitingRouteSelection_continueValidatesAgainstChildRouter_andCompletes() {
        HttpHeaders auth = newCaller();
        seedEchoModel();

        // Child workflow: R(ROUTER HITL) → {approve: A, reject: B}.
        String childWf = createWorkflow(auth, "nested child");
        String approve = insertStep(childWf, 2, seedEchoAgent("Approve (echo)"), "AGENT");
        String reject = insertStep(childWf, 2, seedEchoAgent("Reject (echo)"), "AGENT");
        String router = insertStep(childWf, 1, null, "ROUTER");
        setRouterConfigHitl(router, Map.of("approve", approve, "reject", reject));
        insertEdge(childWf, router, approve, "approve");
        insertEdge(childWf, router, reject, "reject");

        // Parent workflow: W(WORKFLOW → child) → P(AGENT echo).
        String parentWf = createWorkflow(auth, "nested parent");
        String w = insertStep(parentWf, 1, childWf, "WORKFLOW");
        String p = insertStep(parentWf, 2, seedEchoAgent("P (echo)"), "AGENT");
        insertEdge(parentWf, w, p, null);

        seedSession("sess-nested-live");
        runWorkflow(auth, parentWf, "sess-nested-live", "kickoff");

        // 1. The child's ROUTE pause bubbles to AWAITING_ROUTE_SELECTION; the persisted frontier
        //    carries the nested resume state keyed by the parent WORKFLOW node.
        awaitWorkflowStatus(parentWf, "AWAITING_ROUTE_SELECTION");
        String runId = jdbc.queryForObject("SELECT id FROM workflow_runs WHERE workflow_id = ?",
                String.class, parentWf);
        String frontier = jdbc.queryForObject(
                "SELECT dag_frontier::text FROM workflow_runs WHERE id = ?", String.class, runId);
        assertNotNull(frontier, "dag_frontier must be persisted on the paused run");
        assertTrue(frontier.contains("nestedPauses") && frontier.contains(childWf),
                "frontier must carry the nested child resume state: " + frontier);

        // 1b. The route-options picker descends the nested chain to the CHILD's ROUTER — the parent
        //     WORKFLOW node has no routerConfig of its own.
        ResponseEntity<Map<String, Object>> options = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/route-options"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, options.getStatusCode());
        assertEquals(Boolean.TRUE, options.getBody().get("awaitingRouteSelection"));
        assertEquals(List.of("approve", "reject"), options.getBody().get("choiceKeys"),
                "picker must list the nested child ROUTER's choices: " + options.getBody());

        // 2. POST /continue with the choiceKey declared on the CHILD's router (validated via the
        //    nested descent — the parent WORKFLOW node has no routerConfig of its own).
        ResponseEntity<Map<String, Object>> cont = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/continue"), HttpMethod.POST,
                new HttpEntity<>(Map.of("choiceKey", "approve"), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, cont.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(cont.getBody().get("jobId")), Duration.ofSeconds(20));

        // 3. Completed: chosen child branch + parent successor ran; reject branch never did.
        awaitWorkflowStatus(parentWf, "COMPLETED");
        String payload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, runId);
        assertTrue(payload.contains("[Approve (echo)]"), "chosen child branch missing: " + payload);
        assertTrue(payload.contains("[P (echo)]"), "parent successor missing: " + payload);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_runs WHERE node_id = ?", Integer.class, reject),
                "reject branch must never run");

        // 4. The nested trace endpoint surfaces what ran INSIDE the WORKFLOW node, grouped per
        //    child invocation and attributed to the parent W node (child rows live under a derived
        //    run id, so the plain /node-runs trace never shows them).
        ResponseEntity<List<Map<String, Object>>> childTraces = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/child-node-runs"), HttpMethod.GET,
                new HttpEntity<>(auth), new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertEquals(HttpStatus.OK, childTraces.getStatusCode());
        assertEquals(1, childTraces.getBody().size(), "one child invocation: " + childTraces.getBody());
        Map<String, Object> group = childTraces.getBody().get(0);
        assertEquals(w, group.get("parentNodeId"), "group attributed to the parent WORKFLOW node");
        assertEquals(childWf, group.get("childWorkflowId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> childRuns = (List<Map<String, Object>>) group.get("nodeRuns");
        assertTrue(childRuns.size() >= 2, "child ROUTER + approve agent rows expected: " + childRuns);
        assertTrue(childRuns.stream().anyMatch(r -> approve.equals(r.get("nodeId"))),
                "the chosen child branch's node run must be in the trace");
    }

    // ─── Helpers (echo harness, mirrors DagResumeLivePathRuntimeTest) ──────────

    private HttpHeaders newCaller() {
        String username = "dag-nested-live-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local", "pwd-dag-nl-1234",
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
                VALUES (?, 'dag-nested-live-user', 'DEFAULT_SYSTEM_ORG', now(), now())
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
