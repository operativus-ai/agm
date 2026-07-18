package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import com.operativus.agentmanager.core.entity.WorkflowEdge;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves the DAG frontier scheduler ({@link DagWorkflowExecutor}, DAG-3a)
 *   walks a workflow's node graph along its explicit {@link WorkflowEdge} topology — real fan-out
 *   on virtual threads and join-counting fan-in — end-to-end through the REAL agent path, using the
 *   OFFLINE {@code provider='ECHO'} model (echoes "[&lt;agent name&gt;] &lt;input&gt;") so the threaded
 *   per-node payload is directly assertable. Drives the executor DIRECTLY (DAG-3a is off the live
 *   dispatch path); the gated job-handler delegation is DAG-3b.
 *
 *   <p>NO mocked agent operations and NO LLM key — agent steps execute for real and persist
 *   {@code workflow_node_runs} rows. Mirrors the {@code DemoEchoWorkflowRuntimeTest} echo harness.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagWorkflowExecutorRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    // ─── linear chain A → B → C ──────────────────────────────────────────────
    @Test
    void linearChain_threadsEchoThroughEachNode() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-dag-linear";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"));
        WorkflowStep b = step(wf, 2, seedEchoAgent("B (echo)"));
        WorkflowStep c = step(wf, 3, seedEchoAgent("C (echo)"));

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, b, c),
                List.of(edge(wf, a.getId(), b.getId()), edge(wf, b.getId(), c.getId())),
                "draft the release post", () -> false);

        String content = out.contentText();
        assertTrue(content.contains("[A (echo)]"), "A echo missing: " + content);
        assertTrue(content.contains("[B (echo)]"), "B echo missing: " + content);
        assertTrue(content.contains("[C (echo)]"), "C echo missing: " + content);
        assertTrue(content.contains("draft the release post"), "input not threaded: " + content);
        assertTrue(out.success(), "terminal node should succeed");
        assertEquals(3, nodeRuns.countByRunId(run.getId()), "one workflow_node_runs row per node");
    }

    // ─── per-node lifecycle events emitted to the sink (live viewer feed) ─────
    @Test
    void emitsNodeLifecycleEvents_startedThenCompleted_inOrder() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-dag-events";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"));
        WorkflowStep b = step(wf, 2, seedEchoAgent("B (echo)"));
        WorkflowStep c = step(wf, 3, seedEchoAgent("C (echo)"));

        List<String> events = Collections.synchronizedList(new ArrayList<>());
        WorkflowRun run = run(wf, session);
        dag.execute(run,
                List.of(a, b, c),
                List.of(edge(wf, a.getId(), b.getId()), edge(wf, b.getId(), c.getId())),
                "go", () -> false,
                (phase, rid, wfId, nodeId, nodeName, kind) -> events.add(phase + ":" + nodeId));

        // Linear chain runs sequentially on the scheduler thread → deterministic order.
        assertEquals(List.of(
                "STARTED:" + a.getId(), "COMPLETED:" + a.getId(),
                "STARTED:" + b.getId(), "COMPLETED:" + b.getId(),
                "STARTED:" + c.getId(), "COMPLETED:" + c.getId()), events);
    }

    // ─── diamond A → {B,C} → D (real fan-out + join-counting fan-in) ──────────
    @Test
    void diamond_fanOutThenJoin_dSeesBothBranchOutputs() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-dag-diamond";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"));
        WorkflowStep b = step(wf, 2, seedEchoAgent("B (echo)"));
        WorkflowStep c = step(wf, 2, seedEchoAgent("C (echo)"));
        WorkflowStep d = step(wf, 3, seedEchoAgent("D (echo)"));

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, b, c, d),
                List.of(
                        edge(wf, a.getId(), b.getId()),
                        edge(wf, a.getId(), c.getId()),
                        edge(wf, b.getId(), d.getId()),
                        edge(wf, c.getId(), d.getId())),
                "assess vendor X", () -> false);

        String content = out.contentText();
        // D is the single terminal; its echo wraps the joined B and C outputs (real fan-in).
        assertTrue(content.contains("[D (echo)]"), "terminal D echo missing: " + content);
        assertTrue(content.contains("[B (echo)]"), "fan-in missing B branch: " + content);
        assertTrue(content.contains("[C (echo)]"), "fan-in missing C branch: " + content);
        assertTrue(content.contains("---"), "fan-in join separator missing: " + content);
        assertEquals(4, nodeRuns.countByRunId(run.getId()), "A,B,C,D each persist one node run");
    }

    // ─── two disjoint branches both execute (independent fan-out) ─────────────
    @Test
    void independentBranches_bothExecuteAndAppearInAggregateTerminal() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-dag-parallel";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"));
        WorkflowStep b = step(wf, 2, seedEchoAgent("B (echo)"));
        WorkflowStep c = step(wf, 1, seedEchoAgent("C (echo)"));
        WorkflowStep d = step(wf, 2, seedEchoAgent("D (echo)"));

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, b, c, d),
                List.of(edge(wf, a.getId(), b.getId()), edge(wf, c.getId(), d.getId())),
                "go", () -> false);

        // Two terminals (B and D) are aggregated; both independent chains must have run.
        String content = out.contentText();
        assertTrue(content.contains("[B (echo)]"), "branch A→B missing: " + content);
        assertTrue(content.contains("[D (echo)]"), "branch C→D missing: " + content);
        assertEquals(4, nodeRuns.countByRunId(run.getId()), "all four nodes execute");
    }

    // ─── Helpers (echo harness, mirrors DemoEchoWorkflowRuntimeTest) ──────────

    private WorkflowRun run(String workflowId, String sessionId) {
        WorkflowRun r = new WorkflowRun();
        r.setId("wfrun-" + UUID.randomUUID());
        r.setWorkflowId(workflowId);
        r.setSessionId(sessionId);
        r.setOrgId("DEFAULT_SYSTEM_ORG");
        r.setStatus(RunStatus.RUNNING);
        return r;
    }

    private WorkflowStep step(String workflowId, int order, String agentId) {
        return new WorkflowStep("step-" + UUID.randomUUID(), workflowId, order, agentId, "AGENT");
    }

    private WorkflowEdge edge(String workflowId, String fromStepId, String toStepId) {
        return new WorkflowEdge("edge-" + UUID.randomUUID(), workflowId, fromStepId, toStepId, null);
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
                VALUES (?, 'dag-wf-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
