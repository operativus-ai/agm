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
import com.operativus.agentmanager.core.model.workflow.PauseKind;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
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
 * Domain Responsibility: Closes the DAG-3c deferred scenario "LOOP-mid-iteration pause" — a HITL
 *   gate INSIDE a LOOP body pauses partway through the iterations, and the frontier carries the loop
 *   counter so resume continues the loop from where it stopped (it does NOT restart at iteration 0).
 *
 *   <p>Topology: {@code A → LOOP(max:2) ⇄ R(ROUTER-HITL) ; LOOP -(exit)-> C}. The loop body R is a
 *   HITL ROUTER, so the body pauses on EVERY iteration. The flow proved here is:
 *   run → pause(iteration 1) → resume → pause(iteration 2) → resume → exit + complete. The
 *   {@link com.operativus.agentmanager.core.model.workflow.DagFrontier#iteration()} snapshot is the
 *   thing under test: at the first pause it reads 1, at the second pause it reads 2, and each resume
 *   rehydrates it so the LOOP re-evaluates against the correct count instead of looping forever or
 *   exiting early. Driven directly against the executor (offline {@code ECHO} model), mirroring
 *   {@code DagResumeRuntimeTest}.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagLoopPauseResumeRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void hitlInsideLoopBody_pausesEachIteration_resumeContinuesFromTheLoopCounter() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-loop-pause-" + UUID.randomUUID();
        seedSession(session);

        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep loop = step(wf, 2, "max:2", "LOOP");
        WorkflowStep r = step(wf, 3, null, "ROUTER");       // loop body — HITL gate
        WorkflowStep c = step(wf, 4, seedEchoAgent("C (echo)"), "AGENT");
        // HITL selector: the choices map is irrelevant to the pause (HITL always pends); R's only
        // real wiring is the back-edge to LOOP, so resume re-loops regardless of the injected port.
        r.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null, Map.of("again", c.getId()), null));
        List<WorkflowStep> nodes = List.of(a, loop, r, c);
        List<WorkflowEdge> edges = List.of(
                edge(wf, a.getId(), loop.getId(), null),
                edge(wf, loop.getId(), r.getId(), "loop"),
                edge(wf, r.getId(), loop.getId(), "back"),
                edge(wf, loop.getId(), c.getId(), "exit"));

        WorkflowRun run = run(wf, session);

        // 1. First run pauses at the loop body on iteration 1 (the LOOP entered its body once).
        DagResult firstPause = dag.run(run, nodes, edges, "kickoff", () -> false, NodeEventSink.NOOP);
        assertTrue(firstPause.output().paused(), "HITL loop body must pause the run");
        assertEquals(PauseKind.ROUTE, firstPause.output().pauseKind());
        assertNotNull(firstPause.frontier(), "a pause must snapshot a frontier");
        assertEquals(Integer.valueOf(1), firstPause.frontier().iteration().get(loop.getId()),
                "frontier must carry the mid-iteration loop counter (=1)");
        assertEquals(List.of(r.getId()), firstPause.frontier().pausedNodeIds());
        assertEquals(0, attemptsFor(run.getId(), c.getId()), "exit continuation has not run yet");

        // 2. Resume the first pause → the LOOP re-evaluates (1 < 2) and runs the body AGAIN → pauses
        //    a second time, this time at iteration 2. The counter advanced, it did not reset.
        DagResult secondPause = dag.resume(run, nodes, edges, firstPause.frontier(),
                settledBody(r.getId()), () -> false, NodeEventSink.NOOP);
        assertTrue(secondPause.output().paused(), "second iteration's HITL body must pause again");
        assertEquals(Integer.valueOf(2), secondPause.frontier().iteration().get(loop.getId()),
                "loop counter must advance to 2 across the resume, not restart at 0");
        assertEquals(0, attemptsFor(run.getId(), c.getId()), "still looping — exit not taken");

        // 3. Resume the second pause → the LOOP hits its bound (2 == max) → activates the exit port →
        //    the continuation runs and the run completes.
        DagResult done = dag.resume(run, nodes, edges, secondPause.frontier(),
                settledBody(r.getId()), () -> false, NodeEventSink.NOOP);
        String content = done.output().contentText();
        assertTrue(done.output().success(), "run should complete once the loop bound is hit: " + content);
        assertNull(done.frontier(), "a completed run carries no frontier");
        assertTrue(content.contains("[C (echo)]"), "exit continuation must run on the final resume: " + content);
        assertEquals(1, attemptsFor(run.getId(), c.getId()), "exit continuation runs exactly once");
    }

    /** The operator-settled output for the paused loop-body node — a plain success re-arms the back-edge. */
    private static StepOutput settledBody(String nodeId) {
        return StepOutput.success(nodeId, "R", NodeKind.ROUTER, "ROUTER",
                "resumed", List.of(), Instant.now(), Instant.now(), null, null);
    }

    // ─── Helpers (echo harness, mirrors DagResumeRuntimeTest) ──────────────────

    /** Latest attempt number persisted for a node, or 0 if it never ran. */
    private int attemptsFor(String runId, String nodeId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) FROM workflow_node_runs WHERE run_id = ? AND node_id = ?",
                Integer.class, runId, nodeId);
        return max == null ? 0 : max;
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

    private WorkflowStep step(String workflowId, int order, String agentIdOrExpr, String action) {
        return new WorkflowStep("step-" + UUID.randomUUID(), workflowId, order, agentIdOrExpr, action);
    }

    private WorkflowEdge edge(String workflowId, String fromStepId, String toStepId, String port) {
        return new WorkflowEdge("edge-" + UUID.randomUUID(), workflowId, fromStepId, toStepId, port);
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
                VALUES (?, 'dag-loop-pause-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
