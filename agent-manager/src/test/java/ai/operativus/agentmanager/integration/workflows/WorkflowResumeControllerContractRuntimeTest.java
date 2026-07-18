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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Controller-contract runtime coverage for
 *   {@code POST /api/v1/workflows/runs/&#123;runId&#125;/resume}. Pins the boundary behavior
 *   the controller relies on:
 *   <ul>
 *     <li>Unknown runIds pass through (controller does NOT validate run existence — the
 *         {@code WorkflowResumeJobHandler} does, asynchronously). Documented intentional
 *         behavior at {@link ai.operativus.agentmanager.control.controller.WorkflowsController#resumeWorkflowRun}.
 *     <li>Cross-tenant runIds return 404 (tenant guard at the controller level).
 *     <li>Empty / null body fields are defaulted to empty-string {@code output} — no 400.
 *   </ul>
 *
 *   <p>End-to-end HITL auto-pause (agent returns {@code __PAUSED__} sentinel -> workflow_run
 *   transitions to PAUSED) is NOT covered here — it requires the {@code RecordingAgentOperations}
 *   stub currently inlined in {@link WorkflowsRuntimeTest}, which would need extraction to a
 *   shared support class first. Tracked as a follow-on.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowResumeControllerContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void resume_unknownRunId_returns202_andJobIsEnqueued() {
        HttpHeaders auth = newCaller("unknown");

        long jobsBefore = countResumeJobs();

        String unknownRunId = UUID.randomUUID().toString();
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + unknownRunId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "user-approved"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "controller intentionally does NOT validate run existence — unknown runIds pass "
                        + "through and the WorkflowResumeJobHandler handles the not-found case "
                        + "asynchronously; got " + resp.getStatusCode());
        assertNotNull(resp.getBody().get("jobId"), "response must carry a generated jobId");
        assertEquals(unknownRunId, resp.getBody().get("runId"),
                "response must echo the runId — even when unknown");

        assertEquals(jobsBefore + 1, countResumeJobs(),
                "controller MUST enqueue a job for the handler to perform run-existence "
                        + "validation; flipping this contract would silently drop resume requests");
    }

    @Test
    void resume_crossTenantRunId_returns404_andNoJobEnqueued() {
        HttpHeaders orgA = registerLoginWithOrg("wf-resume-iso-a", "wf-resume-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-resume-iso-b", "wf-resume-iso-org-B");

        // Seed a PAUSED workflow_run owned by org B; org A must not be able to resume it.
        String bWorkflowId = createWorkflow(orgB, "B's Paused Flow");
        String runId = UUID.randomUUID().toString();
        seedSession("sess-iso-resume");
        jdbc.update("""
                INSERT INTO workflow_runs
                    (id, workflow_id, session_id, status, current_step_order, current_payload,
                     org_id, created_at, updated_at)
                VALUES (?, ?, 'sess-iso-resume', 'PAUSED', 1, 'pre-pause-payload',
                        'wf-resume-iso-org-B', now(), now())
                """, runId, bWorkflowId);

        long jobsBefore = countResumeJobs();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "evil-cross-tenant"), orgA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "POST /resume on a run owned by another org MUST return 404 (existence-leak "
                        + "protection); a 202 here means the tenant guard at "
                        + "WorkflowsController.resumeWorkflowRun regressed; got " + resp.getStatusCode());

        assertEquals(jobsBefore, countResumeJobs(),
                "no WORKFLOW_RESUME job may be enqueued cross-tenant");

        // Sanity: org B can still resume their own run (proves the seed isn't structurally broken).
        ResponseEntity<Map<String, Object>> bResume = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "owner-approved"), orgB),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, bResume.getStatusCode(),
                "owning org must still be able to resume their own paused run; "
                        + "got " + bResume.getStatusCode());
    }

    @Test
    void resume_emptyBody_returns202_andHandlerDefaultsOutputToEmpty() {
        HttpHeaders auth = newCaller("empty");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + UUID.randomUUID() + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "empty JSON object body must NOT 400 — controller defaults output='' per "
                        + "ResumeWorkflowRequest record's nullable-field contract");
        assertNotNull(resp.getBody().get("jobId"));
    }

    @Test
    void resume_explicitNullOutput_returns202_andHandlerDefaultsOutputToEmpty() {
        HttpHeaders auth = newCaller("nulls");

        Map<String, Object> body = new HashMap<>();
        body.put("output", null);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + UUID.randomUUID() + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "explicit null output must NOT 400 — record field is nullable by design; "
                        + "controller substitutes empty-string before enqueue");
        assertNotNull(resp.getBody().get("jobId"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-resume-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-resume-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createWorkflow fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private void seedSession(String sessionId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'wf-resume-iso-user', 'wf-resume-iso-org-B', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId);
    }

    private long countResumeJobs() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_RESUME'",
                Long.class);
    }
}
