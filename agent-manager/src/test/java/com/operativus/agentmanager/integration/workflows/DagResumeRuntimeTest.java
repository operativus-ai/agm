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
import com.operativus.agentmanager.core.model.workflow.DagFrontier;
import com.operativus.agentmanager.core.model.workflow.PauseKind;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-3c frontier-aware resume on the {@link DagWorkflowExecutor}.
 *   A run that pauses at a ROUTER HITL selector snapshots a compact {@link DagFrontier}; {@link
 *   DagWorkflowExecutor#resume} rehydrates completed-node outputs from {@code workflow_node_runs},
 *   injects the settled node's output, and re-enters the EXACT graph — running the remainder once,
 *   activating only the chosen branch, never re-executing already-completed nodes. Driven directly
 *   against the executor (mirrors {@code DagRouterRuntimeTest}) through the real agent path with the
 *   offline {@code ECHO} model, so the threaded per-node payload is directly assertable.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagResumeRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;
    @Autowired private WorkflowRunRepository workflowRuns;

    // ─── the DagFrontier JSONB column round-trips through Hibernate ────────────
    @Test
    void dagFrontier_jsonbColumn_roundTripsThroughHibernate() {
        String session = "sess-jsonb-" + UUID.randomUUID();
        seedSession(session);
        String wfId = "wf-jsonb-" + UUID.randomUUID();
        seedWorkflow(wfId);
        WorkflowRun run = run(wfId, session);
        run.setCurrentStepOrder(0); // NOT NULL column — the executor-driven tests never persist the run
        DagFrontier childFrontier = new DagFrontier(
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of("child-paused-node"), PauseKind.ROUTE, Map.of());
        DagFrontier frontier = new DagFrontier(
                List.of("node-pending-1", "node-pending-2"),
                Map.of("node-join", 1),
                Map.of("node-join", 0),
                Map.of("loop-1", 3),
                Map.of("node-a", 2),
                Map.of("loop-1", "carry-forward-text"),
                List.of("node-paused"),
                PauseKind.ROUTE,
                Map.of("node-paused", new com.operativus.agentmanager.core.model.workflow.NestedPause(
                        "child-run-id", "child-wf-id", childFrontier)));
        run.setStatus(RunStatus.AWAITING_ROUTE_SELECTION);
        run.setDagFrontier(frontier);
        workflowRuns.saveAndFlush(run);

        WorkflowRun reloaded = workflowRuns.findById(run.getId()).orElseThrow();
        DagFrontier back = reloaded.getDagFrontier();
        assertNotNull(back, "dag_frontier must survive the JSONB round-trip");
        assertEquals(List.of("node-pending-1", "node-pending-2"), back.pendingNodeIds());
        assertEquals(Integer.valueOf(1), back.remaining().get("node-join"));
        assertEquals(Integer.valueOf(3), back.iteration().get("loop-1"));
        assertEquals("carry-forward-text", back.loopInput().get("loop-1"));
        assertEquals(List.of("node-paused"), back.pausedNodeIds());
        assertEquals(PauseKind.ROUTE, back.pauseKind());
        // The recursive nested-pause structure survives the JSONB round-trip too.
        com.operativus.agentmanager.core.model.workflow.NestedPause backNested =
                back.nestedPauses().get("node-paused");
        assertNotNull(backNested, "nestedPauses must survive the JSONB round-trip");
        assertEquals("child-run-id", backNested.childRunId());
        assertEquals("child-wf-id", backNested.childWorkflowId());
        assertEquals(List.of("child-paused-node"), backNested.childFrontier().pausedNodeIds());
    }

    // ─── ROUTER HITL pause → resume runs ONLY the chosen branch, exactly once ──
    @Test
    void routerHitlPause_resumeWithChoice_runsOnlyChosenBranch_andCompletes() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-resume-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep router = step(wf, 1, null, "ROUTER");
        WorkflowStep approve = step(wf, 2, seedEchoAgent("Approve (echo)"), "AGENT");
        WorkflowStep reject = step(wf, 2, seedEchoAgent("Reject (echo)"), "AGENT");
        router.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null,
                Map.of("approve", approve.getId(), "reject", reject.getId()), null));
        List<WorkflowStep> nodes = List.of(router, approve, reject);
        List<WorkflowEdge> edges = List.of(
                edge(wf, router.getId(), approve.getId(), "approve"),
                edge(wf, router.getId(), reject.getId(), "reject"));

        WorkflowRun run = run(wf, session);
        DagResult paused = dag.run(run, nodes, edges, "{}", () -> false, NodeEventSink.NOOP);

        // 1. Run paused at the ROUTER; a frontier was captured naming the paused node + kind.
        assertTrue(paused.output().paused(), "ROUTER HITL must pause the run");
        assertEquals(PauseKind.ROUTE, paused.output().pauseKind());
        assertNotNull(paused.frontier(), "a pause must produce a frontier snapshot");
        assertEquals(List.of(router.getId()), paused.frontier().pausedNodeIds());
        assertEquals(1, nodeRuns.countByRunId(run.getId()), "only the ROUTER ran before the pause");

        // 2. Resume injecting the operator's choice (branch activates the "approve" port only).
        StepOutput settled = StepOutput.branched(router.getId(), "ROUTER", NodeKind.ROUTER, "ROUTER",
                "approved-input", List.of("approve"), List.of(), Instant.now(), Instant.now());
        DagResult done = dag.resume(run, nodes, edges, paused.frontier(), settled, () -> false, NodeEventSink.NOOP);

        String content = done.output().contentText();
        assertTrue(done.output().success(), "resumed run should complete: " + content);
        assertNull(done.frontier());
        assertTrue(content.contains("[Approve (echo)]"), "chosen branch missing: " + content);
        assertFalse(content.contains("[Reject (echo)]"), "non-chosen branch ran: " + content);
        // approve ran exactly once; reject never ran; ROUTER has one extra (injected) row.
        assertEquals(1, attemptsFor(run.getId(), approve.getId()), "approve must run exactly once");
        assertEquals(0, attemptsFor(run.getId(), reject.getId()), "reject branch must be pruned");
    }

    // ─── in-flight sibling drains to a safe point; resume completes the rest ───
    @Test
    void pauseWithInFlightSibling_siblingDrainsAndPersists_resumeCompletes() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-drain-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep router = step(wf, 2, null, "ROUTER");
        WorkflowStep sibling = step(wf, 2, seedEchoAgent("C-sibling (echo)"), "AGENT");
        WorkflowStep approve = step(wf, 3, seedEchoAgent("E-approve (echo)"), "AGENT");
        router.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null,
                Map.of("go", approve.getId()), null));
        List<WorkflowStep> nodes = List.of(a, router, sibling, approve);
        // A fans out to the ROUTER (which will pause) and to an independent sibling that runs to a leaf.
        List<WorkflowEdge> edges = List.of(
                edge(wf, a.getId(), router.getId(), null),
                edge(wf, a.getId(), sibling.getId(), null),
                edge(wf, router.getId(), approve.getId(), "go"));

        WorkflowRun run = run(wf, session);
        DagResult paused = dag.run(run, nodes, edges, "kickoff", () -> false, NodeEventSink.NOOP);

        assertTrue(paused.output().paused(), "ROUTER HITL must pause the run");
        // The in-flight sibling drained to a safe point: it finished and persisted its row, but the
        // paused branch's successor (approve) was NOT enqueued.
        assertEquals(1, attemptsFor(run.getId(), a.getId()), "A ran once");
        assertEquals(1, attemptsFor(run.getId(), sibling.getId()), "in-flight sibling drained + persisted");
        assertEquals(0, attemptsFor(run.getId(), approve.getId()), "paused branch successor not enqueued");

        StepOutput settled = StepOutput.branched(router.getId(), "ROUTER", NodeKind.ROUTER, "ROUTER",
                "go-input", List.of("go"), List.of(), Instant.now(), Instant.now());
        DagResult done = dag.resume(run, nodes, edges, paused.frontier(), settled, () -> false, NodeEventSink.NOOP);

        assertTrue(done.output().success(), "resume should complete: " + done.output().contentText());
        assertEquals(1, attemptsFor(run.getId(), approve.getId()), "approve runs once on resume");
        // Completed nodes from before the pause are NOT re-executed — rehydrated from the trace.
        assertEquals(1, attemptsFor(run.getId(), a.getId()), "A must not re-run on resume");
        assertEquals(1, attemptsFor(run.getId(), sibling.getId()), "sibling must not re-run on resume");
    }

    // ─── two independent branches pause: settle one, run stays paused; settle both → done ──
    @Test
    void concurrentDoublePause_settledOneByOne_runStaysPausedUntilAllSettled() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-double-" + UUID.randomUUID();
        seedSession(session);
        // Two independent ROUTER-HITL branches with no shared predecessor — both pause in one run.
        WorkflowStep r1 = step(wf, 1, null, "ROUTER");
        WorkflowStep a1 = step(wf, 2, seedEchoAgent("A1 (echo)"), "AGENT");
        WorkflowStep r2 = step(wf, 1, null, "ROUTER");
        WorkflowStep a2 = step(wf, 2, seedEchoAgent("A2 (echo)"), "AGENT");
        r1.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null, Map.of("ok", a1.getId()), null));
        r2.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null, Map.of("ok", a2.getId()), null));
        List<WorkflowStep> nodes = List.of(r1, a1, r2, a2);
        List<WorkflowEdge> edges = List.of(
                edge(wf, r1.getId(), a1.getId(), "ok"),
                edge(wf, r2.getId(), a2.getId(), "ok"));

        WorkflowRun run = run(wf, session);
        DagResult paused = dag.run(run, nodes, edges, "{}", () -> false, NodeEventSink.NOOP);

        assertTrue(paused.output().paused(), "both ROUTERs HITL → run pauses");
        assertEquals(2, paused.frontier().pausedNodeIds().size(), "both paused nodes recorded");
        assertTrue(paused.frontier().pausedNodeIds().containsAll(List.of(r1.getId(), r2.getId())));

        // Settle R1 → its branch runs, but R2 is still paused → the run STAYS paused.
        StepOutput settle1 = StepOutput.branched(r1.getId(), "R1", NodeKind.ROUTER, "ROUTER",
                "x", List.of("ok"), List.of(), Instant.now(), Instant.now());
        DagResult afterFirst = dag.resume(run, nodes, edges, paused.frontier(), settle1, () -> false, NodeEventSink.NOOP);
        assertTrue(afterFirst.output().paused(), "run must stay paused until the second pause is settled");
        assertEquals(List.of(r2.getId()), afterFirst.frontier().pausedNodeIds(), "R2 carried forward as still-paused");
        assertEquals(1, attemptsFor(run.getId(), a1.getId()), "settled branch A1 ran once");
        assertEquals(0, attemptsFor(run.getId(), a2.getId()), "unsettled branch A2 has not run");

        // Settle R2 (using the carried frontier) → completes.
        StepOutput settle2 = StepOutput.branched(r2.getId(), "R2", NodeKind.ROUTER, "ROUTER",
                "y", List.of("ok"), List.of(), Instant.now(), Instant.now());
        DagResult done = dag.resume(run, nodes, edges, afterFirst.frontier(), settle2, () -> false, NodeEventSink.NOOP);
        assertTrue(done.output().success(), "run completes once all pauses are settled");
        assertEquals(1, attemptsFor(run.getId(), a2.getId()), "A2 runs once on the second resume");
        assertEquals(1, attemptsFor(run.getId(), a1.getId()), "A1 must not re-run");
    }

    // ─── cancel committed while paused: a late resume must not clobber CANCELLED ──
    @Test
    void resumeWhileCancelled_returnsCancelled_andDoesNotAdvance() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-cancel-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep router = step(wf, 1, null, "ROUTER");
        WorkflowStep approve = step(wf, 2, seedEchoAgent("Approve (echo)"), "AGENT");
        router.setRouterConfig(new RouterStepConfig(RouteSelectorType.HITL, null,
                Map.of("approve", approve.getId()), null));
        List<WorkflowStep> nodes = List.of(router, approve);
        List<WorkflowEdge> edges = List.of(edge(wf, router.getId(), approve.getId(), "approve"));

        WorkflowRun run = run(wf, session);
        DagResult paused = dag.run(run, nodes, edges, "{}", () -> false, NodeEventSink.NOOP);
        assertTrue(paused.output().paused());

        StepOutput settled = StepOutput.branched(router.getId(), "ROUTER", NodeKind.ROUTER, "ROUTER",
                "x", List.of("approve"), List.of(), Instant.now(), Instant.now());
        // Cancel is already committed (poll returns true) — resume must bail before running the branch.
        DagResult cancelled = dag.resume(run, nodes, edges, paused.frontier(), settled, () -> true, NodeEventSink.NOOP);

        assertFalse(cancelled.output().success(), "cancelled resume must not report success");
        assertEquals(0, attemptsFor(run.getId(), approve.getId()), "approve must NOT run when cancelled");
    }

    // ─── Helpers (echo harness, mirrors DagRouterRuntimeTest) ──────────────────

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

    private WorkflowStep step(String workflowId, int order, String agentId, String action) {
        return new WorkflowStep("step-" + UUID.randomUUID(), workflowId, order, agentId, action);
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

    private void seedWorkflow(String workflowId) {
        jdbc.update("""
                INSERT INTO workflows (id, name, org_id, created_at, updated_at)
                VALUES (?, 'JSONB round-trip', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, workflowId);
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'dag-resume-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
