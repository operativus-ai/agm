package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.control.repository.WorkflowEdgeRepository;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the REQ-DR-5 workflow DAG edge REST surface on {@link com.operativus.agentmanager.control.controller.WorkflowsController}:
 * list / graph / add / delete edges, plus validation (self-loop, non-member step, duplicate,
 * cycle rejection via {@link com.operativus.agentmanager.compute.workflow.WorkflowDagValidator})
 * and cross-tenant isolation (404, no existence leak).
 */
@Tag("integration")
public class WorkflowEdgesEndpointRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST = new ParameterizedTypeReference<>() {};

    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowStepRepository workflowStepRepository;
    @Autowired private WorkflowEdgeRepository workflowEdgeRepository;

    @Test
    void edgeCrudGraphValidationAndTenantIsolation() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-edge-A-" + tag;
        String orgB = "org-edge-B-" + tag;
        HttpHeaders callerA = registerLoginWithOrg("wf-edge-A-" + tag, orgA);
        HttpHeaders callerB = registerLoginWithOrg("wf-edge-B-" + tag, orgB);

        // Seed a workflow (org A) with three CONDITION steps (no agent-existence coupling).
        String wfId = "wf-" + tag;
        Workflow wf = new Workflow(wfId, "Edge WF " + tag, "edge test");
        wf.setOrgId(orgA);
        workflowRepository.save(wf);
        String s1 = "s1-" + tag, s2 = "s2-" + tag, s3 = "s3-" + tag;
        workflowStepRepository.save(new WorkflowStep(s1, wfId, 1, "contains:a", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s2, wfId, 2, "contains:b", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s3, wfId, 3, "contains:c", "CONDITION"));

        // 1. Graph starts with 3 nodes, 0 edges.
        ResponseEntity<Map<String, Object>> graph0 = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/graph"), HttpMethod.GET, new HttpEntity<>(callerA), JSON_MAP);
        assertEquals(HttpStatus.OK, graph0.getStatusCode());
        assertEquals(3, ((List<?>) graph0.getBody().get("steps")).size());
        assertEquals(0, ((List<?>) graph0.getBody().get("edges")).size());

        // 2. Add two edges s1->s2, s2->s3.
        String e1 = addEdge(callerA, wfId, s1, s2, HttpStatus.CREATED);
        addEdge(callerA, wfId, s2, s3, HttpStatus.CREATED);
        assertNotNull(e1);
        assertEquals(2, listEdges(callerA, wfId).size());

        // 3. Cycle (s3->s1 closes s1->s2->s3->s1) rejected with 400 and NOT persisted.
        addEdge(callerA, wfId, s3, s1, HttpStatus.BAD_REQUEST);
        assertEquals(2, listEdges(callerA, wfId).size(), "cycle-creating edge must be rolled back");

        // 4. Self-loop rejected.
        addEdge(callerA, wfId, s1, s1, HttpStatus.BAD_REQUEST);

        // 5. Edge to a non-member step rejected.
        addEdge(callerA, wfId, s1, "not-a-step-" + tag, HttpStatus.BAD_REQUEST);

        // 6. Duplicate edge rejected.
        addEdge(callerA, wfId, s1, s2, HttpStatus.BAD_REQUEST);

        // 7. Cross-tenant: org B sees neither graph nor edges, can't mutate (404, no leak).
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/graph"), HttpMethod.GET, new HttpEntity<>(callerB), JSON_MAP).getStatusCode());
        addEdge(callerB, wfId, s1, s3, HttpStatus.NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/edges/" + e1), HttpMethod.DELETE, new HttpEntity<>(callerB), Void.class).getStatusCode());
        assertEquals(2, listEdges(callerA, wfId).size(), "org B must not have mutated org A's edges");

        // 8. Owner deletes one edge → 204, one remains.
        assertEquals(HttpStatus.NO_CONTENT, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/edges/" + e1), HttpMethod.DELETE, new HttpEntity<>(callerA), Void.class).getStatusCode());
        assertEquals(1, listEdges(callerA, wfId).size());

        // Persisted edge count matches what the API reports.
        assertEquals(1, workflowEdgeRepository.countByWorkflowId(wfId));
    }

    @Test
    void loopBackEdgePort_isAccepted_butNullPortCycleStillRejected() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String org = "org-loop-" + tag;
        HttpHeaders caller = registerLoginWithOrg("wf-loop-" + tag, org);
        String wfId = "wf-loop-" + tag;
        Workflow wf = new Workflow(wfId, "Loop WF " + tag, "loop test");
        wf.setOrgId(org);
        workflowRepository.save(wf);
        String loop = "lp-" + tag, body = "bd-" + tag;
        workflowStepRepository.save(new WorkflowStep(loop, wfId, 1, "max:2", "LOOP"));
        workflowStepRepository.save(new WorkflowStep(body, wfId, 2, "contains:x", "CONDITION"));

        addEdgeWithPort(caller, wfId, loop, body, "loop", HttpStatus.CREATED);
        // The body→loop back-edge with the sanctioned "back" port is accepted (was a 400 cycle).
        addEdgeWithPort(caller, wfId, body, loop, "back", HttpStatus.CREATED);
        assertEquals(2, listEdges(caller, wfId).size());
        // A null-port edge closing the same loop is still a cycle → 400, rolled back.
        addEdgeWithPort(caller, wfId, body, loop, null, HttpStatus.BAD_REQUEST);
        assertEquals(2, listEdges(caller, wfId).size(), "non-back cycle must be rolled back");
    }

    @Test
    void relabelEdgePort_updatesCondition_revalidatesCycle_duplicateAndTenantScoped() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-relabel-A-" + tag;
        String orgB = "org-relabel-B-" + tag;
        HttpHeaders callerA = registerLoginWithOrg("wf-relabel-A-" + tag, orgA);
        HttpHeaders callerB = registerLoginWithOrg("wf-relabel-B-" + tag, orgB);

        String wfId = "wf-relabel-" + tag;
        Workflow wf = new Workflow(wfId, "Relabel WF " + tag, "relabel test");
        wf.setOrgId(orgA);
        workflowRepository.save(wf);
        String s1 = "rs1-" + tag, s2 = "rs2-" + tag;
        workflowStepRepository.save(new WorkflowStep(s1, wfId, 1, "contains:a", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s2, wfId, 2, "contains:b", "CONDITION"));

        // Two edges on the same pair, distinct ports (allowed by the COALESCE(condition,'') unique key).
        String e1 = addEdge(callerA, wfId, s1, s2, HttpStatus.CREATED);          // null port
        String e2 = addEdgeWithPort(callerA, wfId, s1, s2, "false", HttpStatus.CREATED);

        // 1. Relabel e1 null -> "true": 200, persisted.
        assertEquals(HttpStatus.OK, patchEdge(callerA, wfId, e1, "true").getStatusCode());
        assertEquals("true", conditionOf(callerA, wfId, e1));

        // 2. Unknown edge id -> 404.
        assertEquals(HttpStatus.NOT_FOUND, patchEdge(callerA, wfId, "no-edge-" + tag, "x").getStatusCode());

        // 3. Cross-tenant -> 404, no mutation.
        assertEquals(HttpStatus.NOT_FOUND, patchEdge(callerB, wfId, e1, "false").getStatusCode());
        assertEquals("true", conditionOf(callerA, wfId, e1));

        // 4. Duplicate: relabel e2 "false" -> "true" collides with e1 (now "true") -> 400, not applied.
        assertEquals(HttpStatus.BAD_REQUEST, patchEdge(callerA, wfId, e2, "true").getStatusCode());
        assertEquals("false", conditionOf(callerA, wfId, e2));

        // 5. Cycle revalidation: relabeling the sanctioned "back" port to a null port closes a cycle.
        String loopWf = "wf-relabel-loop-" + tag;
        Workflow lwf = new Workflow(loopWf, "Relabel Loop " + tag, "loop relabel");
        lwf.setOrgId(orgA);
        workflowRepository.save(lwf);
        String lp = "rlp-" + tag, bd = "rbd-" + tag;
        workflowStepRepository.save(new WorkflowStep(lp, loopWf, 1, "max:2", "LOOP"));
        workflowStepRepository.save(new WorkflowStep(bd, loopWf, 2, "contains:x", "CONDITION"));
        addEdgeWithPort(callerA, loopWf, lp, bd, "loop", HttpStatus.CREATED);
        String backEdge = addEdgeWithPort(callerA, loopWf, bd, lp, "back", HttpStatus.CREATED);
        assertEquals(HttpStatus.BAD_REQUEST, patchEdge(callerA, loopWf, backEdge, null).getStatusCode());
        assertEquals("back", conditionOf(callerA, loopWf, backEdge), "rejected relabel must revert the port");
    }

    @Test
    void validateGraph_reportsOrphans_thenCleanWhenWired_andTenantScoped() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-val-A-" + tag;
        String orgB = "org-val-B-" + tag;
        HttpHeaders callerA = registerLoginWithOrg("wf-val-A-" + tag, orgA);
        HttpHeaders callerB = registerLoginWithOrg("wf-val-B-" + tag, orgB);

        String wfId = "wf-val-" + tag;
        Workflow wf = new Workflow(wfId, "Validate WF " + tag, "validate test");
        wf.setOrgId(orgA);
        workflowRepository.save(wf);
        String s1 = "vs1-" + tag, s2 = "vs2-" + tag, s3 = "vs3-" + tag;
        workflowStepRepository.save(new WorkflowStep(s1, wfId, 1, "contains:a", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s2, wfId, 2, "contains:b", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s3, wfId, 3, "contains:c", "CONDITION"));

        // No edges yet -> legacy flat-list, reported valid (nothing to validate).
        Map<String, Object> v0 = validate(callerA, wfId);
        assertEquals(Boolean.TRUE, v0.get("valid"));
        assertTrue(((List<?>) v0.get("unreachableStepIds")).isEmpty());

        // Wire s1->s2 only. s3 has no inbound edge and isn't the start -> orphan.
        addEdge(callerA, wfId, s1, s2, HttpStatus.CREATED);
        Map<String, Object> v1 = validate(callerA, wfId);
        assertEquals(Boolean.FALSE, v1.get("valid"));
        assertEquals(Boolean.FALSE, v1.get("hasCycle"));
        assertEquals(List.of(s3), v1.get("unreachableStepIds"));

        // Wire s2->s3 -> every step reachable from start -> valid, no orphans.
        addEdge(callerA, wfId, s2, s3, HttpStatus.CREATED);
        Map<String, Object> v2 = validate(callerA, wfId);
        assertEquals(Boolean.TRUE, v2.get("valid"));
        assertTrue(((List<?>) v2.get("unreachableStepIds")).isEmpty());

        // Cross-tenant: org B can't read the report (404, no leak).
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/validate"), HttpMethod.GET,
                new HttpEntity<>(callerB), JSON_MAP).getStatusCode());
    }

    @Test
    void saveAndLoadLayout_replacesPositions_dropsUnknownSteps_andTenantScoped() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-lay-A-" + tag;
        String orgB = "org-lay-B-" + tag;
        HttpHeaders callerA = registerLoginWithOrg("wf-lay-A-" + tag, orgA);
        HttpHeaders callerB = registerLoginWithOrg("wf-lay-B-" + tag, orgB);

        String wfId = "wf-lay-" + tag;
        Workflow wf = new Workflow(wfId, "Layout WF " + tag, "layout test");
        wf.setOrgId(orgA);
        workflowRepository.save(wf);
        String s1 = "ls1-" + tag, s2 = "ls2-" + tag;
        workflowStepRepository.save(new WorkflowStep(s1, wfId, 1, "contains:a", "CONDITION"));
        workflowStepRepository.save(new WorkflowStep(s2, wfId, 2, "contains:b", "CONDITION"));

        // No saved layout -> empty.
        assertTrue(((List<?>) loadLayout(callerA, wfId).get("positions")).isEmpty());

        // Save two positions plus one for an unknown step (must be dropped).
        Map<String, Object> body = Map.of("positions", List.of(
                Map.of("stepId", s1, "x", 10.0, "y", 20.0),
                Map.of("stepId", s2, "x", 30.5, "y", 40.5),
                Map.of("stepId", "ghost-" + tag, "x", 99.0, "y", 99.0)));
        ResponseEntity<Map<String, Object>> put = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/layout"), HttpMethod.PUT,
                new HttpEntity<>(body, callerA), JSON_MAP);
        assertEquals(HttpStatus.OK, put.getStatusCode());
        assertEquals(2, ((List<?>) put.getBody().get("positions")).size(), "unknown step dropped");

        // Reload -> the two valid positions persisted with their coordinates.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> saved = (List<Map<String, Object>>) loadLayout(callerA, wfId).get("positions");
        assertEquals(2, saved.size());
        Map<String, Object> p1 = saved.stream().filter(p -> s1.equals(p.get("stepId"))).findFirst().orElseThrow();
        assertEquals(10.0, ((Number) p1.get("x")).doubleValue());
        assertEquals(20.0, ((Number) p1.get("y")).doubleValue());

        // Save a smaller set -> full replace (s2 removed).
        rest.exchange(url("/api/v1/workflows/" + wfId + "/layout"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("positions", List.of(Map.of("stepId", s1, "x", 1.0, "y", 2.0))), callerA), JSON_MAP);
        assertEquals(1, ((List<?>) loadLayout(callerA, wfId).get("positions")).size(), "replace, not merge");

        // Cross-tenant: org B can neither read nor write org A's layout (404).
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/layout"), HttpMethod.GET,
                new HttpEntity<>(callerB), JSON_MAP).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/workflows/" + wfId + "/layout"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("positions", List.of()), callerB), JSON_MAP).getStatusCode());
        assertEquals(1, ((List<?>) loadLayout(callerA, wfId).get("positions")).size(), "org B must not have mutated");
    }

    private Map<String, Object> loadLayout(HttpHeaders auth, String wfId) {
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/layout"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    private Map<String, Object> validate(HttpHeaders auth, String wfId) {
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/validate"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    private ResponseEntity<Map<String, Object>> patchEdge(HttpHeaders auth, String wfId,
                                                          String edgeId, String condition) {
        Map<String, Object> body = new HashMap<>();
        body.put("condition", condition); // null = relabel to unconditional
        return rest.exchange(url("/api/v1/workflows/" + wfId + "/edges/" + edgeId),
                HttpMethod.PATCH, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String conditionOf(HttpHeaders auth, String wfId, String edgeId) {
        return listEdges(auth, wfId).stream()
                .filter(e -> edgeId.equals(String.valueOf(e.get("id"))))
                .map(e -> e.get("condition") == null ? null : String.valueOf(e.get("condition")))
                .findFirst().orElse(null);
    }

    private String addEdgeWithPort(HttpHeaders auth, String wfId, String from, String to,
                                   String port, HttpStatus expected) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromStepId", from);
        body.put("toStepId", to);
        if (port != null) body.put("condition", port);
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/edges"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(expected, r.getStatusCode(),
                "POST edge " + from + "->" + to + " port=" + port + " expected " + expected);
        return r.getStatusCode() == HttpStatus.CREATED ? String.valueOf(r.getBody().get("id")) : null;
    }

    private String addEdge(HttpHeaders auth, String wfId, String from, String to, HttpStatus expected) {
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/edges"), HttpMethod.POST,
                new HttpEntity<>(Map.of("fromStepId", from, "toStepId", to), auth), JSON_MAP);
        assertEquals(expected, r.getStatusCode(),
                "POST edge " + from + "->" + to + " expected " + expected + " but got " + r.getStatusCode());
        return r.getStatusCode() == HttpStatus.CREATED ? String.valueOf(r.getBody().get("id")) : null;
    }

    private List<Map<String, Object>> listEdges(HttpHeaders auth, String wfId) {
        ResponseEntity<List<Map<String, Object>>> r = rest.exchange(
                url("/api/v1/workflows/" + wfId + "/edges"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertTrue(r.getStatusCode().is2xxSuccessful());
        return r.getBody();
    }
}
