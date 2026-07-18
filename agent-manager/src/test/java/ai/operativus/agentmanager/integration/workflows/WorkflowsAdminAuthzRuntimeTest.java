package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Focused authz pin for the six admin-gated endpoints on
 *   {@link ai.operativus.agentmanager.control.controller.WorkflowsController}.
 *   Closes 6 TODO entries on
 *   {@link ai.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope (all gated by method-level {@code @PreAuthorize("hasRole('ADMIN')")}
 *   that overrides the class-level {@code @PreAuthorize("isAuthenticated()")}):
 *   <ul>
 *     <li>{@code POST   /api/v1/workflows}                       createWorkflow</li>
 *     <li>{@code PATCH  /api/v1/workflows/&#123;id&#125;}        updateWorkflow</li>
 *     <li>{@code DELETE /api/v1/workflows/&#123;id&#125;}        deleteWorkflow</li>
 *     <li>{@code POST   /api/v1/workflows/&#123;id&#125;/steps}  addWorkflowStep</li>
 *     <li>{@code DELETE /api/v1/workflows/&#123;id&#125;/steps/&#123;stepId&#125;} deleteWorkflowStep</li>
 *     <li>{@code POST   /api/v1/workflows/&#123;id&#125;/clone}  cloneWorkflow</li>
 *   </ul>
 *
 *   <p>Each endpoint matrix: anonymous → 401 (JWT filter), ROLE_USER → 403
 *   (the method-level admin gate overrides the class-level {@code isAuthenticated()}),
 *   ROLE_ADMIN → 2xx (proves the gate clears + handler executes).
 *
 *   <p>The non-admin endpoints on this controller ({@code listWorkflows},
 *   {@code getWorkflow}, {@code getWorkflowSteps}, {@code executeWorkflow},
 *   {@code listWorkflowRuns}, {@code resumeWorkflowRun}, {@code cancelWorkflowRun})
 *   are tested elsewhere: see {@code WorkflowsRuntimeTest} +
 *   {@code WorkflowResumeControllerContractRuntimeTest} +
 *   {@code WorkflowRunCancellationRuntimeTest} +
 *   {@code WorkflowLifecycleEdgeRuntimeTest}.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowsAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── POST /api/v1/workflows (createWorkflow) ────────────────────────────

    @Test
    void createWorkflow_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/workflows",
                Map.of("name", "anon")).getStatusCode());
    }

    @Test
    void createWorkflow_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-cw-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/workflows",
                Map.of("name", "user-probe"), userAuth).getStatusCode(),
                "ROLE_USER must hit the method-level admin gate, not the class-level "
                        + "isAuthenticated() gate");
    }

    @Test
    void createWorkflow_roleAdmin_returns201_andReturnsCreatedDto() {
        HttpHeaders adminAuth = adminHeaders("w4-cw-admin");
        String name = "w4-created-" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = postJson("/api/v1/workflows",
                Map.of("name", name, "description", "authz probe"), adminAuth);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"), "created workflow must echo its id");
        assertEquals(name, resp.getBody().get("name"));
    }

    // ─── PATCH /api/v1/workflows/{id} (updateWorkflow) ──────────────────────

    @Test
    void updateWorkflow_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth("/api/v1/workflows/" + UUID.randomUUID(),
                HttpMethod.PATCH, Map.of("name", "anon")).getStatusCode());
    }

    @Test
    void updateWorkflow_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-uw-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange("/api/v1/workflows/" + UUID.randomUUID(),
                HttpMethod.PATCH, Map.of("name", "user"), userAuth).getStatusCode());
    }

    @Test
    void updateWorkflow_roleAdmin_returns2xx_andHandlerExecutes() {
        HttpHeaders adminAuth = adminHeaders("w4-uw-admin");
        String wfId = createWorkflowAndGetId(adminAuth, "w4-uw-fixture");

        ResponseEntity<Map<String, Object>> resp = exchangeJson("/api/v1/workflows/" + wfId,
                HttpMethod.PATCH, Map.of("name", "renamed-" + UUID.randomUUID()), adminAuth);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin PATCH on existing workflow must return 200; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/workflows/{id} (deleteWorkflow) ─────────────────────

    @Test
    void deleteWorkflow_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth("/api/v1/workflows/" + UUID.randomUUID(),
                HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteWorkflow_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-dw-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange("/api/v1/workflows/" + UUID.randomUUID(),
                HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteWorkflow_roleAdmin_returns204() {
        HttpHeaders adminAuth = adminHeaders("w4-dw-admin");
        String wfId = createWorkflowAndGetId(adminAuth, "w4-dw-fixture");
        ResponseEntity<Void> resp = rest.exchange(url("/api/v1/workflows/" + wfId),
                HttpMethod.DELETE, new HttpEntity<>(adminAuth), Void.class);
        // 204 even on a no-op delete — admin gate is what's under test.
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ─── POST /api/v1/workflows/{id}/steps (addWorkflowStep) ────────────────

    @Test
    void addWorkflowStep_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/workflows/" + UUID.randomUUID() + "/steps",
                Map.of("agentId", "any")).getStatusCode());
    }

    @Test
    void addWorkflowStep_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-aws-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/workflows/" + UUID.randomUUID() + "/steps",
                Map.of("agentId", "any"), userAuth).getStatusCode());
    }

    @Test
    void addWorkflowStep_roleAdmin_returns2xx_orHandlerLevelError() {
        HttpHeaders adminAuth = adminHeaders("w4-aws-admin");
        String wfId = createWorkflowAndGetId(adminAuth, "w4-aws-fixture");
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/v1/workflows/" + wfId + "/steps",
                Map.of("agentId", "some-agent", "stepOrder", 1), adminAuth);
        // Admin gate clears; handler may 201 (success) or 404 (missing agent FK).
        // Both prove the @PreAuthorize gate is not the rejection.
        assertTrue(resp.getStatusCode() == HttpStatus.CREATED
                        || resp.getStatusCode() == HttpStatus.NOT_FOUND,
                "admin POST must clear the gate (201 or 404 acceptable); got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/workflows/{id}/steps/{stepId} (deleteWorkflowStep) ──

    @Test
    void deleteWorkflowStep_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/v1/workflows/" + UUID.randomUUID() + "/steps/" + UUID.randomUUID(),
                HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteWorkflowStep_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-dws-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/v1/workflows/" + UUID.randomUUID() + "/steps/" + UUID.randomUUID(),
                HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteWorkflowStep_roleAdmin_returns204() {
        HttpHeaders adminAuth = adminHeaders("w4-dws-admin");
        ResponseEntity<Void> resp = rest.exchange(url(
                "/api/v1/workflows/" + UUID.randomUUID() + "/steps/" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(adminAuth), Void.class);
        // Service is no-op on missing stepId; admin gate is what's under test.
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ─── POST /api/v1/workflows/{id}/clone (cloneWorkflow) ──────────────────

    @Test
    void cloneWorkflow_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/workflows/" + UUID.randomUUID() + "/clone", Map.of()).getStatusCode());
    }

    @Test
    void cloneWorkflow_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("w4-cl-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/workflows/" + UUID.randomUUID() + "/clone", Map.of(), userAuth).getStatusCode());
    }

    @Test
    void cloneWorkflow_roleAdmin_returns2xx() {
        HttpHeaders adminAuth = adminHeaders("w4-cl-admin");
        String wfId = createWorkflowAndGetId(adminAuth, "w4-cl-fixture");
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/v1/workflows/" + wfId + "/clone", Map.of(), adminAuth);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "admin clone of existing workflow must return 201; got " + resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"), "cloned workflow must have a fresh id");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-wf-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-wf-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflowAndGetId(HttpHeaders adminAuth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name + "-" + UUID.randomUUID());
        body.put("description", "authz fixture");
        ResponseEntity<Map<String, Object>> resp = postJson("/api/v1/workflows", body, adminAuth);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture: createWorkflow must return 201; got " + resp.getStatusCode());
        return (String) resp.getBody().get("id");
    }

    private ResponseEntity<String> postNoAuth(String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
    }

    private ResponseEntity<String> exchangeNoAuth(String path, HttpMethod method, Object body) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, auth), String.class);
    }

    private ResponseEntity<Map<String, Object>> exchangeJson(String path, HttpMethod method,
                                                              Object body, HttpHeaders auth) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, auth), JSON_MAP);
    }
}
