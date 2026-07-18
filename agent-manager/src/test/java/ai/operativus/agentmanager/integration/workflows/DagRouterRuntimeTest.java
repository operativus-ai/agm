package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-4c — ROUTER port selection in the {@link DagWorkflowExecutor}
 *   frontier scheduler. A ROUTER node resolves its {@link RouterStepConfig} to a choice key via the
 *   RULE {@code RouteSelector} (JSONPath) and the scheduler activates only the edge labelled with
 *   that key; the other branches are pruned by dead-path elimination (DAG-4b). Verified through the
 *   real agent path with the offline {@code ECHO} model.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagRouterRuntimeTest extends BaseIntegrationTest {

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void router_ruleSelector_routesToApprovePort() {
        assertRoutes("{\"decision\":\"approve\"}", "[Approve (echo)]", "[Reject (echo)]");
    }

    @Test
    void router_ruleSelector_routesToRejectPort() {
        assertRoutes("{\"decision\":\"reject\"}", "[Reject (echo)]", "[Approve (echo)]");
    }

    @Test
    void router_ruleSelector_unknownValue_fallsBackToDefaultChoice() {
        // $.decision = "maybe" is not a declared choice → defaultChoice "approve" wins.
        assertRoutes("{\"decision\":\"maybe\"}", "[Approve (echo)]", "[Reject (echo)]");
    }

    /** Runs a ROUTER(entry)→{approve,reject} graph with the given JSON input; asserts only the
     *  expected branch ran (2 node runs: ROUTER + the taken branch). */
    private void assertRoutes(String jsonInput, String expectedLabel, String forbiddenLabel) {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-router-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep router = step(wf, 1, null, "ROUTER");
        WorkflowStep approve = step(wf, 2, seedEchoAgent("Approve (echo)"), "AGENT");
        WorkflowStep reject = step(wf, 2, seedEchoAgent("Reject (echo)"), "AGENT");
        router.setRouterConfig(new RouterStepConfig(RouteSelectorType.RULE, "$.decision",
                Map.of("approve", approve.getId(), "reject", reject.getId()), "approve"));

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(router, approve, reject),
                List.of(
                        edge(wf, router.getId(), approve.getId(), "approve"),
                        edge(wf, router.getId(), reject.getId(), "reject")),
                jsonInput, () -> false);

        String content = out.contentText();
        assertTrue(content.contains(expectedLabel), "expected branch missing: " + content);
        assertFalse(content.contains(forbiddenLabel), "non-selected branch ran: " + content);
        assertEquals(2, nodeRuns.countByRunId(run.getId()), "only ROUTER + the routed branch execute");
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

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'dag-router-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
