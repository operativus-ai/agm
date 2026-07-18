package ai.operativus.agentmanager.integration.workflows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ai.operativus.agentmanager.compute.workflow.DagWorkflowExecutor;
import ai.operativus.agentmanager.core.entity.WorkflowEdge;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowFunctionStep;
import ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-6 FUNCTION — {@code FunctionNodeExecutor} dispatches to the
 *   {@link WorkflowFunctionStep} named by the node's {@code agent_id} key, threads the function's
 *   deterministic output to successors, and converts an unregistered key into a recorded node
 *   failure (not an unwind). Verified through the real DAG scheduler with the offline {@code ECHO}
 *   model downstream so the function's transformed payload is directly assertable.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagFunctionRuntimeTest extends BaseIntegrationTest {

    @TestConfiguration
    static class UppercaseFunctionConfig {
        @Bean
        WorkflowFunctionStep uppercaseFunctionStep() {
            return new WorkflowFunctionStep() {
                @Override
                public String key() {
                    return "uppercase";
                }

                @Override
                public JsonNode apply(StepInput in, NodeContext ctx) {
                    return TextNode.valueOf(in.inputText().toUpperCase(Locale.ROOT));
                }
            };
        }
    }

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @Test
    void functionTransformsInput_downstreamAgentReceivesIt() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-fn-" + UUID.randomUUID();
        seedSession(session);
        // F(FUNCTION uppercase) → A(AGENT echo). The agent must see the FUNCTION's output, not the raw entry input.
        WorkflowStep f = step(wf, 1, "uppercase", "FUNCTION");
        WorkflowStep a = step(wf, 2, seedEchoAgent("A (echo)"), "AGENT");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(f, a),
                List.of(edge(wf, f.getId(), a.getId())),
                "kickoff", () -> false);

        String content = out.contentText();
        assertTrue(out.success(), "terminal AGENT should succeed: " + content);
        assertTrue(content.contains("KICKOFF"),
                "AGENT did not receive the FUNCTION-transformed (uppercased) input: " + content);
        assertEquals(2, nodeRuns.countByRunId(run.getId()), "F and A each persist one node run");
    }

    @Test
    void unknownFunctionKey_failsTheNodeWithDiagnostics() {
        seedEchoModel();
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-fn-bad-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep f = step(wf, 1, "no-such-function", "FUNCTION");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run, List.of(f), List.of(), "kickoff", () -> false);

        assertFalse(out.success(), "unregistered function key must fail the node");
        assertTrue(out.error() != null && out.error().contains("no-such-function"),
                "failure should name the missing key: " + out.error());
    }

    // ─── Helpers (echo harness, mirrors DagParallelJoinRuntimeTest) ────────────

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
                VALUES (?, 'dag-fn-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
