package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins {@code DELETE /api/v1/workflows/runs/&#123;runId&#125;} —
 *   the user-facing cancel endpoint for workflow runs. Service method
 *   {@link ai.operativus.agentmanager.control.service.WorkflowService#cancelWorkflowRun}
 *   has existed (used by sweepers) but was never exposed via REST before this PR.
 *
 *   <p>Contract:
 *   <ul>
 *     <li>Unknown runId -> 404 (NOT pass-through — cancel is destructive, fail loudly)</li>
 *     <li>Cross-tenant runId -> 404 (existence-leak protection)</li>
 *     <li>Terminal rows (COMPLETED / FAILED / CANCELLED) -> 204 idempotent no-op</li>
 *     <li>PAUSED -> 204, status flips to CANCELLED</li>
 *     <li>RUNNING -> 204, status flips to CANCELLED (in-flight VT may continue; finalizer
 *         guards against overwrite)</li>
 *   </ul>
 *
 *   <p>Mid-flight VT interruption is deliberately deferred — same pattern as the agent-side
 *   cancellation contract.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowRunCancellationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void cancelPausedRun_returns204_flipsStatusToCancelled() {
        String orgId = "org-cancel-paused-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("cancel-paused", orgId);
        String workflowId = createWorkflow(auth, "Cancel Paused");
        String runId = seedRun(workflowId, orgId, "PAUSED");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        assertAll("PAUSED -> CANCELLED via DELETE",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                        "successful cancel must return 204"),
                () -> assertEquals("CANCELLED", statusOf(runId),
                        "status must flip to CANCELLED"));
    }

    @Test
    void cancelRunningRun_returns204_flipsStatusToCancelled() {
        String orgId = "org-cancel-running-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("cancel-running", orgId);
        String workflowId = createWorkflow(auth, "Cancel Running");
        String runId = seedRun(workflowId, orgId, "RUNNING");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertEquals("CANCELLED", statusOf(runId),
                "RUNNING workflow_run must flip to CANCELLED; the in-flight VT continuing "
                        + "to completion is a separate concern handled by the finalizer's "
                        + "status guard");
    }

    @Test
    void cancelAlreadyTerminalRun_returns204_idempotent_statusPreserved() {
        String orgId = "org-cancel-terminal-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("cancel-terminal", orgId);
        String workflowId = createWorkflow(auth, "Cancel Terminal");

        for (String terminalStatus : new String[]{"COMPLETED", "FAILED", "CANCELLED"}) {
            String runId = seedRun(workflowId, orgId, terminalStatus);

            ResponseEntity<Void> resp = rest.exchange(
                    url("/api/v1/workflows/runs/" + runId),
                    HttpMethod.DELETE,
                    new HttpEntity<>(auth),
                    Void.class);

            assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                    "cancel on " + terminalStatus + " must be idempotent (204 no-op)");
            assertEquals(terminalStatus, statusOf(runId),
                    "terminal status " + terminalStatus + " must be preserved — cancel must "
                            + "NOT overwrite a terminal row");
        }
    }

    @Test
    void cancelUnknownRunId_returns404_failsLoudly() {
        String orgId = "org-cancel-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("cancel-404", orgId);
        String unknownId = UUID.randomUUID().toString();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + unknownId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cancel on unknown runId must return 404 — NOT pass-through. Cancel is "
                        + "destructive; a silent success would leave clients believing "
                        + "they cancelled when they didn't.");
    }

    @Test
    void cancelCrossTenantRun_returns404_ownerRowUnmodified() {
        String orgA = "org-cancel-A-" + UUID.randomUUID();
        String orgB = "org-cancel-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("cancel-a", orgA);
        registerLoginWithOrg("cancel-b", orgB);

        String workflowId = createWorkflow(authA, "Org A workflow"); // Just to satisfy FK setup
        // Seed run owned by org B.
        String runId = seedRunForOrgB(workflowId, orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant cancel must return 404 — existence-leak protection");
        assertEquals("PAUSED", statusOf(runId),
                "owner's row must be unmodified after cross-tenant cancel attempt");
    }

    @Test
    void doubleCancel_secondCallIsIdempotentNoOp() {
        String orgId = "org-cancel-double-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("cancel-double", orgId);
        String workflowId = createWorkflow(auth, "Double Cancel");
        String runId = seedRun(workflowId, orgId, "PAUSED");

        ResponseEntity<Void> first = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode());
        assertEquals("CANCELLED", statusOf(runId));

        ResponseEntity<Void> second = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, second.getStatusCode(),
                "second cancel on already-CANCELLED row must be idempotent 204");
        assertEquals("CANCELLED", statusOf(runId),
                "status must remain CANCELLED — no resurrection");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture: workflow must be created; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedRun(String workflowId, String orgId, String status) {
        String runId = "run-cancel-" + UUID.randomUUID();
        String sessionId = "sess-cancel-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'cancel-user', ?, now(), now())
                """, sessionId, orgId);
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload, org_id,
                                           created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, 'fixture-payload', ?, now(), now())
                """, runId, workflowId, sessionId, status, orgId);
        return runId;
    }

    /**
     * Seeds a workflow_runs row stamped with org B's orgId — bypasses the createWorkflow
     * fixture which would stamp the caller's orgId. Workflow_id FK points at the workflow
     * the test already created under whatever org; tenant scoping is enforced on the
     * RUN, not the parent workflow, by the controller's lookup.
     */
    private String seedRunForOrgB(String workflowId, String orgB) {
        String runId = "run-cancel-cross-" + UUID.randomUUID();
        String sessionId = "sess-cancel-cross-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'cancel-cross-user', ?, now(), now())
                """, sessionId, orgB);
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload, org_id,
                                           created_at, updated_at)
                VALUES (?, ?, ?, 'PAUSED', 0, 'fixture-payload-cross', ?, now(), now())
                """, runId, workflowId, sessionId, orgB);
        return runId;
    }

    private String statusOf(String runId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
    }
}
