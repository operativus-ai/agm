package ai.operativus.agentmanager.integration.approvals;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.HumanReviewPendingRepository;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: REQ-HR-4 + REQ-HR-4.5 end-to-end pin —
 *     POST {@code /api/v1/approvals/{id}/decide} for a {@code subjectType=AGENT_TOOL_CALL}
 *     {@link HumanReviewPending} row settles BOTH the unified pending row AND the legacy
 *     {@code approvals} row sharing the same id, via the
 *     {@code AgentToolResumeHandler} SPI bridge.
 *
 *     <p>HitlAdvisor.requireApprovalForTool() dual-tracks: it writes the legacy approval
 *     row first, then creates a HumanReviewPending row at the SAME id (REQ-HR-4.5). This
 *     test seeds both shapes directly (skipping the actual LLM tool-call trigger) and
 *     exercises the decide path end-to-end through the HTTP surface + SPI dispatch.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class AgentToolCallHumanReviewE2eRuntimeTest extends BaseIntegrationTest {

    private static final String DECIDE_PATH = "/api/v1/approvals/";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private HumanReviewPendingRepository pendingRepository;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    @Test
    void decide_approve_settlesBothPendingAndLegacyApproval() {
        String orgId = "org-tool-e2e-" + shortUuid();
        HttpHeaders auth = authHeadersInOrg("tool-e2e-approver", orgId);

        DualTrackFixture fx = seedDualTrack(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + fx.sharedId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(fx.sharedId, body.get("pendingId"));
        assertEquals("AGENT_TOOL_CALL", body.get("subjectType"));
        assertEquals("APPROVE", body.get("decision"));

        HumanReviewPending settled = pendingRepository.findById(fx.sharedId).orElseThrow();
        assertEquals("APPROVE", settled.getDecision(),
                "HumanReviewPending row must be settled APPROVE");
        assertNotNull(settled.getDecidedAt());

        String legacyStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.sharedId);
        assertEquals("APPROVED", legacyStatus,
                "Legacy approval row at the same id must be APPROVED via the SPI bridge");
    }

    @Test
    void decide_reject_settlesBothRowsAsReject() {
        String orgId = "org-tool-e2e-rej-" + shortUuid();
        HttpHeaders auth = authHeadersInOrg("tool-e2e-rejecter", orgId);

        DualTrackFixture fx = seedDualTrack(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + fx.sharedId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "reject"), auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("REJECT", resp.getBody().get("decision"));

        HumanReviewPending settled = pendingRepository.findById(fx.sharedId).orElseThrow();
        assertEquals("REJECT", settled.getDecision());

        String legacyStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.sharedId);
        assertEquals("REJECTED", legacyStatus,
                "Legacy approval row must be REJECTED via the SPI bridge");
    }

    @Test
    void decide_crossTenant_returns404AndLeavesBothRowsUntouched() {
        String orgA = "org-tool-e2e-a-" + shortUuid();
        String orgB = "org-tool-e2e-b-" + shortUuid();
        HttpHeaders crossAuth = authHeadersInOrg("tool-e2e-cross", orgB);

        DualTrackFixture fx = seedDualTrack(orgA);

        ResponseEntity<String> resp = rest.exchange(
                url(DECIDE_PATH + fx.sharedId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), crossAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant decide must 404 — existence-leak protection §79");

        // Neither row mutated.
        HumanReviewPending pending = pendingRepository.findById(fx.sharedId).orElseThrow();
        assertEquals(null, pending.getDecision(),
                "HumanReviewPending row must remain undecided after a cross-tenant 404");
        String legacyStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.sharedId);
        assertEquals("PENDING", legacyStatus,
                "Legacy approval row must remain PENDING after a cross-tenant 404");
    }

    @Test
    void decide_pendingWithoutMatchingLegacyApproval_settlesPendingAndReturns200() {
        // Bug B regression pin: when an externally-seeded HumanReviewPending row has no
        // matching legacy approvals row, the AgentToolResumeHandler bridge call
        // throws (ApprovalService.ResourceNotFoundException). Without REQUIRES_NEW
        // tx isolation, that exception poisoned the outer HumanReviewService.decide
        // transaction → 500 instead of 200 + pending settled. This test asserts the
        // intended contract: pending row settles, /decide returns 200, no legacy row.
        String orgId = "org-tool-e2e-no-legacy-" + shortUuid();
        HttpHeaders auth = authHeadersInOrg("tool-e2e-no-legacy", orgId);

        String pendingId = "pending-no-legacy-" + UUID.randomUUID();
        HumanReviewPending pending = new HumanReviewPending();
        pending.setId(pendingId);
        pending.setRunId("run-no-legacy-" + shortUuid());
        pending.setSubjectType("AGENT_TOOL_CALL");
        pending.setSubjectId("orphan_tool");
        pending.setOrgId(orgId);
        pending.setReason("orphan pending — no legacy approval");
        pending.setCreatedAt(Instant.now());
        pendingRepository.save(pending);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(DECIDE_PATH + pendingId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "decide must return 200 even when the legacy approvals bridge fails — "
                        + "REQUIRES_NEW propagation isolates the inner failure");
        assertEquals("APPROVE", resp.getBody().get("decision"));

        HumanReviewPending settled = pendingRepository.findById(pendingId).orElseThrow();
        assertEquals("APPROVE", settled.getDecision(),
                "outer pending row must settle even when the legacy bridge throws");

        Integer legacyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approvals WHERE id = ?", Integer.class, pendingId);
        assertEquals(0, legacyCount,
                "sanity: no legacy approvals row exists for this orphan pending id");
    }

    @Test
    void decide_idempotent_doubleDecideEchoesFirstAndDoesNotMutateLegacy() {
        String orgId = "org-tool-e2e-idem-" + shortUuid();
        HttpHeaders auth = authHeadersInOrg("tool-e2e-idem", orgId);

        DualTrackFixture fx = seedDualTrack(orgId);

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url(DECIDE_PATH + fx.sharedId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), auth), JSON_MAP);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("APPROVE", first.getBody().get("decision"));

        // Second call attempts the opposite decision — should be idempotent no-op.
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url(DECIDE_PATH + fx.sharedId + "/decide"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "reject"), auth), JSON_MAP);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals("APPROVE", second.getBody().get("decision"),
                "first decision wins; subsequent attempts are idempotent no-ops");

        // Legacy row must reflect the first decision only — second call must NOT mutate.
        String legacyStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.sharedId);
        assertEquals("APPROVED", legacyStatus,
                "Legacy approval must stay APPROVED (first decision), not toggled to REJECTED");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private record DualTrackFixture(String sharedId, String agentId, String sessionId, String runId) {}

    /**
     * Seeds both rows that HitlAdvisor (post-REQ-HR-4.5) writes for a tool-call pause:
     * <ul>
     *   <li>A legacy {@code approvals} row in PENDING state (what
     *       {@code ApprovalService.createApprovalRequest} produces for TIER_3_DESTRUCTIVE).</li>
     *   <li>A {@code human_review_pending} row with {@code subjectType=AGENT_TOOL_CALL}
     *       and the SAME id — the dual-tracking shape.</li>
     * </ul>
     */
    private DualTrackFixture seedDualTrack(String orgId) {
        String agentId = "agent-tool-e2e-" + shortUuid();
        String sessionId = "session-tool-e2e-" + shortUuid();
        String runId = "run-tool-e2e-" + shortUuid();
        String sharedId = "shared-tool-e2e-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at, org_id)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now(), ?)
                """, agentId, "Tool E2E Agent " + sharedId, orgId);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, "tool-e2e-user", orgId, agentId);

        // Legacy approvals row — what HitlAdvisor's ApprovalService.createApprovalRequest writes.
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'delete_database',
                        ?::jsonb, 'SYSTEM', 'TIER_3_DESTRUCTIVE', ?,
                        now(), now(), 0)
                """,
                sharedId, runId, sessionId, agentId, "{\"db\":\"prod\"}", orgId);

        // HumanReviewPending row at the SAME id — the REQ-HR-4.5 dual-tracking shape.
        HumanReviewPending pending = new HumanReviewPending();
        pending.setId(sharedId);
        pending.setRunId(runId);
        pending.setSubjectType("AGENT_TOOL_CALL");
        pending.setSubjectId("delete_database");
        pending.setOrgId(orgId);
        pending.setReason("Agent requires permission to execute: delete_database");
        pending.setCreatedAt(Instant.now());
        pending.setOptions(Map.of(
                "requiresConfirmation", true,
                "requiresUserInput", false,
                "requiresOutputReview", false,
                "onReject", "SKIP",
                "onTimeout", "AUTO_REJECT",
                "onError", "CANCEL"));
        pendingRepository.save(pending);

        return new DualTrackFixture(sharedId, agentId, sessionId, runId);
    }

    private HttpHeaders authHeadersInOrg(String label, String orgId) {
        // registerLoginWithOrg registers a user with ROLE_USER + ROLE_ADMIN — enough to clear
        // the @PreAuthorize gate on POST /approvals/{id}/decide.
        return registerLoginWithOrg(label + "-" + shortUuid(), orgId);
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
