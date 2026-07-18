package com.operativus.agentmanager.integration.approvals;

import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pin the approval ↔ workflow_run bridge in
 *   {@code ApprovalService.resolveApprovalForOrg}. When a resolved approval row has a
 *   non-null {@code workflow_run_id}, the resolve code path spawns a virtual thread
 *   that calls into {@code WorkflowService}:
 *   <ul>
 *     <li>{@code decision = APPROVED} → {@code resumeWorkflowRun(workflowRunId, …)} —
 *         sets workflow_run.status from PAUSED to RUNNING and schedules step advancement.</li>
 *     <li>{@code decision = REJECTED} → {@code cancelWorkflowRun(workflowRunId, reason)} —
 *         sets workflow_run.status to CANCELLED (idempotent on already-terminal rows).</li>
 *   </ul>
 *   {@code WorkflowHitlAutoPauseRuntimeTest} pins the create-side (workflow pauses via
 *   PAUSED_SENTINEL → approval row is created with workflow_run_id). This test pins the
 *   resolve-side: the approval resolution propagates back into the workflow_run state.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Why this gap matters: PR #645's WorkflowRejectionCascadeRuntimeTest covers reject
 * propagation under specific conditions (Tier 2.4 PR 7 F-A); this test adds the
 * symmetric approve path AND a regression-lock that an approval WITHOUT a workflow_run_id
 * does NOT mutate any workflow_run row (the bridge must be conditional).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        ApprovalsWorkflowStepRuntimeTest.StubAgentOpsConfig.class})
public class ApprovalsWorkflowStepRuntimeTest extends BaseIntegrationTest {

    /**
     * The workflow bridge in {@code ApprovalService.resolveApprovalForOrg} sits AFTER
     * {@code agentOperations.continueRun(saved.getRunId(), decision.name())} inside the
     * same try-block. If continueRun throws (which the real AgentService does when
     * agent_runs has no matching row in PAUSED state with the surrounding chat-model
     * machinery wired up), the catch absorbs the exception and the workflow bridge is
     * silently skipped. To exercise the bridge in isolation, we stub continueRun to
     * return a benign success response. The stub overrides ALL other AgentOperations
     * methods to throw — any unexpected call site fails loudly so the stub's footprint
     * stays tight.
     */
    @TestConfiguration
    static class StubAgentOpsConfig {
        @Bean @Primary
        AgentOperations stubAgentOperations() {
            return new StubAgentOperations();
        }
    }

    static class StubAgentOperations implements AgentOperations {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public RunResponse continueRun(String runId, String action) {
            return new RunResponse(
                    runId,
                    "stub-session-" + seq.incrementAndGet(),
                    "stubbed continueRun output for action " + action,
                    new HashMap<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    RunStatus.COMPLETED,
                    null);
        }

        @Override public RunResponse run(String a, String u, String s) { throw unsupported("run"); }
        @Override public RunResponse run(String a, String u, List<Media> m, String s, String uid, String oid, Boolean g, RunOptions o) { throw unsupported("run-8arg"); }
        @Override public Flux<AgentStreamEvent> stream(String a, String u, List<Media> m, String s, String uid, String oid, Boolean g, RunOptions o) { throw unsupported("stream"); }
        @Override public String runInBackground(String a, String u, List<Media> m, String s, String uid, String oid, Boolean g, RunOptions o) { throw unsupported("runInBackground-8arg"); }
        @Override public String runInBackground(String a, String u, String s) { throw unsupported("runInBackground-3arg"); }
        @Override public String runPlayground(String a, String u, String s) { throw unsupported("runPlayground"); }
        @Override public void cancelRun(String runId) { throw unsupported("cancelRun"); }
        @Override public void loadKnowledge(String agentId) { throw unsupported("loadKnowledge"); }

