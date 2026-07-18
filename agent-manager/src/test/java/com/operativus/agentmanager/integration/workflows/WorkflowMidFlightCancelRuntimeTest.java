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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Cooperative mid-flight cancellation canary for
 *   {@link com.operativus.agentmanager.control.service.WorkflowService#executeWorkflowAsync}.
 *   A three-step workflow is dispatched; step 2's scripted response cancels its own
 *   workflow_run row (committed via the test's JdbcTemplate before step 2 returns).
 *   The workflow loop's cooperative-cancel poll at the top of each iteration must
 *   pick up the CANCELLED status BEFORE dispatching step 3 — proven by step 3 never
 *   firing on the recording stub.
 *
 *   <p>Contract:
 *   <ul>
 *     <li>{@code workflow_runs.status} ends as CANCELLED (not COMPLETED).</li>
 *     <li>Exactly 2 agent calls fired ({@code runner.calls.size() == 2}) — step 3
 *         was skipped by the cooperative-cancel poll.</li>
 *     <li>{@code current_step_order} reflects step 2 (the last step that ran),
 *         not step 3 — so post-mortem triage can see where the cancel landed.</li>
 *   </ul>
 *
 *   <p>Non-goal: interrupting the in-flight step. {@code Thread.interrupt} on a VT
 *   mid-JDBC call leaves zombie PG connections (see
 *   {@code BackgroundRunsRuntimeTest.deleteBackgroundRunOnCompletedRun*} for the
 *   prior incident). Cooperative cancellation, by design, only takes effect at
 *   step boundaries.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowMidFlightCancelRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;

    @BeforeEach
    void seedModelAndResetRunner() {
        runner.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void cancelMidFlight_step3IsSkipped_workflowRunEndsCancelled() {
        HttpHeaders auth = newCaller("cancel-midflight");
        String workflowId = createWorkflow(auth, "Cancel Mid-Flight Flow");

        String agentA = seedAgent("cancel-midflight-a");
        String agentB = seedAgent("cancel-midflight-b");
        String agentC = seedAgent("cancel-midflight-c");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 1, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentA);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 2, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentB);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 3, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentC);

        seedSession("sess-cancel-midflight");

        // Step 2's side-effect fires the cancel from inside the workflow VT, before
        // returning. The UPDATE commits via the test's JdbcTemplate's auto-commit, so
        // the next iteration's findById (the cooperative-cancel poll) sees CANCELLED
        // before step 3 is dispatched.
        runner.scriptResponse("step-1-output");
        runner.scriptResponseWithSideEffect("step-2-output-cancel-fired", () ->
                jdbc.update(
                        "UPDATE workflow_runs SET status = 'CANCELLED', updated_at = NOW() "
                                + "WHERE workflow_id = ? AND status = 'RUNNING'",
                        workflowId));
        runner.scriptResponse("step-3-should-not-run");

        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-cancel-midflight"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));

        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));

        // The workflow VT must settle: it had to step out of the for-loop's next iteration
        // via the cooperative-cancel return. Wait on the row.
        Awaitility.await("workflow_runs.status=CANCELLED for mid-flight cancel")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = 'CANCELLED'",
                            Integer.class, workflowId);
                    return n != null && n >= 1;
                });

        // Give the VT a small grace window after CANCELLED is observed — if step 3 were
        // going to fire erroneously, it would fire here. Then assert.
        try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, current_step_order FROM workflow_runs WHERE workflow_id = ?", workflowId);

        assertAll("mid-flight cancel contract",
                () -> assertEquals("CANCELLED", runRow.get("status"),
                        "workflow_runs.status must remain CANCELLED — if COMPLETED, the loop's "
                                + "post-loop save overwrote it (the cooperative-cancel return must "
                                + "happen BEFORE the post-loop save runs)"),
                () -> assertEquals(2, runner.calls.size(),
                        "exactly 2 agent calls must have fired (step 1 + step 2); "
                                + "size 3 means the cooperative-cancel poll did not pick up the "
                                + "CANCELLED status before step 3 dispatched"),
                () -> assertNotNull(runner.calls.get(0)),
                () -> assertEquals(agentA, runner.calls.get(0).agentId(),
                        "first call must be step 1's agent"),
                () -> assertEquals(agentB, runner.calls.get(1).agentId(),
                        "second call must be step 2's agent (the one that fired the cancel)"),
                () -> assertNotEquals(3, runRow.get("current_step_order"),
                        "current_step_order must NOT be 3 — that would indicate step 3 began "
                                + "executing and updated the row before being cancelled. The "
                                + "cancel must short-circuit at the loop's top, before any step 3 "
                                + "side effects."));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-cancel-mf-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-cancel-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedAgent(String label) {
        String id = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, id, "Cancel mid-flight test agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'wf-cancel-user', 'wf-cancel-org', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
