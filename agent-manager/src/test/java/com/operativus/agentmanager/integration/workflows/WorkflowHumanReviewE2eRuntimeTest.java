package com.operativus.agentmanager.integration.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: REQ-HR-6 — comprehensive end-to-end coverage of the
 *   unified HumanReview pause+decide cycle via the REST surface. Drives the
 *   full path:
 *
 *   <pre>
 *     1. CONDITION step with humanReview attached → workflow run
 *     2. Dispatcher hits CONDITION → pauseFor() creates human_review_pending row
 *     3. workflow_run.status = AWAITING_HUMAN_REVIEW
 *     4. Operator POSTs /api/v1/approvals/{pendingId}/decide
 *     5. HumanReviewService.decide settles row, dispatches WorkflowStepResumeHandler
 *     6. Handler calls resumeWorkflowRun (approve) or cancelWorkflowRun (reject)
 *     7. workflow_run reaches terminal state (COMPLETED or CANCELLED)
 *   </pre>
 *
 *   <p>Sibling tests cover sub-paths:
 *   <ul>
 *     <li>{@code WorkflowConditionHumanReviewRuntimeTest} — service-level pause+decide</li>
 *     <li>{@code HumanReviewDecideEndpointRuntimeTest} — endpoint authz + shape</li>
 *   </ul>
 *
 *   <p>This test exercises the FULL REST→service→handler→resume loop in a
 *   single flow, matrix-style over decision × on_reject policy.
 *
 *   <p>AGENT_TOOL_CALL E2E deferred to post-REQ-HR-4.5 (HitlAdvisor wiring is
 *   not yet shipped — see {@code AgentToolResumeHandler} class Javadoc).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowHumanReviewE2eRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;
    @Autowired private ObjectMapper jacksonMapper;

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
    void e2e_conditionTrue_approveViaRestEndpoint_runCompletes() {
        HttpHeaders auth = newCaller("e2e-true-approve");
        String workflowId = createWorkflow(auth, "E2E true+approve");
        String tgt = seedAgent("e2e-tgt-a");
        String tail = seedAgent("e2e-tail-a");

        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-e2e-1");
        runner.scriptResponse("tgt-out");
        runner.scriptResponse("tail-out");

        runWorkflow(auth, workflowId, "sess-e2e-1", "let's go");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");

        String pendingId = awaitPendingId(workflowId);
        ResponseEntity<Map<String, Object>> decide = decideViaRest(auth, pendingId, "approve");
        assertEquals(HttpStatus.OK, decide.getStatusCode());
        assertEquals("APPROVE", decide.getBody().get("decision"));
        assertEquals("WORKFLOW_STEP", decide.getBody().get("subjectType"));

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size());
    }

    @Test
    void e2e_conditionFalse_skipPolicy_approve_skipsTarget() {
        HttpHeaders auth = newCaller("e2e-false-skip");
        String workflowId = createWorkflow(auth, "E2E false+SKIP+approve");
        String tgt = seedAgent("e2e-tgt-b");
        String tail = seedAgent("e2e-tail-b");

        HumanReview hr = new HumanReview(true, null, null, OnRejectPolicy.SKIP,
                null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-e2e-2");
        runner.scriptResponse("tail-out");

        runWorkflow(auth, workflowId, "sess-e2e-2", "halt");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");
        decideViaRest(auth, awaitPendingId(workflowId), "approve");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(1, runner.calls.size(), "SKIP policy approve must skip target");
        assertEquals(tail, runner.calls.get(0).agentId());
    }

    // NOTE: CANCEL policy + pause-mode + approve is intentionally NOT tested.
    // Semantic is ambiguous (does "approve" mean "yes, cancel" or "no, override
    // and continue"?) and the legacy DR-6 PR-4 validator rejected the combo
    // (requires_confirmation + on_reject=CANCEL). REQ-HR-1's HumanReviewValidator
    // does not yet mirror that rejection — gap to fix in REQ-HR-4.5 or a follow-up
    // that decides the semantic explicitly. For now operators are expected to use
    // either CANCEL (no pause) or non-CANCEL+pause combinations.

    @Test
    void e2e_anyDecision_reject_runCancels() {
        // Reject path is uniform across on_reject policies — handler routes to
        // cancelWorkflowRun regardless of which branch would have fired on approve.
        HttpHeaders auth = newCaller("e2e-reject");
        String workflowId = createWorkflow(auth, "E2E reject");
        String tgt = seedAgent("e2e-rej-tgt");
        String tail = seedAgent("e2e-rej-tail");

        HumanReview hr = new HumanReview(true, null, null, OnRejectPolicy.SKIP,
                null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-e2e-rej");

        runWorkflow(auth, workflowId, "sess-e2e-rej", "let's go");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");

        ResponseEntity<Map<String, Object>> decide =
                decideViaRest(auth, awaitPendingId(workflowId), "reject");
        assertEquals("REJECT", decide.getBody().get("decision"));

        awaitWorkflowStatus(workflowId, "CANCELLED");
        assertEquals(0, runner.calls.size());
    }

    @Test
    void e2e_decideEndpoint_idempotent_secondCallEchoesFirstDecision() {
        // The unified surface guarantees idempotent /decide calls. Two operators
        // racing on the same approval get a consistent answer.
        HttpHeaders auth = newCaller("e2e-idem");
        String workflowId = createWorkflow(auth, "E2E idempotent");
        String tgt = seedAgent("e2e-idem-tgt");
        String tail = seedAgent("e2e-idem-tail");

        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-e2e-idem");
        runner.scriptResponse("tgt-out");
        runner.scriptResponse("tail-out");

        runWorkflow(auth, workflowId, "sess-e2e-idem", "let's go");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");
        String pendingId = awaitPendingId(workflowId);

        ResponseEntity<Map<String, Object>> first = decideViaRest(auth, pendingId, "approve");
        ResponseEntity<Map<String, Object>> second = decideViaRest(auth, pendingId, "reject");

        assertEquals("APPROVE", first.getBody().get("decision"));
        assertEquals("APPROVE", second.getBody().get("decision"),
                "second call must echo first decision (idempotent), NOT overwrite");

        awaitWorkflowStatus(workflowId, "COMPLETED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "e2e-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-e2e-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "E2E HR agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'e2e-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression, HumanReview hr) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'cond-expr-placeholder', 'gpt-4o-mini', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, expression);
        String json;
        try {
            json = jacksonMapper.writeValueAsString(hr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize HumanReview", e);
        }
        jdbc.update(
                "INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, human_review) "
                        + "VALUES (?, ?, ?, ?, 'CONDITION', ?::jsonb)",
                UUID.randomUUID().toString(), workflowId, order, expression, json);
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

    private ResponseEntity<Map<String, Object>> decideViaRest(HttpHeaders auth, String pendingId, String decision) {
        return rest.exchange(
                url("/api/v1/approvals/" + pendingId + "/decide"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision), auth),
                JSON_MAP);
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

    private String awaitPendingId(String workflowId) {
        Awaitility.await("human_review_pending row created for workflow " + workflowId)
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM human_review_pending p "
                                    + "JOIN workflow_runs r ON r.id = p.run_id "
                                    + "WHERE r.workflow_id = ? AND p.decision IS NULL",
                            Integer.class, workflowId);
                    return n != null && n >= 1;
                });
        String pendingId = jdbc.queryForObject(
                "SELECT p.id FROM human_review_pending p "
                        + "JOIN workflow_runs r ON r.id = p.run_id "
                        + "WHERE r.workflow_id = ? AND p.decision IS NULL "
                        + "ORDER BY p.created_at DESC LIMIT 1",
                String.class, workflowId);
        assertNotNull(pendingId, "expected undecided pending row");
        return pendingId;
    }
}
