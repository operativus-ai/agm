package com.operativus.agentmanager.integration.workflows;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Validation + error-contract runtime coverage for
 *   {@code POST /api/v1/workflows/{id}/run}. Pins the response-code boundaries
 *   that {@link com.operativus.agentmanager.control.controller.WorkflowsController#executeWorkflow}
 *   relies on so a future refactor cannot quietly flip 404 -> 500, 400 -> 500,
 *   or break the controller's silent defaulting of null/missing body fields.
 *
 *   <p>Cross-tenant 404 is already covered by {@link WorkflowTenantIsolationRuntimeTest}
 *   ({@code runCrossTenantWorkflow404AndNoJobEnqueued}); this class covers the
 *   in-tenant validation gaps:
 *   <ul>
 *     <li>unknown id in the caller's own org -> 404 (not 500) + no job enqueued
 *     <li>malformed JSON body -> 400 (Spring's HttpMessageNotReadableException default)
 *     <li>wrong Content-Type -> 415 (Spring's media-type negotiation)
 *     <li>empty {@code &#123;&#125;} body -> 202 with controller-defaulted input/sessionId
 *     <li>explicit {@code null} fields -> 202 with the same defaulting path
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowExecuteValidationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void unknownWorkflowId_returns404_andNoJobEnqueued() {
        HttpHeaders auth = newCaller("unknown");

        long jobsBefore = countWorkflowJobs();

        String unknownId = UUID.randomUUID().toString();
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + unknownId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("input", "anything"), auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "POST /run on a non-existent workflow id must return 404 (not 500) — "
                        + "WorkflowsController.executeWorkflow gates on existsByIdAndOrgId before any DB write; "
                        + "got " + resp.getStatusCode());
        assertEquals(jobsBefore, countWorkflowJobs(),
                "no WORKFLOW_EXECUTION job may be enqueued for an unknown id");
    }

    @Test
    void malformedJsonBody_returns400_andNoJobEnqueued() {
        HttpHeaders auth = newCaller("malformed");
        String workflowId = createWorkflow(auth, "Validation Probe");

        long jobsBefore = countWorkflowJobs();

        HttpHeaders headers = jsonHeadersWithToken(auth);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>("{not valid json}", headers),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "malformed JSON body must surface as 400 (Spring's HttpMessageNotReadableException -> "
                        + "DefaultHandlerExceptionResolver); a 500 here means an @ExceptionHandler regressed; "
                        + "got " + resp.getStatusCode());
        assertEquals(jobsBefore, countWorkflowJobs(),
                "no job may be enqueued when the body failed to deserialize");
    }

    @Test
    void wrongContentType_returns415_andNoJobEnqueued() {
        HttpHeaders auth = newCaller("ct");
        String workflowId = createWorkflow(auth, "Validation Probe");

        long jobsBefore = countWorkflowJobs();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setBearerAuth(bearerToken(auth));
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>("plain text body", headers),
                String.class);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode(),
                "POST /run with text/plain must return 415; got " + resp.getStatusCode());
        assertEquals(jobsBefore, countWorkflowJobs(),
                "no job may be enqueued when the content type was rejected");
    }

    @Test
    void emptyJsonObjectBody_returns202_andHandlerAppliesDefaults() {
        HttpHeaders auth = newCaller("empty");
        String workflowId = createWorkflow(auth, "Validation Probe");
        seedConditionStep(workflowId); // the #649 zero-step guard 400s before body validation

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "empty JSON object body must NOT 400 — controller defaults input='' and "
                        + "sessionId=UUID per the ExecuteWorkflowRequest record's nullable-fields contract");
        Map<String, Object> body = resp.getBody();
        assertNotNull(body, "202 response must carry a WorkflowExecutionResponse body");
        assertNotNull(body.get("jobId"), "response must carry a generated jobId");
        assertEquals(workflowId, body.get("workflowId"), "response must echo the workflowId");

        String sessionId = String.valueOf(body.get("sessionId"));
        assertNotNull(sessionId);
        assertNotEquals("null", sessionId,
                "generated sessionId must not serialize as the literal string 'null'");
        assertTrue(sessionId.length() >= 8,
                "generated sessionId must be UUID-shaped (>=8 chars); got '" + sessionId + "'");
    }

    @Test
    void explicitNullFields_returns202_andHandlerAppliesDefaults() {
        HttpHeaders auth = newCaller("nulls");
        String workflowId = createWorkflow(auth, "Validation Probe");
        seedConditionStep(workflowId); // the #649 zero-step guard 400s before body validation

        Map<String, Object> body = new HashMap<>();
        body.put("input", null);
        body.put("sessionId", null);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows/" + workflowId + "/run"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "explicit null input/sessionId must NOT 400 — both fields are nullable on "
                        + "ExecuteWorkflowRequest; controller substitutes empty-string + random UUID");
        assertNotNull(resp.getBody().get("jobId"));
        assertNotNull(resp.getBody().get("sessionId"),
                "explicit null sessionId must be replaced by a generated UUID");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "wf-val-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-wf-val-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
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

    /** A single agent-less CONDITION step so the dispatch clears the #649 zero-step 400 guard. */
    private void seedConditionStep(String workflowId) {
        jdbc.update("INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action) "
                + "VALUES (?, ?, 1, 'not_empty', 'CONDITION')", "step-" + UUID.randomUUID(), workflowId);
    }

    private long countWorkflowJobs() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'WORKFLOW_EXECUTION'",
                Long.class);
    }

    private HttpHeaders jsonHeadersWithToken(HttpHeaders auth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken(auth));
        return headers;
    }

    private static String bearerToken(HttpHeaders auth) {
        String authz = auth.getFirst(HttpHeaders.AUTHORIZATION);
        assertNotNull(authz, "auth headers must already carry a Bearer token");
        return authz.substring("Bearer ".length());
    }
}
