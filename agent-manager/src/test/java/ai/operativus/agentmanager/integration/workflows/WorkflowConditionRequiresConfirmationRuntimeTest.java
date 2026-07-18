package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperations;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
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
 * Domain Responsibility: REQ-DR-6 PR-4 — pins the CONDITION dispatcher's
 *   {@code requires_confirmation} HITL gate end-to-end.
 *
 *   <p>Semantics: when {@code requires_confirmation=true} on a CONDITION step,
 *   the dispatcher pauses the run with {@code RunStatus.PAUSED} and persists
 *   {@code current_step_order} to the planned cursor (the position the loop
 *   would resume from after applying the on_reject policy). Operator approves
 *   via the existing {@code POST /api/v1/workflows/runs/{runId}/resume}
 *   endpoint; rejects by calling the cancel endpoint directly.
 *
 *   <p>Cases:
 *   <ul>
 *     <li>condition TRUE + requires_confirmation → pause; resume → continues
 *         to the next step.</li>
 *     <li>condition FALSE + SKIP + requires_confirmation → pause with cursor
 *         past the skipped step; resume → skips the next step, runs the one
 *         after.</li>
 *     <li>condition FALSE + ELSE_BRANCH + requires_confirmation → pause with
 *         cursor at elseStep - 1; resume → executes elseStep onwards.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowConditionRequiresConfirmationRuntimeTest extends BaseIntegrationTest {

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
    void requiresConfirmation_conditionTrue_pausesThenResumesIntoNextStep() {
        HttpHeaders auth = newCaller("rc-true");
        String workflowId = createWorkflow(auth, "Req-conf TRUE");
        String tgt = seedAgent("rc-true-tgt");
        String tail = seedAgent("rc-true-tail");

        insertConditionStep(workflowId, 1, "contains:go", null, null, true);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-rc-true");
        runner.scriptResponse("tgt-output");
        runner.scriptResponse("tail-output");

        String runId = runWorkflowAndExpectPaused(auth, workflowId, "sess-rc-true", "let's go");
        // Before resume: pause confirmed, no AGENT calls.
        assertEquals(0, runner.calls.size(),
                "before /resume the pause must hold; got: " + runner.calls);

        resume(auth, runId);
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "after /resume on truthy condition: target + tail must run; got: " + runner.calls);
    }

    @Test
    void requiresConfirmation_conditionFalseSkip_pausesThenResumesSkippingTarget() {
        HttpHeaders auth = newCaller("rc-false-skip");
        String workflowId = createWorkflow(auth, "Req-conf FALSE+SKIP");
        String tgt = seedAgent("rc-skip-tgt");
        String tail = seedAgent("rc-skip-tail");

        insertConditionStep(workflowId, 1, "contains:go", "SKIP", null, true);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-rc-skip");
        runner.scriptResponse("tail-output");

        String runId = runWorkflowAndExpectPaused(auth, workflowId, "sess-rc-skip", "halt");

        resume(auth, runId);
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "on resume, SKIP policy must skip target; only tail runs; got: " + runner.calls);
    }

    @Test
    void requiresConfirmation_conditionFalseElseBranch_pausesThenResumesIntoElseTarget() {
        HttpHeaders auth = newCaller("rc-false-else");
        String workflowId = createWorkflow(auth, "Req-conf FALSE+ELSE_BRANCH");
        String thenTgt = seedAgent("rc-else-then");
        String elseTgt = seedAgent("rc-else-else");
        String tail = seedAgent("rc-else-tail");

        String elseStepId = insertAgentStep(workflowId, 3, elseTgt);
        insertConditionStep(workflowId, 1, "contains:go", "ELSE_BRANCH", elseStepId, true);
        insertAgentStep(workflowId, 2, thenTgt);
        insertAgentStep(workflowId, 4, tail);

        seedSession("sess-rc-else");
        runner.scriptResponse("else-output");
        runner.scriptResponse("tail-output");

        String runId = runWorkflowAndExpectPaused(auth, workflowId, "sess-rc-else", "halt");

        resume(auth, runId);
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "on resume, ELSE_BRANCH must jump to elseTgt + linear continuation runs tail; "
                        + "got: " + runner.calls);
        assertEquals(elseTgt, runner.calls.get(0).agentId(),
                "first call must be else-target");
        assertEquals(tail, runner.calls.get(1).agentId(),
                "second call must be tail (linear after the branch)");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "rc-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-rc-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "Req-conf agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'rc-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression,
                                     String onReject, String elseStepId, boolean requiresConfirmation) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'cond-expr-placeholder', 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, expression);
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, "
                        + "on_reject, else_step_id, requires_confirmation) "
                        + "VALUES (?, ?, ?, ?, 'CONDITION', ?, ?, ?)",
                UUID.randomUUID().toString(), workflowId, order, expression,
                onReject, elseStepId, requiresConfirmation);
    }

    private String insertAgentStep(String workflowId, int order, String agentId) {
        String id = "step-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, ?, ?, 'AGENT')",
                id, workflowId, order, agentId);
        return id;
    }

    /**
     * Drives a workflow run synchronously through the queue, then waits for the
     * workflow_run row to reach PAUSED. Returns the run id.
     */
    private String runWorkflowAndExpectPaused(HttpHeaders auth, String workflowId,
                                              String sessionId, String input) {
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", input, "sessionId", sessionId), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));
        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        awaitWorkflowStatus(workflowId, "PAUSED");
        return jdbc.queryForObject(
                "SELECT id FROM workflow_runs WHERE workflow_id = ?",
                String.class, workflowId);
    }

    private void resume(HttpHeaders auth, String runId) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"),
                HttpMethod.POST,
                // null/empty output → resumeWorkflowRun falls back to run.currentPayload
                new HttpEntity<>(Map.of("output", ""), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        String jobId = String.valueOf(resp.getBody().get("jobId"));
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
