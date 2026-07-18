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
 * Domain Responsibility: REQ-DR-6 PR-2 — pins the CONDITION step dispatcher's
 *   {@code on_reject} policy end-to-end through {@code WorkflowService.executeWorkflowAsync}.
 *   Sibling to {@code WorkflowConditionPrefixesRuntimeTest} (PR-1, expression types) and
 *   {@code WorkflowsRuntimeTest.conditionStep_whenConditionReturnsFalse_skipsNextStep_andProceeds}
 *   (default SKIP semantics).
 *
 *   <p>Workflow shape: 3 steps — [CONDITION → AGENT (target) → AGENT (tail)].
 *
 *   <ul>
 *     <li>{@code on_reject=null} or {@code SKIP} + condition false → skip target,
 *         execute tail. Run COMPLETED. (Pre-DR-6 default behavior.)</li>
 *     <li>{@code on_reject=CANCEL} + condition false → transition run to
 *         CANCELLED with reason {@code CONDITION_REJECT_POLICY}. No further
 *         AGENT calls.</li>
 *     <li>{@code on_reject=CANCEL} + condition TRUE → no policy fires; run
 *         executes target + tail normally. (Pin: CANCEL is gated on the false
 *         path, never the truthy path.)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowConditionOnRejectRuntimeTest extends BaseIntegrationTest {

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
    void onRejectSkip_conditionFalse_skipsTarget_runCompletes() {
        HttpHeaders auth = newCaller("skip-false");
        String workflowId = createWorkflow(auth, "OnReject SKIP false");
        String tgt = seedAgent("skip-tgt");
        String tail = seedAgent("skip-tail");

        // contains:go evaluates FALSE against input "halt" → SKIP applies.
        insertConditionStep(workflowId, 1, "contains:go", "SKIP");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-skip-false");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-skip-false", "halt");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "SKIP policy on false condition must skip target; got: " + runner.calls);
    }

    @Test
    void onRejectCancel_conditionFalse_cancelsRun_noFurtherAgentCalls() {
        HttpHeaders auth = newCaller("cancel-false");
        String workflowId = createWorkflow(auth, "OnReject CANCEL false");
        String tgt = seedAgent("cancel-tgt");
        String tail = seedAgent("cancel-tail");

        insertConditionStep(workflowId, 1, "contains:go", "CANCEL");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-cancel-false");

        runWorkflow(auth, workflowId, "sess-cancel-false", "halt");
        awaitWorkflowStatus(workflowId, "CANCELLED");
        assertEquals(0, runner.calls.size(),
                "CANCEL policy on false condition must short-circuit before any AGENT step; got: "
                        + runner.calls);
    }

    @Test
    void onRejectCancel_conditionTrue_noPolicyFires_runProceedsNormally() {
        HttpHeaders auth = newCaller("cancel-true");
        String workflowId = createWorkflow(auth, "OnReject CANCEL true");
        String tgt = seedAgent("cancel-true-tgt");
        String tail = seedAgent("cancel-true-tail");

        // contains:go evaluates TRUE against input "let's go" → on_reject NOT applied.
        insertConditionStep(workflowId, 1, "contains:go", "CANCEL");
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-cancel-true");
        runner.scriptResponse("tgt-output");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-cancel-true", "let's go");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "CANCEL policy must NOT fire on a truthy condition; got: " + runner.calls);
    }

    @Test
    void onRejectNull_treatedAsSkip_preservesPreDr6Default() {
        HttpHeaders auth = newCaller("null-policy");
        String workflowId = createWorkflow(auth, "OnReject NULL");
        String tgt = seedAgent("null-tgt");
        String tail = seedAgent("null-tail");

        insertConditionStep(workflowId, 1, "contains:go", null);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-null-policy");
        runner.scriptResponse("tail-output");

        runWorkflow(auth, workflowId, "sess-null-policy", "halt");
        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(),
                "null on_reject must default to SKIP — preserves the pre-DR-6 dispatcher behavior; "
                        + "got: " + runner.calls);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "on-reject-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-onrej-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "OnReject agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'on-reject-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression, String onReject) {
        // CONDITION step stores expression in agent_id (existing convention); FK requires
        // a placeholder agents row.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'cond-expr-placeholder', 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, expression);
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, on_reject) "
                        + "VALUES (?, ?, ?, ?, 'CONDITION', ?)",
                UUID.randomUUID().toString(), workflowId, order, expression, onReject);
    }

    private void insertAgentStep(String workflowId, int order, String agentId) {
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) VALUES (?, ?, ?, ?, 'AGENT')",
                UUID.randomUUID().toString(), workflowId, order, agentId);
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
