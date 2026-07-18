package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.core.model.WorkflowConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: End-to-end auto-pause canary for
 *   {@link com.operativus.agentmanager.control.service.WorkflowService#executeWorkflowAsync}.
 *   When an agent step returns the {@link WorkflowConstants#PAUSED_SENTINEL} value
 *   ({@code "__PAUSED__"}), the engine must:
 *   <ul>
 *     <li>Flip {@code workflow_runs.status} to PAUSED</li>
 *     <li>Stop the step loop — subsequent steps must NOT execute</li>
 *     <li>Set {@code workflow_runs.current_step_order} to the sentinel-returning step</li>
 *   </ul>
 *
 *   <p>Sibling {@code WorkflowsRuntimeTest.resumePausedRun_picksUpFromNextStepAndReachesCompleted}
 *   covers the RESUME path with a synthetic PAUSED seed. This class covers the
 *   AUTOMATIC TRANSITION path: a real agent return value of the sentinel string
 *   drives the workflow_run row into PAUSED on its own. Without this canary,
 *   removing the sentinel handling from the step loop wouldn't be caught by any
 *   existing integration test.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowHitlAutoPauseRuntimeTest extends BaseIntegrationTest {

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
    void agentReturnsPausedSentinel_workflowRunGoesToPaused_andStepsAfterCurrentDoNotRun() {
        HttpHeaders auth = newCaller("auto-pause");
        String workflowId = createWorkflow(auth, "Auto-pause Flow");

        String agentA = seedAgent("auto-pause-a");
        String agentB = seedAgent("auto-pause-b");
        String agentC = seedAgent("auto-pause-c");
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 1, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentA);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 2, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentB);
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 3, ?, 'AGENT')", UUID.randomUUID().toString(), workflowId, agentC);

        seedSession("sess-auto-pause");
        // Step 1 returns the sentinel — the engine must pause BEFORE running step 2 or 3.
        runner.scriptResponse(WorkflowConstants.PAUSED_SENTINEL);
        // Scripts for steps 2 and 3 are queued only to catch a regression: if the engine
        // mistakenly continues past the sentinel, runner.calls will record extra entries
        // that the size-1 assertion below will detect.
        runner.scriptResponse("step-2-should-not-run");
        runner.scriptResponse("step-3-should-not-run");

        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "go", "sessionId", "sess-auto-pause"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = String.valueOf(accepted.getBody().get("jobId"));

        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));

        Awaitility.await("workflow_runs.status=PAUSED for auto-pause workflow")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ? AND status = 'PAUSED'",
                            Integer.class, workflowId);
                    return n != null && n >= 1;
                });

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT status, current_step_order FROM workflow_runs WHERE workflow_id = ?",
                workflowId);

        assertAll("PAUSED_SENTINEL handshake transitions workflow_run to PAUSED + halts step loop",
                () -> assertEquals("PAUSED", runRow.get("status"),
                        "agent returning __PAUSED__ must flip workflow_runs.status to PAUSED — "
                                + "COMPLETED here means the engine no longer recognizes the "
                                + "sentinel and ran subsequent steps anyway (breaks HITL)"),
                () -> assertEquals(1, runRow.get("current_step_order"),
                        "current_step_order must remain at the sentinel-returning step (1) so "
                                + "that resume's stepOrder > currentStepOrder filter picks up "
                                + "from step 2 — not skipping ahead, not re-running step 1"),
                () -> assertEquals(1, runner.calls.size(),
                        "exactly one agent call must have fired before the pause; "
                                + "calls.size > 1 means the engine kept executing past the "
                                + "sentinel — fatal for HITL gating; "
                                + "calls.size == 0 means the job never reached step 1"),
                () -> assertNotNull(runner.calls.get(0),
                        "the single recorded call must be for step 1's agent"),
                () -> assertEquals(agentA, runner.calls.get(0).agentId(),
                        "step 1's agent was agentA — a different agentId means the step-loop "
                                + "iteration order regressed; got " + runner.calls.get(0).agentId()));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-pause-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-pause-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "Auto-pause test agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'auto-pause-user', 'auto-pause-org', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }
}
