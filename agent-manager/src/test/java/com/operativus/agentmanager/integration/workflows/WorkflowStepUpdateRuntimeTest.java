package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves PATCH /api/v1/workflows/{id}/steps/{stepId} (REQ-DR-5 node editing)
 *   — the editable-inspector enabler. Updates a step's editable config (here a CONDITION node's
 *   predicate expression stored in agent_id), with org/cross-workflow scoping (404). The node's
 *   action (kind) is immutable.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
public class WorkflowStepUpdateRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Test
    void patchConditionStep_updatesExpression_actionImmutable() {
        HttpHeaders auth = newCaller();
        String wf = createWorkflow(auth, "Step-update test");

        // Add a CONDITION step (agent_id carries the predicate).
        ResponseEntity<Map<String, Object>> added = rest.exchange(
                url("/api/v1/workflows/" + wf + "/steps"), HttpMethod.POST,
                new HttpEntity<>(Map.of("stepOrder", 1, "agentId", "contains:approve", "action", "CONDITION"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, added.getStatusCode());
        String stepId = String.valueOf(added.getBody().get("id"));

        // PATCH the expression.
        ResponseEntity<Map<String, Object>> patched = rest.exchange(
                url("/api/v1/workflows/" + wf + "/steps/" + stepId), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("stepOrder", 1, "agentId", "contains:reject", "action", "AGENT"), auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, patched.getStatusCode());
        assertEquals("contains:reject", patched.getBody().get("agentId"));
        // action is immutable on update — still CONDITION despite the body saying AGENT.
        assertEquals("CONDITION", patched.getBody().get("action"));

        // Confirm persisted.
        ResponseEntity<List<Map<String, Object>>> steps = rest.exchange(
                url("/api/v1/workflows/" + wf + "/steps"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertTrue(steps.getBody().stream().anyMatch(s ->
                "contains:reject".equals(s.get("agentId")) && "CONDITION".equals(s.get("action"))));
    }

    @Test
    void patchUnknownStep_returns404() {
        HttpHeaders auth = newCaller();
        String wf = createWorkflow(auth, "Step-update 404");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/workflows/" + wf + "/steps/" + UUID.randomUUID()), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("agentId", "x", "action", "AGENT"), auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    private HttpHeaders newCaller() {
        String username = "step-upd-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local", "pwd-step-upd-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createWorkflow(HttpHeaders auth, String name) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/workflows"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name), auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }
}
