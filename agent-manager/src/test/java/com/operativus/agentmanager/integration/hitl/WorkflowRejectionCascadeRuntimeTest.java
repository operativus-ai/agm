package com.operativus.agentmanager.integration.hitl;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Black-box runtime coverage of the Tier 2.4 PR 7 F-A
 *   workflow_run REJECTED cascade — pre-fix, ApprovalService logged "halted" on
 *   REJECTED but never cancelled the workflow_run row, leaving it stuck in
 *   PAUSED forever. Post-fix, the REJECTED branch invokes
 *   {@code WorkflowService.cancelWorkflowRun} which transitions the row to
 *   CANCELLED idempotently.
 *
 *   This test seeds the workflow_run + approval rows directly via JDBC and exercises
 *   the {@code POST /api/v1/approvals/{id}/resolve} endpoint with REJECTED. The
 *   cascade runs in a fire-and-forget Virtual Thread, so the assertion uses
 *   Awaitility to poll for the workflow_run state transition.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowRejectionCascadeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    @Test
    void rejectedHitlApproval_cascadesCancelToWorkflowRun() {
        String orgId = "org-cascade";
        String username = "cascade-user";
        HttpHeaders auth = registerLoginWithOrg(username, orgId);

        String agentId = "agent-cascade-" + UUID.randomUUID();
        String workflowId = "workflow-cascade-" + UUID.randomUUID();
        String workflowRunId = "wr-cascade-" + UUID.randomUUID();
        String runId = "run-cascade-" + UUID.randomUUID();
        String approvalId = "approval-cascade-" + UUID.randomUUID();
        String sessionId = "session-cascade-" + UUID.randomUUID();

        // Seed agent + workflow + workflow_run + session + approval. The agent_run row is
        // not strictly necessary for this assertion (we only verify workflow_run cascade);
        // the resume VT may log an error finding the run, but that's harmless for this
        // contract — the cascade runs alongside the resume call.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Agent " + agentId, orgId);

        jdbc.update("""
                INSERT INTO workflows (id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, workflowId, "Workflow " + workflowId, "test workflow");

        // agent_sessions must precede workflow_runs (FK fk_workflow_runs_session).
        // agent_sessions must precede workflow_runs (FK fk_workflow_runs_session).
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, username, orgId, agentId);

        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, org_id, status, current_step_order,
                                           created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PAUSED', 0, now(), now())
                """, workflowRunId, workflowId, sessionId, orgId);

        // agent_run row must exist — the resume VT calls agentOperations.continueRun(runId)
        // and surfaces ResourceNotFoundException otherwise, which swallows the cascade
        // before reaching the REJECTED branch. Seed it as PAUSED to match the real shape.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status,
                                        input, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'PAUSED', 'workflow input', now(), now(), 0)
                """, runId, agentId, sessionId, username, orgId);

        jdbc.update("""
                INSERT INTO approvals (id, run_id, workflow_run_id, session_id, agent_id, status,
                                       tool_name, tool_arguments, requested_by, decision_tier,
                                       org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 'delete_database',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now())
                """, approvalId, runId, workflowRunId, sessionId, agentId,
                "{\"db\":\"prod\"}", username, orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "REJECTED"), auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "resolve must return 200; cascade runs in a fire-and-forget VT post-response");

        // Poll for the cascade to land. The VT is fire-and-forget; under load this could
        // take a beat. Awaitility tolerates the latency without sleeping the test deterministic-time.
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            String workflowRunStatus = jdbc.queryForObject(
                    "SELECT status FROM workflow_runs WHERE id = ?", String.class, workflowRunId);
            assertEquals("CANCELLED", workflowRunStatus,
                    "workflow_run must transition to CANCELLED via the F-A cascade — anything else means "
                            + "ApprovalService.resolveApprovalForOrg's REJECTED branch did not invoke "
                            + "WorkflowService.cancelWorkflowRun");
        });
    }
}
