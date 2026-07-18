package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: REQ-DR-4 — pins {@code GET /api/v1/workflows/runs/{runId}/route-options},
 *   the picker-data endpoint the UI uses before {@code POST /runs/{runId}/continue}. Covers:
 *
 *   <ul>
 *     <li>404 for unknown and cross-tenant runIds (existence-leak protection)</li>
 *     <li>a run paused at a ROUTER gate returns its routerConfig choice keys (sorted) + defaultChoice</li>
 *     <li>a run NOT awaiting a route selection returns awaitingRouteSelection=false + empty keys</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowRouteOptionsEndpointRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowStepRepository workflowStepRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;

    @Test
    void routeOptions_unknownRun_returns404() {
        ResponseEntity<String> resp = rest.exchange(
                url(path("nonexistent")), HttpMethod.GET,
                new HttpEntity<>(userHeaders("ro-unknown")), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void routeOptions_crossTenant_returns404() {
        String runId = seedRouterPausedRun("OTHER_ORG");
        ResponseEntity<String> resp = rest.exchange(
                url(path(runId)), HttpMethod.GET,
                new HttpEntity<>(userHeaders("ro-cross")), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void routeOptions_awaitingRouter_returnsChoiceKeys() {
        String runId = seedRouterPausedRun("DEFAULT_SYSTEM_ORG");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(path(runId)), HttpMethod.GET,
                new HttpEntity<>(userHeaders("ro-await")), MAP_TYPE);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(runId, body.get("runId"));
        assertEquals("AWAITING_ROUTE_SELECTION", body.get("status"));
        assertEquals(true, body.get("awaitingRouteSelection"));
        assertEquals(List.of("approve", "reject"), body.get("choiceKeys"));
        assertEquals("approve", body.get("defaultChoice"));
    }

    @Test
    void routeOptions_runNotAwaiting_returnsEmpty() {
        String workflowId = seedWorkflowWithRouterStep("DEFAULT_SYSTEM_ORG");
        String runId = "run-" + UUID.randomUUID();
        workflowRunRepository.save(new WorkflowRun(
                runId, workflowId, seedSession("DEFAULT_SYSTEM_ORG"), RunStatus.RUNNING, 0, null, "DEFAULT_SYSTEM_ORG"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(path(runId)), HttpMethod.GET,
                new HttpEntity<>(userHeaders("ro-running")), MAP_TYPE);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("awaitingRouteSelection"));
        assertTrue(((List<?>) body.get("choiceKeys")).isEmpty());
        assertFalse(body.containsKey("orgId"), "orgId must not leak");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String path(String runId) {
        return "/api/v1/workflows/runs/" + runId + "/route-options";
    }

    private String seedWorkflowWithRouterStep(String orgId) {
        String workflowId = "wf-" + UUID.randomUUID();
        Workflow wf = new Workflow(workflowId, "route-options-test", "router workflow");
        wf.setOrgId(orgId);
        workflowRepository.save(wf);

        // Ordered choices so the sorted assertion is deterministic regardless of map order.
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("approve", "step-approve");
        choices.put("reject", "step-reject");
        RouterStepConfig cfg = new RouterStepConfig(RouteSelectorType.HITL, null, choices, "approve");
        workflowStepRepository.save(new WorkflowStep(
                "step-" + UUID.randomUUID(), workflowId, 0, null, "ROUTER", cfg));
        return workflowId;
    }

    private String seedRouterPausedRun(String orgId) {
        String workflowId = seedWorkflowWithRouterStep(orgId);
        String runId = "run-" + UUID.randomUUID();
        workflowRunRepository.save(new WorkflowRun(
                runId, workflowId, seedSession(orgId), RunStatus.AWAITING_ROUTE_SELECTION, 0, null, orgId));
        return runId;
    }

    private String seedSession(String orgId) {
        String sessionId = "sess-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, NULL, now(), now())
                """, sessionId, "ro-user", orgId);
        return sessionId;
    }

    private HttpHeaders userHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-ro-1234", List.of("ROLE_USER"));
    }
}
