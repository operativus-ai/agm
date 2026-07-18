package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves the OFFLINE echo path that backs the demo workflows
 *   (demo-015-workflows.sql) actually runs end-to-end — through the REAL
 *   {@code AgentService} / {@code AgentClientFactory} / {@code EchoModelProvider} /
 *   {@code EchoChatModel} chain, with NO LLM API key and NO mocked agent operations.
 *
 *   <p>Unlike {@link WorkflowRouterE2eRuntimeTest}, this test does NOT import
 *   {@code RecordingAgentOperationsConfig}: agent steps execute for real and the
 *   {@code provider='ECHO'} model returns "[&lt;agent name&gt;] &lt;input&gt;", so the threaded
 *   workflow payload is directly assertable. It mirrors the three demo workflow shapes:
 *   sequential AGENT chain, CONDITION-gate + HITL ROUTER, and PARALLEL + LOOP.</p>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeEmbeddingModelConfig.class, NoOpReflectionServiceConfig.class, JobQueueTestSupport.class})
@TestPropertySource(properties = "agm.workflow.router.enabled=true")
public class DemoEchoWorkflowRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;

    // ─── WF1: sequential AGENT chain ─────────────────────────────────────────
    @Test
    void contentPipeline_sequentialEchoChain_threadsPayloadThroughEachStep() {
        HttpHeaders auth = newCaller("content");
        seedEchoModel();
        String research = seedEchoAgent("Research (echo)");
        String writer = seedEchoAgent("Writer (echo)");
        String qa = seedEchoAgent("QA Reviewer (echo)");

        String wf = createWorkflow(auth, "Echo content pipeline");
        insertAgentStep(wf, 1, research);
        insertAgentStep(wf, 2, writer);
        insertAgentStep(wf, 3, qa);

        seedSession("sess-echo-content");
        runWorkflow(auth, wf, "sess-echo-content", "draft the release post");
        awaitWorkflowStatus(wf, "COMPLETED");

        String payload = finalPayload(wf);
        // Each echo step prefixes the prior output, so all three labels are present and nested.
        assertTrue(payload.contains("[Research (echo)]"), "research step echo missing: " + payload);
        assertTrue(payload.contains("[Writer (echo)]"), "writer step echo missing: " + payload);
        assertTrue(payload.contains("[QA Reviewer (echo)]"), "qa step echo missing: " + payload);
        assertTrue(payload.contains("draft the release post"), "original input not threaded: " + payload);
    }

    // ─── WF2: CONDITION gate + HITL ROUTER (large → pause → approve) ──────────
    // Shape: intake(1) → CONDITION contains:over(2, else→finalize) → ROUTER HITL(3,
    //   approve→finalize, reject→rejection) → rejection(4) → finalize(5). All post-router
    //   steps are AGENTs so the resume path (which runs remaining steps as agents) is safe.
    private String buildExpenseWorkflow(HttpHeaders auth, String intake, String writer, String finance) {
        String wf = createWorkflow(auth, "Echo expense approval " + UUID.randomUUID());
        String finalizeStep = "step-finalize-" + UUID.randomUUID();
        String rejectionStep = "step-rejection-" + UUID.randomUUID();
        insertAgentStep(wf, 1, intake);
        insertConditionStep(wf, 2, "contains:over", "ELSE_BRANCH", finalizeStep);
        insertRouterStepHitl(wf, 3, finalizeStep, rejectionStep); // approve→finalize, reject→rejection
        insertAgentStepWithId(rejectionStep, wf, 4, writer);
        insertAgentStepWithId(finalizeStep, wf, 5, finance);
        return wf;
    }

    @Test
    void expenseApproval_largeAmount_pausesAtRouter_thenApproveRunsFinalizeOnly() {
        HttpHeaders auth = newCaller("exp-large");
        seedEchoModel();
        String wf = buildExpenseWorkflow(auth,
                seedEchoAgent("Intake Triage (echo)"),
                seedEchoAgent("Rejection Notice (echo)"),
                seedEchoAgent("Finance Approver (echo)"));

        seedSession("sess-echo-exp-large");
        runWorkflow(auth, wf, "sess-echo-exp-large",
                "Expense EXP-1: $4,250 for the offsite — over the $1,000 limit.");

        // Large amount → CONDITION true → ROUTER pauses for the human decision.
        awaitWorkflowStatus(wf, "AWAITING_ROUTE_SELECTION");

        String runId = jdbc.queryForObject(
                "SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
        ResponseEntity<Map<String, Object>> cont = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/continue"),
                HttpMethod.POST, new HttpEntity<>(Map.of("choiceKey", "approve"), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, cont.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(cont.getBody().get("jobId")), Duration.ofSeconds(10));

        awaitWorkflowStatus(wf, "COMPLETED");
        String payload = finalPayload(wf);
        assertTrue(payload.contains("[Finance Approver (echo)]"),
                "approve branch must run the finalize step: " + payload);
        assertTrue(!payload.contains("[Rejection Notice (echo)]"),
                "approve must skip the rejection step (jumped straight to finalize): " + payload);
    }

    @Test
    void expenseApproval_largeAmount_rejectRunsRejectionThenFinalize() {
        HttpHeaders auth = newCaller("exp-reject");
        seedEchoModel();
        String wf = buildExpenseWorkflow(auth,
                seedEchoAgent("Intake Triage (echo)"),
                seedEchoAgent("Rejection Notice (echo)"),
                seedEchoAgent("Finance Approver (echo)"));

        seedSession("sess-echo-exp-reject");
        runWorkflow(auth, wf, "sess-echo-exp-reject",
                "Expense EXP-3: $9,900 — over the $1,000 limit, looks questionable.");
        awaitWorkflowStatus(wf, "AWAITING_ROUTE_SELECTION");

        String runId = jdbc.queryForObject(
                "SELECT id FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
        ResponseEntity<Map<String, Object>> cont = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/continue"),
                HttpMethod.POST, new HttpEntity<>(Map.of("choiceKey", "reject"), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, cont.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(cont.getBody().get("jobId")), Duration.ofSeconds(10));

        awaitWorkflowStatus(wf, "COMPLETED");
        String payload = finalPayload(wf);
        // reject → rejection notice then finalize; both labels present, finalize outermost.
        assertTrue(payload.contains("[Rejection Notice (echo)]"), "reject must draft the notice: " + payload);
        assertTrue(payload.contains("[Finance Approver (echo)]"), "reject must still finalize: " + payload);
    }

    // ─── WF2: small amount auto-approves via ELSE_BRANCH (no pause) ───────────
    @Test
    void expenseApproval_smallAmount_autoApprovesViaElseBranch() {
        HttpHeaders auth = newCaller("exp-small");
        seedEchoModel();
        String wf = buildExpenseWorkflow(auth,
                seedEchoAgent("Intake Triage (echo)"),
                seedEchoAgent("Rejection Notice (echo)"),
                seedEchoAgent("Finance Approver (echo)"));

        seedSession("sess-echo-exp-small");
        runWorkflow(auth, wf, "sess-echo-exp-small",
                "Expense EXP-2: $85 taxi to the airport, within policy.");

        // No "over" → CONDITION false → ELSE_BRANCH jumps straight to finalize; the run
        // completes without ever pausing at the router.
        awaitWorkflowStatus(wf, "COMPLETED");
        String payload = finalPayload(wf);
        assertTrue(payload.contains("[Finance Approver (echo)]"), "auto-approve finalize missing: " + payload);
        assertTrue(!payload.contains("[Rejection Notice (echo)]"), "rejection step must not run: " + payload);
    }

    // ─── WF3: PARALLEL fan-out + bounded LOOP ────────────────────────────────
    @Test
    void researchFanout_parallelGatherThenBoundedLoop_completes() {
        HttpHeaders auth = newCaller("research");
        seedEchoModel();
        String a = seedEchoAgent("Research (echo)");
        String b = seedEchoAgent("Finance (echo)");
        String c = seedEchoAgent("Legal (echo)");
        String writer = seedEchoAgent("Writer (echo)");
        String qa = seedEchoAgent("QA Reviewer (echo)");

        String wf = createWorkflow(auth, "Echo research fanout");
        insertParallelStep(wf, 1, a);
        insertParallelStep(wf, 1, b);
        insertParallelStep(wf, 1, c);
        insertAgentStep(wf, 2, writer);
        insertLoopStep(wf, 3, "max:2");
        insertAgentStep(wf, 4, qa);

        seedSession("sess-echo-research");
        runWorkflow(auth, wf, "sess-echo-research", "assess vendor X risk");
        awaitWorkflowStatus(wf, "COMPLETED");

        String payload = finalPayload(wf);
        // The parallel join inserts "---" between the three branch outputs; the loop's last
        // iteration leaves the QA echo as the outermost label.
        assertTrue(payload.contains("[QA Reviewer (echo)]"), "loop body (qa) output missing: " + payload);
        assertTrue(payload.contains("---"), "parallel join separator missing: " + payload);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "echo-wf-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local", "pwd-echo-wf-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
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

    private String createWorkflow(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'echo-wf-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private String insertAgentStep(String wf, int order, String agentId) {
        return insertAgentStepWithId("step-" + UUID.randomUUID(), wf, order, agentId);
    }

    private String insertAgentStepWithId(String id, String wf, int order, String agentId) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'AGENT')", id, wf, order, agentId);
        return id;
    }

    private void insertParallelStep(String wf, int order, String agentId) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'PARALLEL')", "step-" + UUID.randomUUID(), wf, order, agentId);
    }

    private void insertLoopStep(String wf, int order, String expr) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, ?, ?, 'LOOP')", "step-" + UUID.randomUUID(), wf, order, expr);
    }

    private void insertConditionStep(String wf, int order, String expr, String onReject, String elseStepId) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, on_reject, else_step_id) "
                + "VALUES (?, ?, ?, ?, 'CONDITION', ?, ?)",
                "step-" + UUID.randomUUID(), wf, order, expr, onReject, elseStepId);
    }

    private void insertRouterStepHitl(String wf, int order, String approveStepId, String rejectStepId) {
        String json = "{\"selectorType\":\"HITL\",\"selectorExpression\":null,\"choices\":{"
                + "\"approve\":\"" + approveStepId + "\",\"reject\":\"" + rejectStepId + "\"},"
                + "\"defaultChoice\":\"approve\"}";
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, action, router_config) "
                + "VALUES (?, ?, ?, 'ROUTER', ?::jsonb)", "step-" + UUID.randomUUID(), wf, order, json);
    }

    private void runWorkflow(HttpHeaders auth, String wf, String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + wf + "/run"), HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        jobs.awaitJobSuccess(String.valueOf(accepted.getBody().get("jobId")), Duration.ofSeconds(20));
    }

    private void awaitWorkflowStatus(String wf, String expected) {
        Awaitility.await("workflow_runs.status=" + expected + " for " + wf)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = ?",
                            Integer.class, wf, expected);
                    return n != null && n >= 1;
                });
    }

    private String finalPayload(String wf) {
        return jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE workflow_id = ?", String.class, wf);
    }
}
