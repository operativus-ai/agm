package com.operativus.agentmanager.integration.approvals;

import com.operativus.agentmanager.control.service.ApprovalService;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Black-box pins on the unhappy paths of
 *   {@code ApprovalService.resolveApprovalForOrg} — a 118-line method (complexity 17)
 *   whose happy paths and quorum-2nd-vote case are already pinned by
 *   {@link ApprovalsRuntimeTest}. This class fills the negative-space combinatorics:
 *   REJECT-during-quorum (single rejector wins), already-terminal state, invalid
 *   decision strings, missing ids, cross-tenant existence-leak protection, and
 *   GET /{id} status-agnostic + cross-tenant contract.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Plan: {@code .claude/plans/approval-hitl-runtime-coverage-2026-05-16.md} pins A1-A6.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalResolveRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private ApprovalService approvalService;

    @BeforeEach
    void seedModel() {
        // agents.model_id FK — every test seeds an agent_session, which needs the model row.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // ─── A1: REJECT during quorum terminates immediately ───

    // The design intent at ApprovalService.resolveApprovalForOrg:204 — "REJECT still
    // terminates immediately (single rejector wins by design)". This is asymmetric:
    // APPROVED waits for approvers_required votes, REJECTED needs only one. Pinning
    // this guards the asymmetry; a future refactor that treats REJECT symmetrically
    // (requiring N rejectors) would silently break the contract.
    @Test
    void rejectDuringQuorum_singleRejectorTerminatesImmediately() {
        String orgId = "org-ap1-a1-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap1-a1-rejector", orgId);

        // Seed an approval requiring 3 approvers with 1 prior APPROVED vote (1/3 toward
        // quorum). A single REJECT should override the in-progress APPROVE chain.
        Fixture fx = seedQuorumApproval(orgId, 3, List.of("alice"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "REJECTED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "REJECT-during-quorum must succeed even with prior APPROVE votes");
        assertEquals("REJECTED", resp.getBody().get("status"),
                "single REJECT must terminate immediately — quorum not required for rejection");

        String persistedStatus = jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertEquals("REJECTED", persistedStatus, "row must persist as REJECTED");

        String resolvedAt = jdbc.queryForObject(
                "SELECT resolved_at::text FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertNotNull(resolvedAt, "resolved_at must be stamped on terminal transition");
    }

    // ─── A2/A3: Resolve already-terminal approval returns 400 ───

    @Test
    void resolveAlreadyApprovedApproval_returns400() {
        String orgId = "org-ap1-a2-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap1-a2-actor", orgId);

        Fixture fx = seedApprovalInState(orgId, "APPROVED");
        String stateBefore = approvalStatus(fx.approvalId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "resolving a terminal approval must return 400 — BusinessValidationException at line 184");
        assertEquals(stateBefore, approvalStatus(fx.approvalId),
                "row state must be unchanged after a rejected resolve attempt");
    }

    @Test
    void resolveAlreadyExpiredApproval_returns400() {
        String orgId = "org-ap1-a3-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap1-a3-actor", orgId);

        Fixture fx = seedApprovalInState(orgId, "EXPIRED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "resolving an EXPIRED approval must return 400 — same path as APPROVED");
        assertEquals("EXPIRED", approvalStatus(fx.approvalId),
                "EXPIRED row must stay EXPIRED");
    }

    // ─── A4: Invalid decision string returns 400 ───

    @Test
    void resolveWithInvalidDecisionString_returns400() {
        String orgId = "org-ap1-a4-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap1-a4-actor", orgId);

        Fixture fx = seedPendingApproval(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "MAYBE"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "decision must be APPROVED or REJECTED — anything else throws BusinessValidationException at line 177");
        assertEquals("PENDING", approvalStatus(fx.approvalId),
                "row must stay PENDING — invalid decision must not mutate state");
    }

    // ─── A5: Resolve non-existent approval id returns 404 ───

    @Test
    void resolveNonExistentApprovalId_returns404() {
        String orgId = "org-ap1-a5-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap1-a5-actor", orgId);

        String missingId = "does-not-exist-" + shortUuid();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/approvals/" + missingId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "missing approval id must surface as 404 — ResourceNotFoundException at line 290");
    }

    // ─── A6: Cross-tenant resolve attempt returns 404 (not 403) ───

    @Test
    void crossTenantResolveAttempt_returns404_notLeakingExistence() {
        String orgA = "org-ap1-a6-a-" + shortUuid();
        String orgB = "org-ap1-a6-b-" + shortUuid();

        Fixture fx = seedPendingApproval(orgA);
        HttpHeaders orgBAuth = registerLoginWithOrg("ap1-a6-org-b-admin", orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), orgBAuth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant resolve must return 404 (existence-leak protection), NOT 403 — pins .filter at line 182");
        assertEquals("PENDING", approvalStatus(fx.approvalId),
                "org-A's approval must be untouched by org-B's call");
    }

    // ─── B: bulkResolveForOrg operator surface (POST /api/v1/approvals/bulk-resolve) ───

    // B1 — Bulk-approve happy path: 3 PENDING approvals in caller's org → resolved=3, failed=0.
    @Test
    void bulkResolve_happyPath_allPendingApproved() {
        String orgId = "org-ap2-b1-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap2-b1-actor", orgId);

        Fixture f1 = seedPendingApproval(orgId);
        Fixture f2 = seedPendingApproval(orgId);
        Fixture f3 = seedPendingApproval(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "ids", List.of(f1.approvalId, f2.approvalId, f3.approvalId),
                        "decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(3, ((Number) resp.getBody().get("resolved")).intValue(),
                "all 3 PENDING approvals must resolve cleanly");
        assertEquals(0, ((Number) resp.getBody().get("failed")).intValue(),
                "no failures expected on happy path");

        for (Fixture f : List.of(f1, f2, f3)) {
            assertEquals("APPROVED", approvalStatus(f.approvalId),
                    "row " + f.approvalId + " must be APPROVED after bulk-resolve");
        }
    }

    // B2 — Bulk-resolve partial failure: 2 PENDING + 1 already-APPROVED → resolved=2, failed=1.
    //      Proves independent-transaction design — the already-terminal row's failure does
    //      NOT roll back the two successful resolves.
    @Test
    void bulkResolve_partialFailure_independentTransactions() {
        String orgId = "org-ap2-b2-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap2-b2-actor", orgId);

        Fixture pending1 = seedPendingApproval(orgId);
        Fixture pending2 = seedPendingApproval(orgId);
        Fixture terminal = seedApprovalInState(orgId, "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "ids", List.of(pending1.approvalId, terminal.approvalId, pending2.approvalId),
                        "decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, ((Number) resp.getBody().get("resolved")).intValue(),
                "2 PENDING rows must resolve; 1 terminal row must fail — independent transactions");
        assertEquals(1, ((Number) resp.getBody().get("failed")).intValue(),
                "the already-APPROVED row's failure must NOT roll back the 2 successes");

        assertEquals("APPROVED", approvalStatus(pending1.approvalId),
                "PENDING #1 must be APPROVED");
        assertEquals("APPROVED", approvalStatus(pending2.approvalId),
                "PENDING #2 must be APPROVED");
        assertEquals("APPROVED", approvalStatus(terminal.approvalId),
                "pre-existing terminal row must still be APPROVED (unchanged)");
    }

    // B3 — Bulk-resolve cross-tenant ids silently counted as failures. Pins the docstring
    //      contract: "Cross-tenant ids are silently counted as failures … to avoid leaking
    //      tenant membership." Caller is in org-A; 1 of the 3 ids belongs to org-B.
    @Test
    void bulkResolve_crossTenantIds_silentlyCountedAsFailures() {
        String orgA = "org-ap2-b3-a-" + shortUuid();
        String orgB = "org-ap2-b3-b-" + shortUuid();
        HttpHeaders authA = registerLoginWithOrg("ap2-b3-org-a-admin", orgA);

        Fixture orgAPending1 = seedPendingApproval(orgA);
        Fixture orgAPending2 = seedPendingApproval(orgA);
        Fixture orgBPending = seedPendingApproval(orgB);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "ids", List.of(orgAPending1.approvalId, orgBPending.approvalId, orgAPending2.approvalId),
                        "decision", "APPROVED"), authA),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, ((Number) resp.getBody().get("resolved")).intValue(),
                "2 org-A rows must resolve");
        assertEquals(1, ((Number) resp.getBody().get("failed")).intValue(),
                "org-B row must count as a silent failure — no tenant-membership leak");

        assertEquals("APPROVED", approvalStatus(orgAPending1.approvalId));
        assertEquals("APPROVED", approvalStatus(orgAPending2.approvalId));
        assertEquals("PENDING", approvalStatus(orgBPending.approvalId),
                "org-B's row must be untouched — bulk-resolve must not bypass tenant isolation");
    }

    // B4 — Bulk-resolve with invalid decision: every item fails (per-call validation).
    @Test
    void bulkResolve_invalidDecision_everyItemFails() {
        String orgId = "org-ap2-b4-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap2-b4-actor", orgId);

        Fixture f1 = seedPendingApproval(orgId);
        Fixture f2 = seedPendingApproval(orgId);
        Fixture f3 = seedPendingApproval(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "ids", List.of(f1.approvalId, f2.approvalId, f3.approvalId),
                        "decision", "MAYBE"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, ((Number) resp.getBody().get("resolved")).intValue());
        assertEquals(3, ((Number) resp.getBody().get("failed")).intValue(),
                "every per-call BusinessValidationException must increment failed");

        for (Fixture f : List.of(f1, f2, f3)) {
            assertEquals("PENDING", approvalStatus(f.approvalId),
                    "row " + f.approvalId + " must stay PENDING — invalid decision must not mutate state");
        }
    }

    // B5 — Empty ids list: resolved=0, failed=0. No exception, no DB write.
    @Test
    void bulkResolve_emptyIdsList_zeroZero() {
        String orgId = "org-ap2-b5-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap2-b5-actor", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "ids", List.of(),
                        "decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, ((Number) resp.getBody().get("resolved")).intValue());
        assertEquals(0, ((Number) resp.getBody().get("failed")).intValue());
    }

    // ─── C: createApprovalRequest tier matrix + GET /{id} contract + principal attribution ───

    // C1 — Tier 2 (FINOPS_BLOCK) creates a PENDING approval, not auto-approved.
    //      The ternary at ApprovalService.createApprovalRequest:131 reads
    //      `tier == TIER_1_SAFE ? APPROVED : PENDING` — Tier 2 and 3 share the PENDING branch.
    @Test
    void createApprovalRequest_tier2FinopsBlock_stayspending() {
        Fixture base = seedAgentSessionFixture("c1-tier2-" + shortUuid());

        ApprovalDTO created = approvalService.createApprovalRequest(
                base.runId, base.sessionId, base.agentId,
                "spend-budget-tool", "{\"usd\":5000}", "needs-finops-review",
                "c1-tier2-user", null, null,
                DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK);

        assertEquals("PENDING", created.status().name(),
                "TIER_2_FINOPS_BLOCK must stay PENDING — only TIER_1_SAFE auto-approves");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, created.id()),
                "persisted row must reflect PENDING");
        assertEquals("TIER_2_FINOPS_BLOCK", jdbc.queryForObject(
                "SELECT decision_tier FROM approvals WHERE id = ?", String.class, created.id()),
                "decision_tier column must preserve the tier enum name");
    }

    // C2 — Null tier creates PENDING with tier="UNKNOWN". Pins the defensive default at
    //      ApprovalService.createApprovalRequest:137 (`tier != null ? tier.name() : "UNKNOWN"`).
    @Test
    void createApprovalRequest_nullTier_pendingWithUnknownLabel() {
        Fixture base = seedAgentSessionFixture("c2-null-tier-" + shortUuid());

        ApprovalDTO created = approvalService.createApprovalRequest(
                base.runId, base.sessionId, base.agentId,
                "ambiguous-tool", "{}", "no tier provided",
                "c2-user", null, null,
                /* tier = */ null);

        assertEquals("PENDING", created.status().name(),
                "null tier must NOT auto-approve — defensive default keeps it gated");
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT decision_tier FROM approvals WHERE id = ?", String.class, created.id()),
                "null tier must persist as 'UNKNOWN' per the defensive default");
    }

    // C3 — POST /resolve uses the authenticated principal as resolvedBy. Any extra fields
    //      in the request body (e.g., resolved_by) are silently dropped because
    //      ResolveDecisionRequest is a record with one field (`decision`). The principal
    //      flows from SecurityContextHolder, not from the body. Pins the controller
    //      docstring contract: "request-body fields are NOT honored, so a client cannot
    //      forge attribution".
    @Test
    void resolveAttribution_usesAuthenticatedPrincipal_ignoresBodyResolvedBy() {
        String orgId = "org-ap3-c3-" + shortUuid();
        String principalUsername = "ap3-c3-real-actor";
        HttpHeaders auth = registerLoginWithOrg(principalUsername, orgId);

        Fixture fx = seedPendingApproval(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "decision", "APPROVED",
                        // Attempt to forge attribution via the body — must be silently dropped.
                        "resolved_by", "forged-attacker",
                        "resolvedBy", "forged-attacker-camel"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("APPROVED", resp.getBody().get("status"));

        String persistedResolvedBy = jdbc.queryForObject(
                "SELECT resolved_by FROM approvals WHERE id = ?", String.class, fx.approvalId);
        assertEquals(principalUsername, persistedResolvedBy,
                "resolved_by must reflect the authenticated principal, NOT the body's forged value");
    }

    // C4 — GET /api/v1/approvals/{id} is status-agnostic: a resolved approval is still
    //      retrievable for audit / history views. Pins the controller docstring intent
    //      that explicitly contrasts /{id} with /pending (which filters by status).
    @Test
    void getApprovalById_returnsResolvedRow_statusAgnostic() {
        String orgId = "org-ap3-c4-" + shortUuid();
        HttpHeaders auth = registerLoginWithOrg("ap3-c4-actor", orgId);

        Fixture fx = seedApprovalInState(orgId, "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "GET /{id} must succeed for APPROVED rows (status-agnostic, unlike /pending)");
        assertEquals(fx.approvalId, resp.getBody().get("id"));
        assertEquals("APPROVED", resp.getBody().get("status"),
                "resolved status must round-trip");
    }

    // C5 — GET /{id} cross-tenant returns 404 so tenant-membership cannot be probed.
    //      Pins the controller docstring: "Cross-tenant lookups return 404 so
    //      tenant-membership cannot be probed."
    @Test
    void getApprovalById_crossTenant_returns404() {
        String orgA = "org-ap3-c5-a-" + shortUuid();
        String orgB = "org-ap3-c5-b-" + shortUuid();

        Fixture fx = seedApprovalInState(orgA, "APPROVED");
        HttpHeaders orgBAuth = registerLoginWithOrg("ap3-c5-org-b-actor", orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/approvals/" + fx.approvalId),
                HttpMethod.GET, new HttpEntity<>(orgBAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant GET /{id} must return 404 — existence-leak protection");
    }

    // ─── helpers ───

    private record Fixture(String approvalId, String agentId, String sessionId, String runId) {}

    private Fixture seedAgentSessionFixture(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Approval Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return new Fixture(null, agentId, sessionId, runId);
    }

    private Fixture seedPendingApproval(String orgId) {
        return seedApprovalInState(orgId, "PENDING");
    }

    private Fixture seedApprovalInState(String orgId, String status) {
        if (!status.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("status must be uppercase enum identifier; got: " + status);
        }
        String label = "state-" + status.toLowerCase();
        Fixture base = seedAgentSessionFixture(label + "-" + shortUuid());
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resolvedAt = "PENDING".equals(status) ? null : now;
        // Status interpolated inline (existing ApprovalsRuntimeTest helpers do the same)
        // because the approvals.status column is text-typed and bind params would need
        // an explicit ::run_status cast. The validation above guards against injection.
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       resolved_at, resolved_by,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, '%s', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?,
                        ?, ?,
                        now(), now(), 0)
                """.formatted(status),
                approvalId, base.runId, base.sessionId, base.agentId,
                "{\"db\":\"prod\"}", label + "-user", orgId,
                resolvedAt, "PENDING".equals(status) ? null : "prior-resolver");
        return new Fixture(approvalId, base.agentId, base.sessionId, base.runId);
    }

    /**
     * Seeds a PENDING approval requiring N approvers with the given prior APPROVE votes
     * already recorded. payload_hash left NULL (legacy-row path that skips T036(8) check)
     * so the resolveApprovalForOrg quorum branch is the only thing being exercised.
     */
    private Fixture seedQuorumApproval(String orgId, int approversRequired, List<String> priorVotes) {
        Fixture base = seedAgentSessionFixture("quorum-" + shortUuid());
        String approvalId = "approval-quorum-" + UUID.randomUUID();
        String votesJson = "[" + priorVotes.stream()
                .map(v -> "\"" + v + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       approvers_required, approved_by,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'destructive-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?,
                        ?, ?::jsonb,
                        now(), now(), 0)
                """,
                approvalId, base.runId, base.sessionId, base.agentId,
                "{\"db\":\"prod\"}", "quorum-user", orgId, approversRequired, votesJson);
        return new Fixture(approvalId, base.agentId, base.sessionId, base.runId);
    }

    private String approvalStatus(String approvalId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM approvals WHERE id = ?", String.class, approvalId);
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
