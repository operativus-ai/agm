package com.operativus.agentmanager.integration.approvals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import com.operativus.agentmanager.control.service.HumanReviewService;
import com.operativus.agentmanager.core.entity.HumanReviewPending;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: REQ-HR-5 — pins the unified
 *   {@code POST /api/v1/approvals/{id}/decide} endpoint authz + dispatch
 *   surface end-to-end. The dispatcher integration (workflow CONDITION pause
 *   → /decide → resume) is covered by {@code WorkflowConditionHumanReviewRuntimeTest};
 *   this test focuses on the endpoint contract itself:
 *
 *   <ul>
 *     <li>4-case authz matrix (anon, USER, ADMIN, cross-tenant)</li>
 *     <li>approve / reject decision parsing</li>
 *     <li>idempotency on double-decide</li>
 *     <li>response shape (pendingId, runId, subjectType, decision, decidedBy, decidedAt)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class HumanReviewDecideEndpointRuntimeTest extends BaseIntegrationTest {

    private static final String DECIDE_PATH = "/api/v1/approvals/";

    @Autowired private HumanReviewService humanReviewService;
    @Autowired private HumanReviewPendingRepository pendingRepository;
    @Autowired private ObjectMapper mapper;

    // ─── authz matrix ────────────────────────────────────────────────────────

    @Test
    void decide_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + "any-id/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void decide_roleUser_returns403() {
        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + "any-id/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), userHeaders("decide-user")),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void decide_unknownId_returns404_forAdmin() {
        // ADMIN clears the gate but no pending row exists → 404 (ResourceNotFound mapped).
        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + "nonexistent/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), adminHeaders("decide-admin-404")),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void decide_crossTenant_returns404() {
        // Pending row belongs to a different org → 404 (existence-leak protection §79).
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        // Caller is in a different org.
        HttpHeaders cross = registerLoginWithOrg("decide-cross-" + UUID.randomUUID(), "OTHER_ORG");

        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), cross), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ─── happy path + idempotency ────────────────────────────────────────────

    @Test
    void decide_approve_settlesRowAndReturnsResponseShape() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-approve");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(pending.getId(), body.get("pendingId"));
        assertEquals(pending.getRunId(), body.get("runId"));
        assertEquals("WORKFLOW_STEP", body.get("subjectType"));
        assertEquals("APPROVE", body.get("decision"));
        assertNotNull(body.get("decidedBy"), "decidedBy must be populated");
        assertNotNull(body.get("decidedAt"), "decidedAt must be populated");

        // Row settled in DB.
        HumanReviewPending settled = pendingRepository.findById(pending.getId()).orElseThrow();
        assertEquals("APPROVE", settled.getDecision());
        assertNotNull(settled.getDecidedAt());
    }

    @Test
    void decide_reject_settlesRowAsReject() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-reject");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "reject"), auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("REJECT", resp.getBody().get("decision"));
        assertEquals("REJECT",
                pendingRepository.findById(pending.getId()).orElseThrow().getDecision());
    }

    @Test
    void decide_invalidDecisionString_returns400() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-bad");

        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "MAYBE"), auth), String.class);
        // @Pattern validation fires before @PreAuthorize check on body → 400 from @Valid.
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void decide_alreadySettled_isIdempotentReturning200() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-idempotent");

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, first.getStatusCode());

        // Second call with the OPPOSITE decision — should be idempotent no-op, echo first.
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "reject"), auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, second.getStatusCode());
        // First decision wins; second call's "reject" is ignored per service-layer idempotency.
        assertEquals("APPROVE", second.getBody().get("decision"));
    }

    @Test
    void decide_blankDecision_returns400() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-blank");

        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", ""), auth), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void decide_responseShape_includesNoLeakageOfInternalFields() {
        HumanReviewPending pending = seedPending("DEFAULT_SYSTEM_ORG");
        HttpHeaders auth = adminHeaders("decide-shape");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + pending.getId() + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        Map<String, Object> body = resp.getBody();
        // Should NOT carry internal options/payload back to wire.
        assertNotEquals(true, body.containsKey("options"));
        assertNotEquals(true, body.containsKey("payload"));
        assertNotEquals(true, body.containsKey("orgId"));
        assertNotEquals(true, body.containsKey("expiresAt"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private HumanReviewPending seedPending(String orgId) {
        HumanReview hr = new HumanReview(true, null, null, null, null, null, null, null, null);
        return humanReviewService.pauseFor(
                HumanReviewSubjectType.WORKFLOW_STEP,
                "step-" + UUID.randomUUID(),
                "run-" + UUID.randomUUID(),
                orgId,
                "test pending",
                hr,
                Map.of("plannedCursor", 0),
                "test-seed");
    }

    private HttpHeaders userHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-hr-1234", List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-hr-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
