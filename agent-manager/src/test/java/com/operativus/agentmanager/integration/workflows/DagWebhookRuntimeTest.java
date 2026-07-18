package com.operativus.agentmanager.integration.workflows;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves DAG-6 WEBHOOK — {@code WebhookNodeExecutor} resolves the node's
 *   executor id (the step's {@code agent_id} URL) through the {@code WorkflowStepExecutorExtension}
 *   SPI, POSTs the payload via the default SSRF-guarded {@code WebhookWorkflowStepExecutor}, and
 *   threads the webhook's response body to successors. An HTTP failure becomes a recorded node
 *   failure (not an unwind). WireMock plays the external endpoint on a dynamic loopback port
 *   (test profile sets {@code agent.workflow.webhook.allow-loopback-urls=true}).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class})
public class DagWebhookRuntimeTest extends BaseIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired private DagWorkflowExecutor dag;
    @Autowired private WorkflowNodeRunRepository nodeRuns;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void webhookPostsPayload_responseBodyFlowsDownstream() {
        seedEchoModel();
        wireMock.stubFor(post(urlPathEqualTo("/transform"))
                .willReturn(aResponse().withStatus(200).withBody("transformed-by-webhook")));
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-wh-" + UUID.randomUUID();
        seedSession(session);
        // W(WEBHOOK → WireMock) → A(AGENT echo). The agent must see the webhook's response body.
        WorkflowStep w = step(wf, 1, wireMock.baseUrl() + "/transform", "WEBHOOK");
        WorkflowStep a = step(wf, 2, seedEchoAgent("A (echo)"), "AGENT");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run,
                List.of(w, a),
                List.of(edge(wf, w.getId(), a.getId())),
                "kickoff", () -> false);

        String content = out.contentText();
        assertTrue(out.success(), "terminal AGENT should succeed: " + content);
        assertTrue(content.contains("transformed-by-webhook"),
                "AGENT did not receive the webhook's response body: " + content);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/transform"))
                .withRequestBody(containing("kickoff"))
                .withRequestBody(containing(run.getId())));
        assertEquals(2, nodeRuns.countByRunId(run.getId()), "W and A each persist one node run");
    }

    @Test
    void webhookHttpFailure_failsTheNodeWithDiagnostics() {
        wireMock.stubFor(post(urlPathEqualTo("/broken"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-wh-bad-" + UUID.randomUUID();
        seedSession(session);
        String url = wireMock.baseUrl() + "/broken";
        WorkflowStep w = step(wf, 1, url, "WEBHOOK");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run, List.of(w), List.of(), "kickoff", () -> false);

        assertFalse(out.success(), "HTTP 500 from the webhook must fail the node");
        assertTrue(out.error() != null && out.error().contains(url),
                "failure should name the failing executor id: " + out.error());
    }

    @Test
    void nonUrlExecutorIdWithNoSupportingExtension_failsTheNode() {
        String wf = "wf-" + UUID.randomUUID();
        String session = "sess-wh-noext-" + UUID.randomUUID();
        seedSession(session);
        WorkflowStep w = step(wf, 1, "custom-extension-not-deployed", "WEBHOOK");

        WorkflowRun run = run(wf, session);
        StepOutput out = dag.execute(run, List.of(w), List.of(), "kickoff", () -> false);

        assertFalse(out.success(), "unresolvable executor id must fail the node (no silent pass-through)");
        assertTrue(out.error() != null && out.error().contains("custom-extension-not-deployed"),
                "failure should name the unresolvable executor id: " + out.error());
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
                VALUES (?, 'dag-wh-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
