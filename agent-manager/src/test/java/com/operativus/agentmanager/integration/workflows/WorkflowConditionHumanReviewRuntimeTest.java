package com.operativus.agentmanager.integration.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import com.operativus.agentmanager.control.service.HumanReviewService;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.enums.HumanReviewDecision;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: REQ-HR-3 — pins the CONDITION dispatcher's new
 *   HumanReview-based pause path end-to-end. When {@code step.humanReview}
 *   is set with an active pause mode, the dispatcher routes through
 *   {@link HumanReviewService} instead of the legacy
 *   {@code requires_confirmation} path.
 *
 *   <p>Workflow shape: 3 steps — [CONDITION (humanReview attached) →
 *   AGENT (target) → AGENT (tail)]. The CONDITION evaluates {@code contains:go}
 *   against the workflow input.
 *
 *   <ul>
 *     <li>condition TRUE + approve → run resumes through target + tail.</li>
 *     <li>condition FALSE + on_reject=SKIP + approve → run resumes past
 *         target (planned cursor pre-set at pause time); tail runs.</li>
 *     <li>condition TRUE + reject → run flips to CANCELLED with reason
 *         {@code HUMAN_REVIEW_REJECTED_BY_OPERATOR}.</li>
 *   </ul>
 *
 *   <p>The legacy {@code requires_confirmation} path is unchanged — covered
 *   by {@code WorkflowConditionRequiresConfirmationRuntimeTest}. This test
 *   pins ONLY the new humanReview path; both coexist during the migration
 *   window.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class, JobQueueTestSupport.class})
public class WorkflowConditionHumanReviewRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private RecordingAgentOperations runner;
    @Autowired private HumanReviewService humanReviewService;
    @Autowired private HumanReviewPendingRepository pendingRepository;
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
    void conditionTrue_humanReviewApprove_resumesThroughTargetAndTail() {
        HttpHeaders auth = newCaller("hr-true-approve");
        String workflowId = createWorkflow(auth, "HR true approve");
        String tgt = seedAgent("hr-tgt");
        String tail = seedAgent("hr-tail");

        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-hr-true");
        runner.scriptResponse("tgt-out");
        runner.scriptResponse("tail-out");

        runWorkflow(auth, workflowId, "sess-hr-true", "let's go");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");

        HumanReviewPending pending = awaitPending(workflowId);
        assertEquals("WORKFLOW_STEP", pending.getSubjectType());

        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.APPROVE, null, "alice");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        assertEquals(2, runner.calls.size(),
                "approve must resume through target + tail; got: " + runner.calls);
    }

    @Test
    void conditionFalse_skipPolicy_humanReviewApprove_skipsTargetAndRunsTail() {
        HttpHeaders auth = newCaller("hr-false-skip");
        String workflowId = createWorkflow(auth, "HR false SKIP approve");
        String tgt = seedAgent("hr-skip-tgt");
        String tail = seedAgent("hr-skip-tail");

        HumanReview hr = new HumanReview(true, null, null,
                com.operativus.agentmanager.core.model.enums.OnRejectPolicy.SKIP,
                null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-hr-skip");
        runner.scriptResponse("tail-out");

        runWorkflow(auth, workflowId, "sess-hr-skip", "halt");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");
        HumanReviewPending pending = awaitPending(workflowId);

        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.APPROVE, null, "alice");

        awaitWorkflowStatus(workflowId, "COMPLETED");
        // SKIP policy + false → planned cursor skips target; resume runs only tail.
        assertEquals(1, runner.calls.size(),
                "SKIP policy approve must run tail only (target skipped); got: " + runner.calls);
        assertEquals(tail, runner.calls.get(0).agentId());
    }

    @Test
    void conditionTrue_humanReviewReject_cancelsRun() {
        HttpHeaders auth = newCaller("hr-true-reject");
        String workflowId = createWorkflow(auth, "HR true reject");
        String tgt = seedAgent("hr-rej-tgt");
        String tail = seedAgent("hr-rej-tail");

        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-hr-reject");

        runWorkflow(auth, workflowId, "sess-hr-reject", "let's go");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");
        HumanReviewPending pending = awaitPending(workflowId);

        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.REJECT, null, "alice");

        awaitWorkflowStatus(workflowId, "CANCELLED");
        assertEquals(0, runner.calls.size(),
                "reject must short-circuit; no agent calls; got: " + runner.calls);
    }

    @Test
    void pendingRow_carriesPlannedCursorInOptions() {
        // Pin the contract: the dispatcher must record plannedCursor in
        // pending.options so the operator/audit can see what the resume will do.
        HttpHeaders auth = newCaller("hr-plan");
        String workflowId = createWorkflow(auth, "HR planned cursor");
        String tgt = seedAgent("hr-plan-tgt");
        String tail = seedAgent("hr-plan-tail");

        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        insertConditionStep(workflowId, 1, "contains:go", hr);
        insertAgentStep(workflowId, 2, tgt);
        insertAgentStep(workflowId, 3, tail);

        seedSession("sess-hr-plan");
        runWorkflow(auth, workflowId, "sess-hr-plan", "halt");
        awaitWorkflowStatus(workflowId, "AWAITING_HUMAN_REVIEW");

        HumanReviewPending pending = awaitPending(workflowId);
        assertNotNull(pending.getOptions());
        assertTrue(pending.getOptions().containsKey("plannedCursor"),
                "options must carry plannedCursor; got keys: " + pending.getOptions().keySet());
        assertTrue(pending.getOptions().containsKey("conditionMet"),
                "options must carry conditionMet for operator visibility");

        // Settle the run so @AfterEach truncate doesn't dangle.
        humanReviewService.decide(pending.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.REJECT, null, "cleanup");
        awaitWorkflowStatus(workflowId, "CANCELLED");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "hr-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-hr-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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
                """, id, "HR agent " + label);
        return id;
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'hr-user', 'DEFAULT_SYSTEM_ORG', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private void insertConditionStep(String workflowId, int order, String expression, HumanReview hr) {
        // CONDITION expression stored in agent_id; needs placeholder row for FK.
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

    private HumanReviewPending awaitPending(String workflowId) {
        Awaitility.await("human_review_pending row for workflow " + workflowId)
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> pendingRepository.findAll().stream()
                        .anyMatch(p -> p.getRunId() != null));
        // Find the pending row for the most recent run on this workflow.
        String runId = jdbc.queryForObject(
                "SELECT id FROM workflow_runs WHERE workflow_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, workflowId);
        return pendingRepository.findByRunIdOrderByCreatedAtDesc(runId).stream()
                .filter(p -> p.getDecision() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no undecided pending row for runId=" + runId));
    }
}
