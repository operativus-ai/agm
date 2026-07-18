package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.DagResult;
import com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor.NodeEventSink;
import com.operativus.agentmanager.core.entity.WorkflowEdge;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.workflow.NestedPause;
import com.operativus.agentmanager.core.model.workflow.PauseKind;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves nested-frontier resume (DAG-6 follow-up) — a HITL pause INSIDE a
 *   child sub-workflow no longer fails the WORKFLOW node: it bubbles up as a parent pause carrying
 *   {@link NestedPause} (child run id + child frontier), and settling the parent routes the operator
 *   output back INTO the child via {@code SubWorkflowNodeExecutor.resumeNested}, which retargets it
 *   onto the child's paused node and re-enters the child's exact graph. Also proves the re-pause
 *   cycle: a child with TWO sequential HITL gates keeps the parent paused (with refreshed nested
 *   state under the SAME child run id) until both are settled.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagNestedResumeRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;

    @Test
    void childRouterHitl_bubblesAsParentPause_resumeRoutesIntoChild_andCompletes() {
        seedEchoModel();
        String childWf = seedWorkflow();
        String x = insertAgentStep(childWf, 2, seedEchoAgent("X (echo)"));
        String y = insertAgentStep(childWf, 2, seedEchoAgent("Y (echo)"));
        String r = insertRouterHitlStep(childWf, 1, Map.of("x", x, "y", y));
        insertEdge(childWf, r, x, "x");
        insertEdge(childWf, r, y, "y");

        String session = "sess-nested-" + UUID.randomUUID();
        seedSession(session);
        String parentWf = "wf-" + UUID.randomUUID();
        WorkflowStep w = step(parentWf, 1, childWf, "WORKFLOW");
        WorkflowStep p = step(parentWf, 2, seedEchoAgent("P (echo)"), "AGENT");
        List<WorkflowStep> nodes = List.of(w, p);
        List<WorkflowEdge> edges = List.of(edge(parentWf, w.getId(), p.getId(), null));

        WorkflowRun run = run(parentWf, session);
        DagResult paused = dag.run(run, nodes, edges, "kickoff", () -> false, NodeEventSink.NOOP);

        // 1. The child's ROUTE pause bubbled up as a parent pause with nested resume state.
        assertTrue(paused.output().paused(), "child HITL must bubble up as a parent pause, not a failure");
        assertEquals(PauseKind.ROUTE, paused.output().pauseKind(), "the child's pause kind bubbles up");
        assertEquals(List.of(w.getId()), paused.frontier().pausedNodeIds());
        NestedPause nested = paused.frontier().nestedPauses().get(w.getId());
        assertNotNull(nested, "the parent frontier must carry the child's nested resume state");
        assertEquals(childWf, nested.childWorkflowId());
        assertEquals(List.of(r), nested.childFrontier().pausedNodeIds(), "child frontier names its paused ROUTER");
        assertEquals(1, childRowCount(nested.childRunId()), "only the child ROUTER ran before the pause");

        // 2. Settle against the PARENT node id (as the control layer does); the executor retargets
        //    onto the child's paused ROUTER and activates its "x" port.
        StepOutput settled = StepOutput.branched(w.getId(), "W", NodeKind.ROUTER, "ROUTER",
                "operator-pick", List.of("x"), List.of(), Instant.now(), Instant.now());
        DagResult done = dag.resume(run, nodes, edges, paused.frontier(), settled, () -> false, NodeEventSink.NOOP);

        String content = done.output().contentText();
        assertTrue(done.output().success(), "resumed run should complete: " + content);
        assertNull(done.frontier());
        assertTrue(content.contains("[X (echo)]"), "chosen child branch missing: " + content);
        assertTrue(content.contains("[P (echo)]"), "parent successor missing: " + content);
        assertEquals(0, nodeRowCount(nested.childRunId(), y), "non-chosen child branch must be pruned");
        // Child rows accumulated under the SAME stable child run id across the resume.
        assertTrue(childRowCount(nested.childRunId()) >= 2, "child trace accumulates under the stable child run id");
    }

    @Test
    void childWithTwoSequentialHitlGates_parentStaysPausedAcrossFirstSettle() {
        seedEchoModel();
        String childWf = seedWorkflow();
        String z = insertAgentStep(childWf, 3, seedEchoAgent("Z (echo)"));
        String r2 = insertRouterHitlStep(childWf, 2, Map.of("done", z));
        String r1 = insertRouterHitlStep(childWf, 1, Map.of("go", r2));
        insertEdge(childWf, r1, r2, "go");
        insertEdge(childWf, r2, z, "done");

        String session = "sess-nested2-" + UUID.randomUUID();
        seedSession(session);
        String parentWf = "wf-" + UUID.randomUUID();
        WorkflowStep w = step(parentWf, 1, childWf, "WORKFLOW");
        WorkflowStep p = step(parentWf, 2, seedEchoAgent("P (echo)"), "AGENT");
        List<WorkflowStep> nodes = List.of(w, p);
        List<WorkflowEdge> edges = List.of(edge(parentWf, w.getId(), p.getId(), null));

        WorkflowRun run = run(parentWf, session);
        DagResult firstPause = dag.run(run, nodes, edges, "kickoff", () -> false, NodeEventSink.NOOP);
        assertTrue(firstPause.output().paused());
        String childRunId = firstPause.frontier().nestedPauses().get(w.getId()).childRunId();
        assertEquals(List.of(r1), firstPause.frontier().nestedPauses().get(w.getId())
                .childFrontier().pausedNodeIds(), "first pause is at the child's first gate");

        // Settle gate 1 → the child advances to gate 2 and pauses again → the PARENT stays paused
        // with refreshed nested state under the SAME child run id.
        StepOutput settle1 = StepOutput.branched(w.getId(), "W", NodeKind.ROUTER, "ROUTER",
                "first", List.of("go"), List.of(), Instant.now(), Instant.now());
        DagResult secondPause = dag.resume(run, nodes, edges, firstPause.frontier(), settle1,
                () -> false, NodeEventSink.NOOP);
        assertTrue(secondPause.output().paused(), "parent must stay paused while the child has unsettled gates");
        NestedPause refreshed = secondPause.frontier().nestedPauses().get(w.getId());
        assertNotNull(refreshed, "re-pause must refresh the nested state");
        assertEquals(childRunId, refreshed.childRunId(), "child run id is stable across pause cycles");
        assertEquals(List.of(r2), refreshed.childFrontier().pausedNodeIds(), "now paused at the child's second gate");
        assertEquals(PauseKind.ROUTE, secondPause.output().pauseKind(), "re-pause keeps the child's pause kind");

        // Settle gate 2 → child completes → parent successor runs → run completes.
        StepOutput settle2 = StepOutput.branched(w.getId(), "W", NodeKind.ROUTER, "ROUTER",
                "second", List.of("done"), List.of(), Instant.now(), Instant.now());
        DagResult done = dag.resume(run, nodes, edges, secondPause.frontier(), settle2,
                () -> false, NodeEventSink.NOOP);
        String content = done.output().contentText();
        assertTrue(done.output().success(), "run completes once both child gates settle: " + content);
        assertTrue(content.contains("[Z (echo)]"), "child terminal output missing: " + content);
        assertTrue(content.contains("[P (echo)]"), "parent successor missing: " + content);
    }

    // ─── Helpers (echo harness, child graph persisted — the executor reloads it) ──

    private int childRowCount(String childRunId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ?",
                Integer.class, childRunId);
        return n == null ? 0 : n;
    }

    private int nodeRowCount(String runId, String nodeId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_runs WHERE run_id = ? AND node_id = ?",
                Integer.class, runId, nodeId);
        return n == null ? 0 : n;
    }

    private WorkflowRun run(String workflowId, String sessionId) {
        WorkflowRun r = new WorkflowRun();
        r.setId("wfrun-" + UUID.randomUUID());
        r.setWorkflowId(workflowId);
        r.setSessionId(sessionId);
        r.setOrgId("DEFAULT_SYSTEM_ORG");
        r.setStatus(RunStatus.RUNNING);
        return r;
    }

    private WorkflowStep step(String workflowId, int order, String agentId, String action) {
        return new WorkflowStep("step-" + UUID.randomUUID(), workflowId, order, agentId, action);
    }

    private WorkflowEdge edge(String workflowId, String fromStepId, String toStepId, String port) {
        return new WorkflowEdge("edge-" + UUID.randomUUID(), workflowId, fromStepId, toStepId, port);
    }

    private String seedWorkflow() {
        String id = "wf-child-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflows (id, name, org_id, created_at, updated_at)
                VALUES (?, 'nested resume child', 'DEFAULT_SYSTEM_ORG', now(), now())
                """, id);
        return id;
    }

    private String insertAgentStep(String wf, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'AGENT')", id, wf, order, agentId);
        return id;
    }

    private String insertRouterHitlStep(String wf, int order, Map<String, String> choices) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, NULL, 'ROUTER')", id, wf, order);
        StringBuilder choicesJson = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, String> e : choices.entrySet()) {
            if (i++ > 0) choicesJson.append(",");
            choicesJson.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        choicesJson.append("}");
        String json = "{\"selectorType\":\"HITL\",\"selectorExpression\":null,\"choices\":"
                + choicesJson + ",\"defaultChoice\":null}";
        jdbc.update("UPDATE workflow_steps SET router_config = ?::jsonb WHERE id = ?", json, id);
        return id;
    }

    private void insertEdge(String wf, String from, String to, String port) {
        jdbc.update("INSERT INTO workflow_edges (id, workflow_id, from_step_id, to_step_id, condition, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", "edge-" + UUID.randomUUID(), wf, from, to, port);
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

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'dag-nested-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
