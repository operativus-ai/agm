package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the workflows surface,
 *   third domain in the changeset-007 audit (after knowledge_bases and schedules).
 *   ADMIN of org A cannot list, fetch, modify, delete, run, resume, clone, list-steps,
 *   add-steps, delete-steps, or list-runs against ADMIN of org B's workflows. Cross-tenant
 *   lookups return 404 (mutations) / empty page (runs list); no 404-vs-403 distinction
 *   (existence-leak protection).
 *   <p>
 *   Child entities ({@code WorkflowStep}, {@code WorkflowRun}) do NOT carry their own
 *   {@code org_id} column — they are tenant-scoped via parent traversal (the controller +
 *   service resolve {@code workflowId → workflows.org_id} before any child mutation).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void listReturnsOnlyCallerOrgWorkflows() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-list", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-list", "wf-iso-org-B");

        createWorkflow(orgA, "A's Pipeline");
        createWorkflow(orgA, "A's Sweep");
        createWorkflow(orgB, "B's Pipeline");

        ResponseEntity<Map<String, Object>> aPage = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, aPage.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aPage.getBody().get("content");
        assertEquals(2, aContent.size(),
                "org A listing must contain exactly A's 2 workflows; got " + aContent.size());
        assertTrue(aContent.stream().allMatch(w -> String.valueOf(w.get("name")).startsWith("A's ")),
                "every row in A's listing must be an A-named workflow; got " + aContent);

        ResponseEntity<Map<String, Object>> bPage = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bPage.getBody().get("content");
        assertEquals(1, bContent.size(),
                "org B listing must contain exactly B's 1 workflow; got " + bContent.size());
    }

    @Test
    void getById404ForCrossTenantWorkflow() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-get", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-get", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Get-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /workflows/{B-id} as A must return 404; got " + response.getStatusCode());
    }

    @Test
    void patch404ForCrossTenantWorkflowAndRowUnmodified() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-patch", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-patch", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Patch-Probe");

        Map<String, Object> body = Map.of("description", "should never apply");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId),
                HttpMethod.PATCH,
                new HttpEntity<>(body, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PATCH cross-tenant must return 404; got " + response.getStatusCode());

        String bDescription = jdbc.queryForObject(
                "SELECT description FROM workflows WHERE id = ?",
                String.class, bId);
        // The original create did not set description — null on the row is correct evidence
        // that the cross-tenant PATCH did NOT write through.
        assertEquals(null, bDescription,
                "cross-tenant PATCH must not have written B's row; got description=" + bDescription);
    }

    @Test
    void deleteCrossTenantWorkflowIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-del", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-del", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Delete-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE returns 204 unconditionally (controller shape preserved)");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflows WHERE id = ?",
                Long.class, bId);
        assertEquals(1L, count == null ? 0L : count,
                "cross-tenant DELETE must not have removed B's row");
    }

    @Test
    void clone404ForCrossTenantWorkflow() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-clone", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-clone", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Clone-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId + "/clone"),
                HttpMethod.POST,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /workflows/{B-id}/clone as A must return 404; got " + response.getStatusCode());

        // Sanity: B can still clone their own workflow.
        ResponseEntity<Map<String, Object>> bClone = rest.exchange(
                url("/api/v1/workflows/" + bId + "/clone"),
                HttpMethod.POST,
                new HttpEntity<>(orgB),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, bClone.getStatusCode(),
                "B's own clone must succeed; got " + bClone.getStatusCode());
    }

    @Test
    void runCrossTenantWorkflow404AndNoJobEnqueued() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-run", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-run", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Run-Probe");

        long jobsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_EXECUTION'",
                Long.class);

        Map<String, Object> body = Map.of("input", "evil-cross-tenant");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /workflows/{B-id}/run as A must return 404");

        long jobsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_EXECUTION'",
                Long.class);
        assertEquals(jobsBefore, jobsAfter,
                "no WORKFLOW_EXECUTION job must be enqueued cross-tenant; before=" + jobsBefore
                        + " after=" + jobsAfter);
    }

    @Test
    void getRunsReturnsEmptyPageForCrossTenantWorkflow() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-runs", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-runs", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Runs-Probe");

        // Direct-seed a workflow_run so the unscoped query would otherwise return one row.
        // agent_sessions FK must be satisfied — insert a minimal session row first.
        String seedSessionId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id)
                VALUES (?, 'seed-user-runs', 'wf-iso-org-B')
                """, seedSessionId);
        // workflow_runs.org_id is NOT NULL (changeset 058) — pin it to org B so the
        // cross-tenant query under test has a row to filter out.
        jdbc.update("""
                INSERT INTO workflow_runs
                  (id, workflow_id, session_id, status, current_step_order, org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'COMPLETED', 1, ?, NOW(), NOW())
                """,
                java.util.UUID.randomUUID().toString(), bId, seedSessionId, "wf-iso-org-B");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows/" + bId + "/runs"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertTrue(content.isEmpty(),
                "GET /workflows/{B-id}/runs as A must be empty (parent invisible → runs invisible); got "
                        + content);
    }

    @Test
    void addStepCrossTenantReturns404AndChildNotInserted() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-step", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-step", "wf-iso-org-B");

        String bId = createWorkflow(orgB, "B's Step-Probe");

        Map<String, Object> stepBody = new HashMap<>();
        stepBody.put("stepOrder", 1);
        stepBody.put("agentId", "evil-injected-agent");
        stepBody.put("action", "AGENT");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/" + bId + "/steps"),
                HttpMethod.POST,
                new HttpEntity<>(stepBody, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /workflows/{B-id}/steps as A must return 404 (parent invisible)");

        Long stepCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ?",
                Long.class, bId);
        assertEquals(0L, stepCount == null ? 0L : stepCount,
                "no step row must be inserted cross-tenant; got " + stepCount);
    }

    @Test
    void deleteStepCrossTenantIsNoOpAndStepRowSurvives() {
        // Controller has class-level @PreAuthorize("hasRole('ADMIN')") on
        // deleteWorkflowStep but no path-level existsByIdAndOrgId pre-check —
        // the tenant guard lives in WorkflowService.deleteWorkflowStep which
        // walks step.workflowId → workflows.org_id before issuing the delete.
        // Cross-tenant: silently no-ops, controller returns 204 unconditionally.
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-delstep", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-delstep", "wf-iso-org-B");

        String bWorkflowId = createWorkflow(orgB, "B's DeleteStep-Probe");
        String bStepId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action)
                VALUES (?, ?, 1, 'b-agent-evil-probe', 'AGENT')
                """, bStepId, bWorkflowId);

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/workflows/" + bWorkflowId + "/steps/" + bStepId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE /workflows/{B-id}/steps/{stepId} as A returns 204 unconditionally "
                        + "(controller shape preserved); got " + response.getStatusCode());

        Long stepCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE id = ?",
                Long.class, bStepId);
        assertEquals(1L, stepCount == null ? 0L : stepCount,
                "cross-tenant deleteStep must NOT have removed B's workflow_steps row. "
                        + "Pre-fix the service called workflowStepRepository.deleteById(stepId) "
                        + "without checking parent workflow's org — any admin could delete any "
                        + "tenant's step by stepId.");
    }

    @Test
    void resumeCrossTenantWorkflowRunReturns404AndNoJobEnqueued() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-resume", "wf-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("wf-iso-b-resume", "wf-iso-org-B");

        String bWorkflowId = createWorkflow(orgB, "B's Resume-Probe");

        // Seed a workflow_run owned by org B in PAUSED state. agent_sessions FK must be
        // satisfied — insert a minimal session row stamped to org B first.
        String seedSessionId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id)
                VALUES (?, 'seed-user-resume', 'wf-iso-org-B')
                """, seedSessionId);
        String runId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workflow_runs
                  (id, workflow_id, session_id, status, current_step_order, org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'PAUSED', 1, ?, NOW(), NOW())
                """,
                runId, bWorkflowId, seedSessionId, "wf-iso-org-B");

        // Capture pre-call queue depth — the tenant guard sits in WorkflowsController.
        // resumeWorkflowRun BEFORE the jobQueueService.enqueue call (cross-tenant → 404
        // returned directly; no job written). Pre-fix it was missing entirely.
        Long preJobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = ?",
                Long.class, "WORKFLOW_RESUME");

        Map<String, Object> resumeBody = Map.of("output", "human-approved continuation");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/workflows/runs/" + runId + "/resume"),
                HttpMethod.POST,
                new HttpEntity<>(resumeBody, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "cross-tenant resume must return 404 (existence-leak protection); got "
                        + response.getStatusCode());

        Long postJobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = ?",
                Long.class, "WORKFLOW_RESUME");
        assertEquals(preJobs, postJobs,
                "no WORKFLOW_RESUME background_job must be enqueued when cross-tenant "
                        + "resume is attempted. Pre-fix the controller would have called "
                        + "jobQueueService.enqueue before the tenant check, leaking a job "
                        + "into the queue that the handler would then act on against B's run.");

        // The PAUSED row itself must remain untouched.
        String stillStatus = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
        assertEquals("PAUSED", stillStatus,
                "B's workflow_run must remain PAUSED after A's cross-tenant resume attempt; "
                        + "any other terminal status indicates the handler did fire.");
    }

    @Test
    void postIgnoresBodyOrgIdAndStampsCallerOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("wf-iso-a-post", "wf-iso-org-A");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "post-org-injection-attempt");
        body.put("description", "body claims org B");
        body.put("orgId", "wf-iso-org-B");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);

        String createdId = String.valueOf(created.get("id"));
        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM workflows WHERE id = ?",
                String.class, createdId);
        assertEquals("wf-iso-org-A", storedOrgId,
                "POST must stamp caller's orgId; body-injected orgId must be ignored. got=" + storedOrgId);
    }

    // ─── helpers ───

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
}
