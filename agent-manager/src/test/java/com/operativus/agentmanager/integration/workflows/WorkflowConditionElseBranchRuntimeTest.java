package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import com.operativus.agentmanager.integration.support.RecordingAgentOperations;
import com.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: REQ-DR-6 PR-3 — pins the CONDITION dispatcher's
 *   {@code on_reject=ELSE_BRANCH} behavior end-to-end.
 *
 *   <p>Workflow shape: 4 steps — [CONDITION → AGENT (then-target) → AGENT (else-target) → AGENT (tail)].
 *   The CONDITION's {@code else_step_id} points at the third step.
 *
 *   <ul>
 *     <li>condition TRUE  → no policy fires; step2 runs, then step3, then step4.</li>
 *     <li>condition FALSE → cursor jumps to step3 (the else-target); step3 then
 *         step4 run; step2 is skipped.</li>
 *     <li>condition FALSE + elseStepId points to deleted/missing step → run FAILED
 *         with reason {@code ELSE_BRANCH_TARGET_NOT_FOUND}.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowConditionElseBranchRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;

    @BeforeEach
    void seedAndReset() {
        runner.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void elseBranch_conditionFalse_dispatcherJumpsToElseTarget() {
        HttpHeaders auth = newCaller("else-false");
        String workflowId = createWorkflow(auth, "Else-branch FALSE");
        String thenTgt = seedAgent("else-then");
        String elseTgt = seedAgent("else-else");
        String tail = seedAgent("else-tail");

        String elseStepId = insertAgentStep(workflowId, 3, elseTgt);
        insertConditionStep(workflowId, 1, "contains:go", "ELSE_BRANCH", elseStepId);
        insertAgentStep(workflowId, 2, thenTgt);
        insertAgentStep(workflowId, 4, tail);

        seedSession("sess-else-false");
        runner.scriptResponse("else-output");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-else-false", "halt");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "false condition + ELSE_BRANCH must skip step2 (then-target) and run "
                        + "step3 (else-target) + step4 (tail); got: " + runner.calls);
        assertEquals(elseTgt, runner.calls.get(0).agentId(),
                "first call must be the else-target agent");
        assertEquals(tail, runner.calls.get(1).agentId(),
                "second call must be the tail agent (linear continuation after the branch)");
    }

    @Test
    void elseBranch_conditionTrue_policyDoesNotFire() {
        HttpHeaders auth = newCaller("else-true");
        String workflowId = createWorkflow(auth, "Else-branch TRUE");
        String thenTgt = seedAgent("true-then");
        String elseTgt = seedAgent("true-else");
        String tail = seedAgent("true-tail");

        String elseStepId = insertAgentStep(workflowId, 3, elseTgt);
        insertConditionStep(workflowId, 1, "contains:go", "ELSE_BRANCH", elseStepId);
        insertAgentStep(workflowId, 2, thenTgt);
        insertAgentStep(workflowId, 4, tail);

        seedSession("sess-else-true");
        runner.scriptResponse("then-output");
        runner.scriptResponse("else-output");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-else-true", "let's go");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        // Truthy condition: ELSE_BRANCH policy is gated to the false path,
        // so step2 runs, then linear continuation hits step3 + step4.
        assertEquals(3, runner.calls.size(),
                "truthy condition must NOT fire ELSE_BRANCH; expected then→else→tail linear, got: "
                        + runner.calls);
        assertEquals(thenTgt, runner.calls.get(0).agentId());
        assertEquals(elseTgt, runner.calls.get(1).agentId());
        assertEquals(tail, runner.calls.get(2).agentId());
    }

    @Test
    void elseBranch_targetDeletedAfterCreate_runFailsWithReason() {
        HttpHeaders auth = newCaller("else-missing");
        String workflowId = createWorkflow(auth, "Else-branch missing target");
        String thenTgt = seedAgent("missing-then");
        String elseTgt = seedAgent("missing-else");

        String elseStepId = insertAgentStep(workflowId, 3, elseTgt);
        insertConditionStep(workflowId, 1, "contains:go", "ELSE_BRANCH", elseStepId);
        insertAgentStep(workflowId, 2, thenTgt);
        // Simulate operator deleting the else target after the workflow was wired.
        jdbc.update("DELETE FROM workflow_steps WHERE id = ?", elseStepId);

        seedSession("sess-else-missing");

        // The default runWorkflow helper waits for the BackgroundJob to COMPLETE, but
        // WorkflowExecutionJobHandler (per PR #930) throws on workflow_run FAILED so
        // PersistentJobQueueService records the failure. That throw is now a
        // NonRetryableJobException — the job goes straight to the DLQ without retrying
        // (retrying would re-run the workflow and mint a duplicate run row). For this
        // failure-path test we only care that the workflow_run ends in FAILED with the
        // ELSE_BRANCH_TARGET_NOT_FOUND reason; the job's no-retry-to-DLQ behavior is
        // exercised separately by WorkflowsRuntimeTest.agentStepFailure_* and
        // WorkflowExecutionJobHandlerTest.execute_workflowRunsToFailed_throwsSoQueueLogsTheFailure.
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "halt", "sessionId", "sess-else-missing"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        jobs.processNow();
        awaitWorkflowStatus(workflowId, "FAILED");
        assertEquals(0, runner.calls.size(),
                "missing else target must short-circuit before any AGENT call; got: "
                        + runner.calls);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "else-branch-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-eb-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedAgent(String label) {
        String id = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, "Else-branch agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'else-branch-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression,
                                     String onReject, String elseStepId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'cond-expr-placeholder', 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, expression);
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, on_reject, else_step_id) "
                        + "VALUES (?, ?, ?, ?, 'CONDITION', ?, ?)",
                UUID.randomUUID().toString(), workflowId, order, expression, onReject, elseStepId);
    }

    private String insertAgentStep(String workflowId, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, ?, ?, 'AGENT')",
                id, workflowId, order, agentId);
        return id;
    }

    private void runWorkflow(HttpHeaders auth, String workflowId, String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));
        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
    }

    private void awaitWorkflowStatus(String workflowId, String expectedStatus) {
        Awaitility.await("workflow_runs.status=" + expectedStatus + " for " + workflowId)
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = ?",
                            Integer.class, workflowId, expectedStatus);
                    return n != null && n >= 1;
                });
    }
}
