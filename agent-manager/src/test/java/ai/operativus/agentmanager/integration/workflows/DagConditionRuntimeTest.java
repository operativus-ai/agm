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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-4a — CONDITION port selection in the {@link DagWorkflowExecutor}
 *   frontier scheduler. A CONDITION node evaluates its predicate against the incoming content and
 *   the scheduler activates ONLY the matching outgoing port ({@code true} / {@code false}) instead
 *   of every edge, so exactly one exclusive branch runs. Verified through the REAL agent path with
 *   the offline {@code ECHO} model (no mocks, no LLM key) so the threaded payload is assertable.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagConditionRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void conditionTrue_activatesOnlyTrueBranch() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-cond-true";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep cond = step(wf, 2, "contains:over", "CONDITION");
        WorkflowStep t = step(wf, 3, seedEchoAgent("T (echo)"), "AGENT");
        WorkflowStep f = step(wf, 3, seedEchoAgent("F (echo)"), "AGENT");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, cond, t, f),
                List.of(
                        edge(wf, a.getId(), cond.getId(), null),
                        edge(wf, cond.getId(), t.getId(), "true"),
                        edge(wf, cond.getId(), f.getId(), "false")),
                "EXP over the limit", () -> false);

        String content = out.contentText();
        assertTrue(content.contains("[T (echo)]"), "true branch must run: " + content);
        assertFalse(content.contains("[F (echo)]"), "false branch must be skipped: " + content);
        // A + COND + T ran; F was never scheduled.
        assertEquals(3, nodeRuns.countByRunId(run.getId()), "only the taken branch executes");
    }

    @Test
    void conditionFalse_activatesOnlyFalseBranch() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-cond-false";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep cond = step(wf, 2, "contains:over", "CONDITION");
        WorkflowStep t = step(wf, 3, seedEchoAgent("T (echo)"), "AGENT");
        WorkflowStep f = step(wf, 3, seedEchoAgent("F (echo)"), "AGENT");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, cond, t, f),
                List.of(
                        edge(wf, a.getId(), cond.getId(), null),
                        edge(wf, cond.getId(), t.getId(), "true"),
                        edge(wf, cond.getId(), f.getId(), "false")),
                "small amount, within policy", () -> false);

        String content = out.contentText();
        assertTrue(content.contains("[F (echo)]"), "false branch must run: " + content);
        assertFalse(content.contains("[T (echo)]"), "true branch must be skipped: " + content);
        assertEquals(3, nodeRuns.countByRunId(run.getId()), "only the taken branch executes");
    }

    // ─── jsonpath: grammar now works on the DAG path (was silently always-true) ──
    @Test
    void jsonpathTruthy_activatesTrueBranch_andFalsy_activatesFalseBranch() {
        seedEchoModel();
        String wfT = "wf-" + UUID.randomUUID();
        seedSession("sess-jp-true");
        // CONDITION is the entry node so it reads the raw JSON input (an upstream echo agent would
        // prefix it and break JSON parsing). jsonpath:$.approved resolves against the input doc.
        WorkflowStep condT = step(wfT, 1, "jsonpath:$.approved", "CONDITION");
        WorkflowStep tT = step(wfT, 2, seedEchoAgent("T (echo)"), "AGENT");
        WorkflowStep fT = step(wfT, 2, seedEchoAgent("F (echo)"), "AGENT");
        StepOutput outT = dag.execute(run(wfT, "sess-jp-true"),
                List.of(condT, tT, fT),
                List.of(edge(wfT, condT.getId(), tT.getId(), "true"),
                        edge(wfT, condT.getId(), fT.getId(), "false")),
                "{\"approved\": true}", () -> false);
        assertTrue(outT.contentText().contains("[T (echo)]"),
                "truthy jsonpath must take the true branch: " + outT.contentText());
        assertFalse(outT.contentText().contains("[F (echo)]"),
                "truthy jsonpath must NOT take the false branch: " + outT.contentText());

        // Falsy resolution routes false — pre-fix the DAG path ignored jsonpath: and always
        // returned true, so this case would have wrongly run the true branch.
        String wfF = "wf-" + UUID.randomUUID();
        seedSession("sess-jp-false");
        WorkflowStep condF = step(wfF, 1, "jsonpath:$.approved", "CONDITION");
        WorkflowStep tF = step(wfF, 2, seedEchoAgent("T (echo)"), "AGENT");
        WorkflowStep fF = step(wfF, 2, seedEchoAgent("F (echo)"), "AGENT");
        StepOutput outF = dag.execute(run(wfF, "sess-jp-false"),
                List.of(condF, tF, fF),
                List.of(edge(wfF, condF.getId(), tF.getId(), "true"),
                        edge(wfF, condF.getId(), fF.getId(), "false")),
                "{\"approved\": false}", () -> false);
        assertTrue(outF.contentText().contains("[F (echo)]"),
                "falsy jsonpath must take the false branch: " + outF.contentText());
        assertFalse(outF.contentText().contains("[T (echo)]"),
                "falsy jsonpath must NOT take the true branch: " + outF.contentText());
    }

    // ─── DAG-4b: reconverging CONDITION branches (OR-join via dead-path elimination) ──
    @Test
    void conditionBranchesReconverge_mergeNodeFiresOnceFromTakenBranch() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-cond-merge";
        seedSession(session);
        WorkflowStep a = step(wf, 1, seedEchoAgent("A (echo)"), "AGENT");
        WorkflowStep cond = step(wf, 2, "contains:over", "CONDITION");
        WorkflowStep t = step(wf, 3, seedEchoAgent("T (echo)"), "AGENT");
        WorkflowStep f = step(wf, 3, seedEchoAgent("F (echo)"), "AGENT");
        WorkflowStep m = step(wf, 4, seedEchoAgent("M (echo)"), "AGENT"); // both branches reconverge here

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(a, cond, t, f, m),
                List.of(
                        edge(wf, a.getId(), cond.getId(), null),
                        edge(wf, cond.getId(), t.getId(), "true"),
                        edge(wf, cond.getId(), f.getId(), "false"),
                        edge(wf, t.getId(), m.getId(), null),
                        edge(wf, f.getId(), m.getId(), null)),
                "EXP over the limit", () -> false);

        String content = out.contentText();
        // The merge node M is the single terminal; it fired once from the taken (true) branch even
        // though its other predecessor (F) was on the dead branch — OR-join, not a deadlock.
        assertTrue(content.contains("[M (echo)]"), "merge node must run: " + content);
        assertTrue(content.contains("[T (echo)]"), "merge must see the taken branch: " + content);
        assertFalse(content.contains("[F (echo)]"), "dead branch must not contribute: " + content);
        // A + COND + T + M ran; F was dead. M ran exactly once.
        assertEquals(4, nodeRuns.countByRunId(run.getId()), "A,COND,T,M run; F dead; M fires once");
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
                VALUES (?, 'dag-cond-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
