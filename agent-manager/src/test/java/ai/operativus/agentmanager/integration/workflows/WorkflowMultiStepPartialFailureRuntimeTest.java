package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.core.model.enums.RunStatus;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Multi-step partial failure canary for
 *   {@link ai.operativus.agentmanager.control.service.WorkflowService#executeWorkflowAsync}.
 *   Step 1 succeeds and writes its output to {@code current_payload}; step 2 throws an
 *   uncaught exception. The engine must:
 *   <ul>
 *     <li>Flip {@code workflow_runs.status} to FAILED</li>
 *     <li>Stop the step loop at step 2 — step 3 must NOT execute</li>
 *     <li>Leave {@code current_step_order} pointing at the failed step (2),
 *         so post-mortem triage can identify where the run died</li>
 *     <li>Preserve step 1's output in {@code current_payload} (or the failure
 *         payload — either is acceptable; the contract under test is "no NPE,
 *         no half-overwritten state")</li>
 *   </ul>
 *
 *   <p>Sibling case 8 in {@link WorkflowsRuntimeTest}
 *   ({@code agentStepFailure_causesWorkflowRunToTerminateInFailedStatus}) covers the
 *   single-step failure case where step 1 throws. This class covers the more
 *   realistic multi-step scenario where the workflow has already made progress
 *   when the failure happens — the partial-progress preservation contract is
 *   different and previously untested.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowMultiStepPartialFailureRuntimeTest extends BaseIntegrationTest {

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
    void step2Throws_workflowRunFlipsToFailedAtStep2_step3DoesNotRun() {
        HttpHeaders auth = newCaller("partial-fail");
        String workflowId = createWorkflow(auth, "Partial-failure Flow");

        String agentA = seedAgent("partial-fail-a");
        String agentB = seedAgent("partial-fail-b");
        String agentC = seedAgent("partial-fail-c");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 1, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentA);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 2, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentB);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 3, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentC);

        seedSession("sess-partial-fail");

        // Step 1 succeeds with a recognizable output. Step 2 throws. Step 3 is queued
        // as a should-not-run canary — if it ever fires, runner.calls.size() would be 3.
        runner.scriptResponse("step-1-good-output");
        runner.scriptThrow(new RuntimeException("boom — simulated step-2 failure"));
        runner.scriptResponse("step-3-should-not-run");

        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-partial-fail"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));

        jobs.processNow();
        // A FAILED run is a non-retryable job outcome since #1167 — the job lands in the DLQ
        // immediately (one run row, no re-execution). This deliberately-failing scenario must
        // await job FAILURE; the run-row assertions below stay the real contract under test.
        jobs.awaitJobFailure(jobId, Duration.ofSeconds(10));

        Awaitility.await("workflow_runs.status=FAILED for partial-failure workflow")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = 'FAILED'",
                            Integer.class, workflowId);
                    return n != null && n >= 1;
                });

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, current_step_order, current_payload FROM workflow_runs "
                        + "WHERE workflow_id = ?", workflowId);

        assertAll("multi-step partial-failure contract",
                () -> assertEquals("FAILED", runRow.get("status"),
                        "uncaught exception in step 2 must flip workflow_runs.status to FAILED "
                                + "— COMPLETED here means the catch-block in executeWorkflowAsync "
                                + "swallowed the error without updating state"),
                () -> assertEquals(2, runRow.get("current_step_order"),
                        "current_step_order must point at the failed step (2). If it's 1, "
                                + "the row reflects pre-failure progress and post-mortem triage "
                                + "loses the failure location. If it's 3, the engine continued "
                                + "past the throw — fatal for partial-failure contract."),
                () -> assertEquals(2, runner.calls.size(),
                        "exactly 2 agent calls must have fired (step 1 success + step 2 throw); "
                                + "size 3 means step 3 ran after the throw — broken halt-on-failure; "
                                + "size < 2 means step 1 was skipped"),
                () -> assertNotNull(runner.calls.get(0)),
                () -> assertEquals(agentA, runner.calls.get(0).agentId(),
                        "first call must be step 1's agent"),
                () -> assertEquals(agentB, runner.calls.get(1).agentId(),
                        "second call must be step 2's agent (the failing one)"),
                () -> assertEquals("step-1-good-output", runner.calls.get(1).input(),
                        "step 2 input must be step 1's output — the chained-payload handshake "
                                + "regresses if this is the workflow's initial input ('go')"),
                () -> assertTrue(runRow.get("current_payload") == null
                                || runRow.get("current_payload") instanceof String,
                        "current_payload must not be a half-corrupted value; got "
                                + runRow.get("current_payload")));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-fail-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-fail-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "Partial-failure test agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'partial-fail-user', 'partial-fail-org', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
