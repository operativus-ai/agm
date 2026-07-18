package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-6 — the structural-gate node executors
 *   ({@code ParallelNodeExecutor}, {@code JoinNodeExecutor}). A PARALLEL node fans out to every
 *   successor (the scheduler runs them on virtual threads); a JOIN node is held by the AND-join
 *   until all predecessors finish and threads their merged content forward. Both are pass-through
 *   gates — before this they had no registered executor and failed fast. Verified through the real
 *   agent path with the offline {@code ECHO} model.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagParallelJoinRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void parallelFansOut_joinFansIn_threadsBothBranchesThrough() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-pj-" + UUID.randomUUID();
        seedSession(session);
        // P(PARALLEL) → {B,C}(AGENT echo) → J(JOIN). P fans out; J waits for both then merges.
        WorkflowStep p = step(wf, 1, null, "PARALLEL");
        WorkflowStep b = step(wf, 2, seedEchoAgent("B (echo)"), "AGENT");
        WorkflowStep c = step(wf, 2, seedEchoAgent("C (echo)"), "AGENT");
        WorkflowStep j = step(wf, 3, null, "JOIN");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(p, b, c, j),
                List.of(
                        edge(wf, p.getId(), b.getId()),
                        edge(wf, p.getId(), c.getId()),
                        edge(wf, b.getId(), j.getId()),
                        edge(wf, c.getId(), j.getId())),
                "kickoff", () -> false);

        String content = out.contentText();
        assertTrue(out.success(), "JOIN terminal should succeed: " + content);
        // The PARALLEL gate activated BOTH branches; the JOIN merged both into its (terminal) output.
        assertTrue(content.contains("[B (echo)]"), "PARALLEL did not fan out to B: " + content);
        assertTrue(content.contains("[C (echo)]"), "PARALLEL did not fan out to C: " + content);
        assertTrue(content.contains("---"), "JOIN did not merge both predecessor outputs: " + content);
        assertEquals(4, nodeRuns.countByRunId(run.getId()), "P, B, C, J each persist one node run");
    }

    // ─── Helpers (echo harness, mirrors DagRouterRuntimeTest) ──────────────────

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
                VALUES (?, 'dag-pj-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
