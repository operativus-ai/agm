package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.service.queue.WorkflowResumeJobHandler;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end pins for the WORKFLOW_RESUME background-job pipeline.
 *   Closes the asymmetric contract pinned at the service level by
 *   {@link WorkflowResumeStateMismatchRuntimeTest} by following the call all the way
 *   from the controller through the queue to the terminal job status:
 *   <ul>
 *     <li><b>Unknown runId (loud)</b> — POST /resume returns 202 (controller defers
 *         validation). {@link WorkflowResumeJobHandler#execute} calls
 *         {@code WorkflowService.resumeWorkflowRun}, which throws IAE on missing row.
 *         The IAE propagates through the queue's retry template; with
 *         {@code maxRetries=1} the row terminates in DLQ (with the runId in
 *         {@code error_message}). This is the loud failure path: invalid resume
 *         requests do NOT silently disappear.</li>
 *     <li><b>Wrong-state run (silent)</b> — POST /resume on a non-PAUSED run also
 *         returns 202 + enqueues a job. The handler runs successfully (the service
 *         silently no-ops on state-mismatch). Job terminates in COMPLETED. The
 *         contract is: the queue layer cannot distinguish "I did nothing" from
 *         "I did work" without the service raising a signal — and today the service
 *         doesn't raise one. Pinning this prevents an over-eager refactor that
 *         silently turns these into job failures (which would surface as a
 *         user-visible "your action failed" toast when in fact the workflow is
 *         already past the resume point).</li>
 *   </ul>
 *
 *   <p>Companion: {@link WorkflowResumeControllerContractRuntimeTest} pins the
 *   controller's 202/404/empty-body contract. {@link WorkflowResumeStateMismatchRuntimeTest}
 *   pins the service-layer IAE-vs-no-op asymmetry. This class pins the full
 *   queue-level outcomes that those two stop short of.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class
})
public class WorkflowResumeHandlerQueueE2eRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueTestSupport jobs;
    @Autowired private BackgroundJobRepository jobRepo;

    // ─── W3.1 — unknown runId: 202 -> handler IAE -> job DLQ with runId in message ──

    @Test
    void resume_unknownRunId_handlerThrowsIAE_jobTerminatesInDlqWithRunIdInMessage() {
        String unknownRunId = "missing-run-" + UUID.randomUUID();
        HttpHeaders auth = newCaller("w3-unknown");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + unknownRunId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "user-approved"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "controller intentionally defers validation; got " + resp.getStatusCode());
        String jobId = (String) resp.getBody().get("jobId");
        assertNotNull(jobId, "controller must echo the enqueued job id");

        // Pre-state: the job was enqueued with the default max_retries (3). Force it to
        // 1 so the retry path goes straight to DLQ instead of waiting out a 60s backoff.
        BackgroundJob enqueued = jobRepo.findById(jobId).orElseThrow();
        enqueued.setMaxRetries(1);
        jobRepo.save(enqueued);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobFailure(jobId, Duration.ofSeconds(30));

        assertAll("unknown runId -> handler IAE -> DLQ with diagnostic message",
                () -> assertEquals(JobStatus.DLQ, terminal.getStatus(),
                        "row MUST terminate in DLQ — invalid resume must NOT silently complete "
                                + "as if it succeeded. A COMPLETED row here would mask "
                                + "user-issued resumes that targeted nonexistent / typo'd runIds."),
                () -> assertEquals("WORKFLOW_RESUME", terminal.getJobType(),
                        "job_type must be WORKFLOW_RESUME"),
                () -> assertNotNull(terminal.getErrorMessage(),
                        "DLQ row MUST carry the IAE's message"),
                () -> assertTrue(terminal.getErrorMessage().contains(unknownRunId),
                        "error_message MUST include the runId so triage can identify which "
                                + "resume request failed; got: " + terminal.getErrorMessage()));
    }

    // ─── W3.2 — wrong-state run: 202 -> handler no-ops -> job COMPLETED ──────

    @Test
    void resume_runningRun_handlerSilentlyNoOps_jobTerminatesInCompleted() {
        // Seed a RUNNING workflow_run in the caller's tenant. The resume handler's
        // service call will silently no-op because the run is not PAUSED.
        HttpHeaders auth = newCaller("w3-running");
        String orgId = jdbc.queryForObject(
                "SELECT org_id FROM users WHERE username = ?", String.class,
                jdbc.queryForObject(
                        "SELECT username FROM users ORDER BY created_at DESC LIMIT 1",
                        String.class));
        String runId = seedWorkflowRun("w3-running", orgId, "RUNNING");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("output", "late-approved"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        String jobId = (String) resp.getBody().get("jobId");
        assertNotNull(jobId);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(15));

        assertAll("wrong-state run -> handler no-op -> COMPLETED",
                () -> assertEquals(JobStatus.COMPLETED, terminal.getStatus(),
                        "job MUST terminate in COMPLETED — the service no-ops silently, the "
                                + "handler returns normally, and the queue marks the job done. "
                                + "A DLQ here would mean the service started throwing on "
                                + "state-mismatch — a contract flip that would surface as a "
                                + "user-visible failure for any resume request that arrived "
                                + "after the workflow had already advanced."),
                () -> assertEquals(null, terminal.getErrorMessage(),
                        "successful jobs must carry no error_message"),
                () -> assertEquals(0, terminal.getRetryCount(),
                        "first-try success must not bump retry_count"));

        // The underlying workflow_run MUST be untouched — pinning this here too so a
        // future change to the service that makes wrong-state resume actually advance
        // the workflow surfaces visibly.
        String status = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
        assertEquals("RUNNING", status,
                "workflow_run MUST remain RUNNING — silent no-op means no state change");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(username, username + "@test.local",
                "pwd-w3-1234", List.of("ROLE_USER"));
    }

    private String seedWorkflowRun(String label, String orgId, String status) {
        if (!status.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("status must be uppercase enum identifier; got: " + status);
        }
        // models row idempotent — other test classes also insert it; OK to skip if present.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String sessionId = "sess-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-" + label + "-" + UUID.randomUUID();
        String runId = "wfrun-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'w3-user', ?, now(), now())
                """, sessionId, orgId);
        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, 'w3-test', ?, now(), now())
                """, workflowId, "W3 Probe " + label, orgId);
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload, org_id,
                                           created_at, updated_at)
                VALUES (?, ?, ?, '%s', 1, 'w3-payload', ?, now(), now())
                """.formatted(status),
                runId, workflowId, sessionId, orgId);
        return runId;
    }
}
