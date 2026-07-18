package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.service.WorkflowService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box pins on Workflow CRUD + run-lifecycle edges not covered
 *   by {@link WorkflowsRuntimeTest} (which focuses on happy-path execution + RBAC).
 *   Fills three gap clusters: PATCH/DELETE error matrix on workflow definitions, the
 *   run-cancellation REST surface, and the stuck-PAUSED sweeper.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Findings documented by these pins:
 *   - PATCH on missing or cross-tenant workflow id returns 404 — controller (not the
 *     service-level IAE) correctly maps to NOT_FOUND. Good news: WorkflowsController is
 *     already consistent with the codebase convention (unlike the pre-#741
 *     UserAdminService.getUser pattern).
 *   - DELETE on missing or cross-tenant workflow id returns 204 (silent no-op) — the
 *     service's {@code existsByIdAndOrgId} guard short-circuits the delete and the
 *     controller returns 204 unconditionally. UX-surprising for cross-tenant attempts
 *     (caller can't distinguish success from no-op), but documented behavior.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowLifecycleEdgeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private WorkflowService workflowService;

    @Value("${agentmanager.scheduler.workflow-run-paused-cutoff-hours:24}")
    private int pausedCutoffHours;

    // ─── A1 / A2: updateWorkflow error matrix ───

    // A1 — PATCH /api/v1/workflows/{missing UUID} -> 404. WorkflowsController maps the
    //      service's IllegalArgumentException to 404 (not 400), so WorkflowsController is
    //      already consistent with the codebase convention. Pin confirms the contract.
    @Test
    void patchMissingWorkflow_returns404() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-a1-" + tag);

        String missingId = "does-not-exist-" + UUID.randomUUID();
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + missingId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("name", "renamed-" + tag), auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PATCH on missing workflow id must return 404 — controller handles the service's IAE correctly");
    }

    // A2 — Cross-tenant PATCH (org-A admin targets org-B's workflow) -> 404 via the
    //      same controller→IAE→404 mapping as A1, because findByIdAndOrgId returns
    //      empty for cross-tenant lookups.
    @Test
    void patchCrossTenantWorkflow_returns404_existenceLeakProtection() {
        String tag = shortUuid();
        String orgA = "org-wf-a2-a-" + tag;
        String orgB = "org-wf-a2-b-" + tag;

        HttpHeaders authB = registerLoginWithOrg("wf-a2-org-b-" + tag, orgB);
        String wfBId = createWorkflowGetId(authB, "org-B workflow " + tag);

        assertEquals(orgB, jdbc.queryForObject(
                "SELECT org_id FROM workflows WHERE id = ?", String.class, wfBId),
                "fixture: org-B workflow must be tenant-bound before cross-tenant test");

        HttpHeaders authA = registerLoginWithOrg("wf-a2-org-a-" + tag, orgA);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + wfBId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("name", "tampered-by-org-a"), authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant PATCH must return 404 — existence-leak protection via findByIdAndOrgId returning empty");

        String currentName = jdbc.queryForObject(
                "SELECT name FROM workflows WHERE id = ?", String.class, wfBId);
        assertEquals("org-B workflow " + tag, currentName,
                "org-B workflow's name must be unchanged after rejected cross-tenant PATCH");
    }

    // ─── A3 / A4: deleteWorkflow silent no-op ───

    // A3 — DELETE /api/v1/workflows/{missing UUID} -> 204 (silent no-op).
    // Documented contract: controller returns 204 unconditionally; service's
    // existsByIdAndOrgId guard short-circuits the delete. Caller cannot distinguish
    // "deleted" from "never existed".
    @Test
    void deleteMissingWorkflow_returns204_silentNoOp() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-a3-" + tag);

        String missingId = "does-not-exist-" + UUID.randomUUID();
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/workflows/" + missingId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "DELETE on missing id returns 204 silently — caller can't distinguish from a real delete");
    }

    // A4 — Cross-tenant DELETE: org-A admin deletes org-B's workflow -> 204, but the
    //      org-B row is preserved (tenant scoping works at the service layer).
    @Test
    void deleteCrossTenantWorkflow_returns204_butOrgBRowPreserved() {
        String tag = shortUuid();
        String orgA = "org-wf-a4-a-" + tag;
        String orgB = "org-wf-a4-b-" + tag;

        HttpHeaders authB = registerLoginWithOrg("wf-a4-org-b-" + tag, orgB);
        String wfBId = createWorkflowGetId(authB, "org-B preserved " + tag);

        HttpHeaders authA = registerLoginWithOrg("wf-a4-org-a-" + tag, orgA);
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/workflows/" + wfBId),
                HttpMethod.DELETE,
                new HttpEntity<>(authA),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "cross-tenant DELETE returns 204 silently (same as A3)");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflows WHERE id = ?", Integer.class, wfBId);
        assertEquals(1, count,
                "org-B workflow must persist — cross-tenant DELETE must be a no-op at the data layer");
    }

    // ─── B1 / B2: run-cancellation endpoint ───

    // B1 — DELETE /api/v1/workflows/runs/{missing runId} -> 404. Pins the controller's
    //      explicit notFound() branch (not the silent-no-op pattern of deleteWorkflow).
    @Test
    void cancelMissingWorkflowRun_returns404() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-b1-" + tag);

        String missingRunId = "missing-run-" + UUID.randomUUID();
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + missingRunId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE /runs/{missing} must return 404 (controller's findById->null branch)");
    }

    // B2 — Cross-tenant DELETE /runs/{runId} -> 404. Pins the controller's
    //      callerOrgId().equals(run.getOrgId()) guard at WorkflowsController:288.
    @Test
    void cancelCrossTenantWorkflowRun_returns404_existenceLeakProtection() {
        String tag = shortUuid();
        String orgA = "org-wf-b2-a-" + tag;
        String orgB = "org-wf-b2-b-" + tag;

        // Seed a RUNNING workflow_run in org-B directly via JDBC (no need to actually
        // execute a workflow — we only care that the cancel path hits the orgId guard).
        String runId = seedWorkflowRunViaJdbc("wf-b2-run-" + tag, orgB, "RUNNING");

        HttpHeaders authA = registerLoginWithOrg("wf-b2-org-a-" + tag, orgA);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/runs/" + runId),
                HttpMethod.DELETE,
                new HttpEntity<>(authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant cancel must return 404 — existence-leak protection at WorkflowsController:288");

        String stillRunning = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
        assertEquals("RUNNING", stillRunning,
                "org-B's RUNNING workflow_run must be unchanged after rejected cross-tenant cancel");
    }

    // ─── B3: stuck-PAUSED sweeper ───

    // B3 — expireStuckPausedWorkflowRuns picks up workflow_runs that have been PAUSED
    //      beyond the configured cutoff and cancels them with a structured reason
    //      stamped into current_payload. Direct service call (no scheduler driver
    //      exposed in SchedulerTestSupport for this tick).
    @Test
    void expireStuckPausedSweeper_cancelsPausedRowsOlderThanCutoff() {
        String tag = shortUuid();
        // Seed two rows: one within the cutoff window (must survive), one beyond it
        // (must be cancelled).
        String freshRunId = seedWorkflowRunViaJdbc("wf-b3-fresh-" + tag, "org-b3-" + tag, "PAUSED",
                LocalDateTime.now().minusMinutes(30));
        String stuckRunId = seedWorkflowRunViaJdbc("wf-b3-stuck-" + tag, "org-b3-" + tag, "PAUSED",
                LocalDateTime.now().minusHours(pausedCutoffHours + 1L));

        workflowService.expireStuckPausedWorkflowRuns();

        String stuckStatus = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, stuckRunId);
        assertEquals("CANCELLED", stuckStatus,
                "row PAUSED beyond cutoff must be cancelled by the sweeper");

        String stuckPayload = jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, stuckRunId);
        assertNotNull(stuckPayload, "current_payload must carry the cancellation reason");
        assertTrue(stuckPayload.contains("stuck in PAUSED state"),
                "current_payload must carry the structured reason; got: " + stuckPayload);

        String freshStatus = jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, freshRunId);
        assertEquals("PAUSED", freshStatus,
                "row PAUSED within cutoff window must NOT be touched by the sweeper");
    }

    // ─── C: addWorkflowStep — action-discriminated agentId validation ───
    //
    // Before the fix, POST /api/v1/workflows/{id}/steps unconditionally validated the
    // body's `agentId` against the agents registry. CONDITION and LOOP steps repurpose
    // the agent_id column to carry a predicate / bounds expression (`contains:dollar`,
    // `max:5|until:done`), so the agent-existence check rejected every CONDITION/LOOP
    // create with `IllegalArgumentException → 404`. The 096 changeset drops the
    // workflow_steps.agent_id → agents.id FK so the INSERT itself doesn't trip; the
    // service now gates the agent-existence check on action type.

    @Test
    void addConditionStep_acceptsPredicateExpression() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-c1-" + tag);
        String wfId = createWorkflowGetId(auth, "C1 condition step " + tag);

        Map<String, Object> body = Map.of(
                "stepOrder", 1,
                "agentId", "contains:dollar",
                "action", "CONDITION");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/steps"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "CONDITION step with predicate-shaped agentId must persist; got " + resp.getStatusCode());
        Map<String, Object> created = resp.getBody();
        assertNotNull(created);
        assertEquals("CONDITION", created.get("action"));
        assertEquals("contains:dollar", created.get("agentId"),
                "predicate must round-trip in the agentId field for dispatch-time evaluateCondition");
    }

    @Test
    void addLoopStep_acceptsBoundsExpression() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-c2-" + tag);
        String wfId = createWorkflowGetId(auth, "C2 loop step " + tag);

        Map<String, Object> body = Map.of(
                "stepOrder", 1,
                "agentId", "max:5|until:done",
                "action", "LOOP");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/steps"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "LOOP step with bounds-shaped agentId must persist; got " + resp.getStatusCode());
        assertEquals("LOOP", resp.getBody().get("action"));
        assertEquals("max:5|until:done", resp.getBody().get("agentId"));
    }

    @Test
    void addAgentStep_stillRejectsUnknownAgent() {
        // Regression guard: AGENT-action steps must still 404 when agentId doesn't
        // match a registered agent. The CONDITION/LOOP relaxation must not bleed.
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wf-c3-" + tag);
        String wfId = createWorkflowGetId(auth, "C3 agent step " + tag);

        Map<String, Object> body = Map.of(
                "stepOrder", 1,
                "agentId", "no-such-agent-" + UUID.randomUUID(),
                "action", "AGENT");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/steps"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "AGENT step with unknown agentId must still 404 (service IAE → controller 404)");
    }

    // ─── helpers ───

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-wf-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @SuppressWarnings("unchecked")
    private String createWorkflowGetId(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "fixture"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture: workflow create must return 201");
        return (String) resp.getBody().get("id");
    }

    private String seedWorkflowRunViaJdbc(String label, String orgId, String status) {
        return seedWorkflowRunViaJdbc(label, orgId, status, LocalDateTime.now());
    }

    /**
     * Inserts the model + agent + agent_session + workflow + workflow_run chain via JDBC.
     * Bypasses the executor entirely so tests can control orgId, status, and created_at
     * directly. status is interpolated into the SQL because the column is a text-backed
     * enum and bind params would need an explicit ::run_status cast.
     */
    private String seedWorkflowRunViaJdbc(String label, String orgId, String status, LocalDateTime createdAt) {
        if (!status.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("status must be uppercase enum identifier; got: " + status);
        }
        // Model row (idempotent in case multiple tests run in the same JVM).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-" + label + "-" + UUID.randomUUID();
        String runId = "wfrun-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Lifecycle Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);
        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, 'lifecycle fixture', ?, now(), now())
                """, workflowId, "fixture-" + label, orgId);
        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status, current_step_order, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '%s', 0, ?, ?, ?)
                """.formatted(status),
                runId, workflowId, sessionId, orgId,
                Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
        return runId;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
