package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import ai.operativus.agentmanager.control.service.HumanReviewService;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.model.HumanReview;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: REQ-HR-5 — pins the HumanReview triage-list endpoint
 *   {@code GET /api/v1/approvals/human-review}. Covers:
 *
 *   <ul>
 *     <li>401 for anonymous (class-level {@code isAuthenticated()})</li>
 *     <li>a plain ROLE_USER may view (viewing is not ADMIN-gated; only /decide is)</li>
 *     <li>only the caller-org's UNDECIDED rows are returned — decided rows and
 *         other-tenant rows are excluded</li>
 *     <li>DTO shape carries the triage fields and does not leak orgId/decision</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class HumanReviewPendingListEndpointRuntimeTest extends BaseIntegrationTest {

    private static final String LIST_PATH = "/api/v1/approvals/human-review";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired private HumanReviewService humanReviewService;
    @Autowired private HumanReviewPendingRepository pendingRepository;

    @Test
    void list_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET, new HttpEntity<>(HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void list_roleUser_canView_returns200() {
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET, new HttpEntity<>(userHeaders("hr-list-user")), LIST_TYPE);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    void list_returnsOnlyCallerOrgUndecidedRows() {
        HumanReviewPending p1 = seedPending("DEFAULT_SYSTEM_ORG");
        HumanReviewPending p2 = seedPending("DEFAULT_SYSTEM_ORG");
        HumanReviewPending decided = seedPending("DEFAULT_SYSTEM_ORG");
        humanReviewService.decide(decided.getId(), "DEFAULT_SYSTEM_ORG",
                HumanReviewDecision.APPROVE, null, "seed");
        HumanReviewPending crossTenant = seedPending("OTHER_ORG");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET, new HttpEntity<>(adminHeaders("hr-list")), LIST_TYPE);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> body = resp.getBody();
        assertNotNull(body);
        Set<Object> ids = body.stream().map(m -> m.get("id")).collect(Collectors.toSet());

        assertTrue(ids.contains(p1.getId()), "undecided caller-org row p1 must be listed");
        assertTrue(ids.contains(p2.getId()), "undecided caller-org row p2 must be listed");
        assertFalse(ids.contains(decided.getId()), "decided row must be excluded");
        assertFalse(ids.contains(crossTenant.getId()), "other-tenant row must be excluded");

        // DTO shape: triage fields present, no orgId/decision leakage.
        Map<String, Object> row = body.stream()
                .filter(m -> p1.getId().equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals("WORKFLOW_STEP", row.get("subjectType"));
        assertEquals(p1.getRunId(), row.get("runId"));
        assertNotNull(row.get("reason"));
        assertNotNull(row.get("createdAt"));
        assertFalse(row.containsKey("orgId"), "orgId must not leak to the wire");
        assertFalse(row.containsKey("decision"), "decision must not appear on the pending queue");
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