        private static UnsupportedOperationException unsupported(String m) {
            return new UnsupportedOperationException("StubAgentOperations does not implement " + m);
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P2.2-1 — Approve a workflow-bound approval. The bridge MUST resume the
    // workflow_run (status flips away from PAUSED). The downstream step advancement
    // runs on a virtual thread and may further transition the state; the bridge
    // contract pinned here is "status != PAUSED after resolve" — i.e., resumeWorkflowRun
    // fired and at minimum flipped to RUNNING.
    @Test
    void pausedWorkflowRun_approvalResolvedApproved_workflowRunTransitionsAwayFromPaused() {
        String orgId = "org-wf-step-approve";
        HttpHeaders auth = registerLoginWithOrg("wf-step-approve", orgId);

        WorkflowFx fx = seedPausedWorkflowRun("wf-approve", orgId);
        String approvalId = seedApprovalWithWorkflowRunId("wf-approve", orgId, fx);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "resolve must return 200 even though the workflow bridge fires asynchronously");

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertNotEquals("PAUSED", jdbc.queryForObject(
                        "SELECT status FROM workflow_runs WHERE id = ?", String.class, fx.workflowRunId),
                        "resumeWorkflowRun must have fired — workflow_run.status must flip away from PAUSED. "
                                + "If still PAUSED after 10s, the bridge in ApprovalService.resolveApprovalForOrg "
                                + "did not invoke WorkflowService.resumeWorkflowRun"));

        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId),
                "approval row itself must be APPROVED post-resolve");
    }

    // P2.2-2 — Reject a workflow-bound approval. The bridge MUST cancel the
    // workflow_run with a reason that references the approval id (per
    // ApprovalService.resolveApprovalForOrg's cancellation message format:
    // "Cancelled: HITL approval {id} was REJECTED.").
    @Test
    void pausedWorkflowRun_approvalResolvedRejected_workflowRunCancelled() {
        String orgId = "org-wf-step-reject";
        HttpHeaders auth = registerLoginWithOrg("wf-step-reject", orgId);

        WorkflowFx fx = seedPausedWorkflowRun("wf-reject", orgId);
        String approvalId = seedApprovalWithWorkflowRunId("wf-reject", orgId, fx);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "REJECTED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals("CANCELLED", jdbc.queryForObject(
                        "SELECT status FROM workflow_runs WHERE id = ?", String.class, fx.workflowRunId),
                        "REJECTED approval must cascade to workflow_run.status=CANCELLED — "
                                + "ApprovalService spawns a VT calling WorkflowService.cancelWorkflowRun"));

        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId));
    }

    // P2.2-3 — Bridge must be CONDITIONAL on a non-null workflow_run_id. An approval
    // created via a non-workflow code path (direct agent run, no workflow context)
    // resolves cleanly without touching any workflow_run row. Pinning this prevents a
    // future regression that always calls into WorkflowService and would either fail
    // loudly (workflowRunId=null → IllegalArgumentException) or silently mutate the
    // wrong row.
    @Test
    void approvalWithoutWorkflowRunId_resolvedApproved_doesNotMutateAnyWorkflowRun() {
        String orgId = "org-wf-no-bridge";
        HttpHeaders auth = registerLoginWithOrg("wf-no-bridge", orgId);

        // Seed an unrelated PAUSED workflow_run that must NOT be touched.
        WorkflowFx untouched = seedPausedWorkflowRun("wf-untouched", orgId);

        // Seed an approval with workflow_run_id = NULL — the non-workflow code path.
        String approvalId = seedApprovalWithoutWorkflowRunId("wf-no-bridge", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        // Wait a beat to give any (incorrect) async bridge a chance to fire.
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        assertAll("no workflow_run mutation when bridge has no target",
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, approvalId),
                        "approval still resolves normally"),
                () -> assertEquals("PAUSED", jdbc.queryForObject(
                        "SELECT status FROM workflow_runs WHERE id = ?", String.class, untouched.workflowRunId),
                        "unrelated workflow_run must remain PAUSED — bridge must be conditional on workflow_run_id"));
    }

    // ─── helpers ───

    private record WorkflowFx(String workflowId, String workflowRunId, String sessionId, String agentId) {}

    private WorkflowFx seedPausedWorkflowRun(String label, String orgId) {
        String workflowId = "wf-" + label + "-" + UUID.randomUUID();
        String workflowRunId = "wfrun-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String agentId = "agent-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO workflows (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, workflowId, "WF Step Test " + label, "test fixture");
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "WF Step Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status, current_step_order,
                                            org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'PAUSED', 1, ?, now(), now())
                """, workflowRunId, workflowId, sessionId, orgId);

        return new WorkflowFx(workflowId, workflowRunId, sessionId, agentId);
    }

    private String seedApprovalWithWorkflowRunId(String label, String orgId, WorkflowFx fx) {
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, workflow_run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 'wf-step-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, fx.workflowRunId, fx.sessionId, fx.agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);
        return approvalId;
    }

    private String seedApprovalWithoutWorkflowRunId(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "No-bridge Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'no-bridge-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);
        return approvalId;
    }
}
