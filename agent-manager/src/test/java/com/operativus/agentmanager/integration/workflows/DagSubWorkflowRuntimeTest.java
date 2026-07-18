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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-6 WORKFLOW — {@code SubWorkflowNodeExecutor} loads the child
 *   workflow named by the node's {@code agent_id} (org-scoped), runs it synchronously through the
 *   same {@code DagWorkflowExecutor} under a derived child run id, and threads the child's terminal
 *   output to the parent's successors. The {@code workflowDepth} ScopedValue guard converts a
 *   recursive inclusion into a recorded failure instead of infinite recursion, and a cross-org
 *   child id is indistinguishable from not-found (§79).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagSubWorkflowRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void subWorkflowRuns_terminalOutputFlowsToParentSuccessor() {
        seedEchoModel();
        String childWf = seedWorkflow("DEFAULT_SYSTEM_ORG");
        seedAgentStep(childWf, 1, seedEchoAgent("Inner (echo)"));
        String session = "sess-sub-" + UUID.randomUUID();
        seedSession(session);

        String parentWf = "wf-" + UUID.randomUUID();
        // W(WORKFLOW → child) → A(AGENT echo). The parent agent must see the child's terminal output.
        WorkflowStep w = step(parentWf, 1, childWf, "WORKFLOW");
        WorkflowStep a = step(parentWf, 2, seedEchoAgent("Outer (echo)"), "AGENT");

        WorkflowRun run = run(parentWf, session);
        StepOutput out = dag.execute(run,
                List.of(w, a),
                List.of(edge(parentWf, w.getId(), a.getId())),
                "kickoff", () -> false);

        String content = out.contentText();
        assertTrue(out.success(), "terminal AGENT should succeed: " + content);
        assertTrue(content.contains("[Inner (echo)] kickoff"),
                "parent did not receive the child's terminal output: " + content);
        assertTrue(content.contains("[Outer (echo)]"),
                "parent successor did not run after the sub-workflow: " + content);
        // Parent rows (W + A) under the parent run id; child rows under the derived child run id.
        assertEquals(2, nodeRuns.countByRunId(run.getId()),
                "child node runs must not pollute the parent run's rows");
    }

    @Test
    void recursiveInclusion_failsAtMaxNestingDepth() {
        String selfWf = seedWorkflow("DEFAULT_SYSTEM_ORG");
        // The workflow's only step is a WORKFLOW node pointing at itself.
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                        + "VALUES (?, ?, 1, ?, 'WORKFLOW')",
                "step-" + UUID.randomUUID(), selfWf, selfWf);
        String session = "sess-sub-rec-" + UUID.randomUUID();
        seedSession(session);

        String parentWf = "wf-" + UUID.randomUUID();
        WorkflowStep w = step(parentWf, 1, selfWf, "WORKFLOW");

        WorkflowRun run = run(parentWf, session);
        StepOutput out = dag.execute(run, List.of(w), List.of(), "kickoff", () -> false);

        assertFalse(out.success(), "self-including workflow must fail, not recurse forever");
        assertTrue(out.error() != null && out.error().contains("exceeds the maximum"),
                "failure should surface the nesting-depth guard: " + out.error());
    }

    @Test
    void crossOrgChild_isIndistinguishableFromNotFound() {
        String otherOrgWf = seedWorkflow("OTHER_ORG");
        String session = "sess-sub-xorg-" + UUID.randomUUID();
        seedSession(session);

        String parentWf = "wf-" + UUID.randomUUID();
        WorkflowStep w = step(parentWf, 1, otherOrgWf, "WORKFLOW");

        WorkflowRun run = run(parentWf, session);
        StepOutput out = dag.execute(run, List.of(w), List.of(), "kickoff", () -> false);

        assertFalse(out.success(), "cross-org sub-workflow must not execute");
        assertTrue(out.error() != null && out.error().contains("not found"),
                "cross-org must read as not-found (no tenancy leak): " + out.error());
    }

    // ─── Helpers (echo harness, mirrors DagFunctionRuntimeTest) ────────────────

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

    private String seedWorkflow(String orgId) {
        String id = "wf-child-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, 'dag sub-workflow test', ?, now(), now())
                """, id, "Child " + id, orgId);
        return id;
    }

    private void seedAgentStep(String workflowId, int order, String agentId) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                        + "VALUES (?, ?, ?, ?, 'AGENT')",
                "step-" + UUID.randomUUID(), workflowId, order, agentId);
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
                VALUES (?, 'dag-sub-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
