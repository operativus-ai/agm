package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Regression-lock for the workflow-bridge-fires-on-continueRun-failure
 *   fix. Pre-fix, {@code ApprovalService.resolveApprovalForOrg}'s spawned VT had the
 *   workflow_run cascade inside the SAME try-block as {@code agentOperations.continueRun}.
 *   When continueRun threw (transient DB error, missing agent_run row, etc.), the catch
 *   silently absorbed the exception and the workflow_run cascade was SKIPPED — leaving
 *   the workflow_run stuck in PAUSED forever with no automatic recovery.
 *   Post-fix, the cascade lives in its OWN try-block and fires regardless of step-1
 *   outcome. {@link ApprovalsWorkflowStepRuntimeTest} pins the happy path (continueRun
 *   succeeds); this test pins the failure path (continueRun throws → cascade still fires).
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Test fixture drives the failure deliberately via a {@code @Primary} stub
 * {@code AgentOperations.continueRun} that throws. Pre-fix this test would fail with
 * {@code workflow_run.status} stuck at PAUSED; post-fix the cascade fires and we see
 * CANCELLED (REJECTED path) or transition-away-from-PAUSED (APPROVED path).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        ApprovalsWorkflowBridgeContinueRunFailureRuntimeTest.ThrowingContinueRunConfig.class})
public class ApprovalsWorkflowBridgeContinueRunFailureRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @TestConfiguration
    static class ThrowingContinueRunConfig {
        @Bean @Primary
        AgentOperations throwingAgentOperations() {
            return new ThrowingAgentOperations();
        }
    }

    static class ThrowingAgentOperations implements AgentOperations {
        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new RuntimeException("simulated continueRun failure for runId=" + runId);
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
            return new UnsupportedOperationException("ThrowingAgentOperations does not implement " + m);
        }
    }

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // FIX-C-1 — REJECTED cascade fires even when continueRun throws. Pre-fix the
    // workflow_run would stay PAUSED; post-fix it transitions to CANCELLED. This is the
    // load-bearing case — REJECTED approvals must always cascade because there is no
    // alternate recovery path that flips a PAUSED workflow_run away from PAUSED.
    @Test
    void continueRunThrows_rejectedApproval_workflowRunStillCancelled() {
        String orgId = "org-bridge-fail-reject";
        HttpHeaders auth = registerLoginWithOrg("bridge-fail-reject", orgId);

        WorkflowFx fx = seedPausedWorkflowRun("bridge-reject", orgId);
        String approvalId = seedApprovalWithWorkflowRunId("bridge-reject", orgId, fx);

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
                        "Pre-fix: workflow_run stuck PAUSED because the REJECTED cascade lived inside "
                                + "the same try-block as continueRun. Post-fix: cascade fires in its own "
                                + "try-block regardless of continueRun outcome."));

        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId),
                "approval row itself flips to REJECTED — the resolve operation itself is independent of the cascade");
    }

    // FIX-C-2 — APPROVED cascade fires even when continueRun throws. Post-fix the
    // resumeWorkflowRun call gets a null content (because response was null due to
    // continueRun failure) but still flips workflow_run.status away from PAUSED so the
    // operator can investigate / retry without manual DB intervention.
    @Test
    void continueRunThrows_approvedApproval_workflowRunStillTransitionsAwayFromPaused() {
        String orgId = "org-bridge-fail-approve";
        HttpHeaders auth = registerLoginWithOrg("bridge-fail-approve", orgId);

        WorkflowFx fx = seedPausedWorkflowRun("bridge-approve", orgId);
        String approvalId = seedApprovalWithWorkflowRunId("bridge-approve", orgId, fx);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM workflow_runs WHERE id = ?", String.class, fx.workflowRunId);
                    if ("PAUSED".equals(status)) {
                        throw new AssertionError(
                                "Pre-fix: workflow_run stuck PAUSED because the APPROVED cascade was "
                                        + "skipped when continueRun threw. Post-fix: resumeWorkflowRun fires "
                                        + "with null content and flips status to RUNNING.");
                    }
                });

        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, approvalId));
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
                """, workflowId, "Bridge-Fail Test " + label, "test fixture");
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Bridge-Fail Agent " + label);
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
                VALUES (?, ?, ?, ?, ?, 'PENDING', 'bridge-fail-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, fx.workflowRunId, fx.sessionId, fx.agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);
        return approvalId;
    }
}
