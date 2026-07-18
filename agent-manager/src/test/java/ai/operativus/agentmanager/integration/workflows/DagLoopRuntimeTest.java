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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-5a — bounded LOOP in the {@link DagWorkflowExecutor} frontier
 *   scheduler. A LOOP node repeats a single body node via the loop/back edges until its {@code max}
 *   (or {@code until} condition) is hit, then activates the exit port. The back-edge is held out of
 *   the forward DAG so the cycle doesn't deadlock the in-degree model; each body/loop re-execution
 *   is a distinct {@code workflow_node_runs} row (incrementing attempt). Verified through the real
 *   agent path with the offline {@code ECHO} model (echo nesting makes the iteration count visible).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagLoopRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void loopMax2_runsBodyTwiceThenExits() {
        // A → LOOP(max:2) ⇄ B (loop/back) ; LOOP -(exit)-> C
        StepOutput out = runLoop("max:2", "go");
        String content = out.contentText();
        assertTrue(content.contains("[C (echo)]"), "exit continuation must run: " + content);
        assertTrue(content.contains("[A (echo)]"), "original input threaded: " + content);
        assertEquals(2, count(content, "[B (echo)]"), "body runs exactly twice: " + content);
    }

    @Test
    void loopMax1_runsBodyOnce() {
        String content = runLoop("max:1", "go").contentText();
        assertTrue(content.contains("[C (echo)]"), "exit continuation must run: " + content);
        assertEquals(1, count(content, "[B (echo)]"), "body runs exactly once: " + content);
    }

    private StepOutput runLoop(String loopConfig, String input) {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-loop-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep loop = step(wf, 2, loopConfig, "LOOP");
        WorkflowStep b = step(wf, 3, seedEchoAgent("B (echo)"), "AGENT");
        WorkflowStep c = step(wf, 4, seedEchoAgent("C (echo)"), "AGENT");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, loop, b, c),
                List.of(
                        edge(wf, a.getId(), loop.getId(), null),
                        edge(wf, loop.getId(), b.getId(), "loop"),
                        edge(wf, b.getId(), loop.getId(), "back"),
                        edge(wf, loop.getId(), c.getId(), "exit")),
                input, () -> false);

        // Trace rows: A(1) + LOOP(iterations+1) + B(iterations) + C(1). Just assert the body ran
        // and the run produced a coherent set of node-run rows (no unique-constraint blowup).
        assertTrue(nodeRuns.countByRunId(run.getId()) >= 4, "node runs persisted");
        return out;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        while (m.find()) n++;
        return n;
    }

    // ─── Helpers (echo harness) ───────────────────────────────────────────────

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
                VALUES (?, 'dag-loop-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
