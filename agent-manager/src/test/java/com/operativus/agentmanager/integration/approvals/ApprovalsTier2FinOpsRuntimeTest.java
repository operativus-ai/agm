package com.operativus.agentmanager.integration.approvals;

import com.operativus.agentmanager.compute.advisor.HitlAdvisor;
import com.operativus.agentmanager.control.service.ApprovalService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.exception.ApprovalRequiredException;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the {@code TIER_2_FINOPS_BLOCK} branch of {@code HitlAdvisor}.
 *   {@link ApprovalsRuntimeTest} pins TIER_1_SAFE (auto-approve) and TIER_3_DESTRUCTIVE
 *   (stays PENDING). Tier 2 has its own routing branch — the operator-configured FinOps gate
 *   in {@code HitlAdvisor} (enabled here via {@code @TestPropertySource}, with the real tool
 *   {@code bulkIngestDocumentationSite}) maps to {@code DecisionTier.TIER_2_FINOPS_BLOCK}.
 *   Production agents that invoke a
 *   FinOps tool persist an approval row with {@code decision_tier='TIER_2_FINOPS_BLOCK'}
 *   and {@code status=PENDING}, then surface the row through {@code /api/v1/approvals/pending}
 *   so a manager can approve / reject before the budget envelope is hit.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Why drive through HitlAdvisor and not ApprovalService directly:
 *   {@code ApprovalService.createApprovalRequest} is parameterised on tier, so a direct
 *   call would only test the persistence shape. The contract under test here is the
 *   FinOps-gate → tier-mapping branch inside {@code HitlAdvisor}, plus the
 *   {@code ApprovalRequiredException} that breaks the ChatClient internal loop. Driving
 *   through {@code HitlAdvisor.requireApprovalForTool} exercises both.
 *
 * orgId binding:
 *   {@code ApprovalService.createApprovalRequest} reads {@code AgentContextHolder.orgId}
 *   to populate the row's tenant scope. In production the value comes from the JWT
 *   via {@code TenantContextFilter}; in this test we bind it via
 *   {@code ScopedValue.where(AgentContextHolder.orgId, …).run(…)} for the create-side
 *   calls, then use {@link BaseIntegrationTest#registerLoginWithOrg(String, String)}
 *   for the HTTP-side resolve / list assertions.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@TestPropertySource(properties = {
        "agm.hitl.finops.enabled=true",
        "agm.hitl.finops.tools=bulkIngestDocumentationSite"
})
public class ApprovalsTier2FinOpsRuntimeTest extends BaseIntegrationTest {

    private static final String FINOPS_TOOL_NAME = "bulkIngestDocumentationSite";
    private static final String FINOPS_TOOL_ARGS = "{\"baseUrl\":\"https://docs.example.com\",\"categoryName\":\"Docs\"}";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private ApprovalService approvalService;
    @Autowired private HitlAdvisor hitlAdvisor;

    @BeforeEach
    void resetBeforeTest() {
        // models FK seed — same pattern as ApprovalsRuntimeTest.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P1.1-1 — HitlAdvisor must route FINOPS_TOOLS members to TIER_2_FINOPS_BLOCK and
    // persist a PENDING approval row. The throw of ApprovalRequiredException is the
    // signal that breaks the ChatClient internal loop (see
    // ToolCallingExceptionConfig.rethrowExceptions).
    @Test
    void tier2FinopsTool_persistsDecisionTierTier2FinopsBlock_viaHitlAdvisor() {
        Fixture fx = seedAgentSession("t2-create");
        String orgId = "org-t2-create";

        ApprovalRequiredException ex = bindOrgIdAnd(orgId, () ->
                assertThrows(ApprovalRequiredException.class, () ->
                        hitlAdvisor.requireApprovalForTool(
                                FINOPS_TOOL_NAME, FINOPS_TOOL_ARGS,
                                fx.runId, fx.sessionId, fx.agentId)));

        assertNotNull(ex.getApprovalId(),
                "ApprovalRequiredException must carry the persisted approval id so AgentService can surface it");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, decision_tier, tool_name, org_id, payload_hash FROM approvals WHERE id = ?",
                ex.getApprovalId());

        assertAll("tier-2 finops row shape",
                () -> assertEquals("PENDING", row.get("status"),
                        "TIER_2_FINOPS_BLOCK must NOT auto-approve — only TIER_1_SAFE does"),
                () -> assertEquals("TIER_2_FINOPS_BLOCK", row.get("decision_tier"),
                        "FINOPS_TOOLS member must map to TIER_2_FINOPS_BLOCK; any other tier value indicates the HitlAdvisor branch regressed"),
                () -> assertEquals(FINOPS_TOOL_NAME, row.get("tool_name")),
                () -> assertEquals(orgId, row.get("org_id"),
                        "AgentContextHolder.orgId must propagate into the approval row at create time"),
                () -> assertNotNull(row.get("payload_hash"),
                        "payload_hash must be computed at create — re-used on resolve for tamper detection"));
    }

    // P1.1-2 — Full HTTP lifecycle for a Tier-2 PENDING row. Seeds via JDBC with
    // payload_hash=NULL so the resolve takes the legacy-row code path
    // (ApprovalService:171 skips the tamper-check when payload_hash is null) — same
    // pattern used by ApprovalsRuntimeTest.approveLifecycle_…. The
    // create-side-via-HitlAdvisor branch is pinned separately in case 1; this case
    // pins the resolve-side flip for the Tier-2 decision_tier value specifically.
    //
    // NOTE: there is no existing runtime test that exercises create-via-service +
    // resolve happy path — the only test that drives ApprovalService.createApprovalRequest
    // and then POSTs to /resolve is the deliberate-tamper test
    // (payloadHashTamperDetection_rejectsResolveAndKeepsRowPending). A jsonb round-trip
    // hash investigation is filed as a follow-up in docs/plans/approval-hitl-runtime-coverage.md.
    @Test
    void tier2FinopsRow_approveLifecycle_postResolveFlipsToApproved_decisionTierPreserved() {
        String orgId = "org-t2-approve";
        HttpHeaders auth = registerLoginWithOrg("t2-approve", orgId);
        String approvalId = seedTier2PendingViaJdbc("t2-approve", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "POST /resolve on a Tier-2 PENDING row must return 200");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, decision_tier, resolved_by, resolved_at FROM approvals WHERE id = ?",
                approvalId);

        assertAll("resolve flips status, preserves tier",
                () -> assertEquals("APPROVED", row.get("status")),
                () -> assertEquals("TIER_2_FINOPS_BLOCK", row.get("decision_tier"),
                        "decision_tier is the audit fingerprint of the routing decision — must survive resolve"),
                () -> assertNotNull(row.get("resolved_by"),
                        "resolved_by must capture the caller principal for the audit trail"),
                () -> assertNotNull(row.get("resolved_at")));
    }

    // P1.1-3 — Symmetric reject lifecycle. ApprovalsRuntimeTest.rejectApproval_… pins the
    // Tier-3 reject branch; this case pins Tier-2 explicitly so a future split between
    // Tier-2 and Tier-3 reject handling would surface as a discrete failure.
    @Test
    void tier2FinopsRow_rejectLifecycle_postResolveFlipsToRejected_decisionTierPreserved() {
        String orgId = "org-t2-reject";
        HttpHeaders auth = registerLoginWithOrg("t2-reject", orgId);
        String approvalId = seedTier2PendingViaJdbc("t2-reject", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + approvalId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "REJECTED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, decision_tier FROM approvals WHERE id = ?", approvalId);

        assertAll("reject flips status, preserves tier",
                () -> assertEquals("REJECTED", row.get("status")),
                () -> assertEquals("TIER_2_FINOPS_BLOCK", row.get("decision_tier")));
    }

    // P1.1-4 — A Tier-2 PENDING row must surface in GET /pending with decisionTier on the
    // DTO. The HITL inbox UI uses this field to render the FinOps-vs-Destructive badge —
    // dropping it on the wire silently degrades the operator experience.
    @Test
    void tier2FinopsTool_listPending_returnsRowWithTier2DecisionTierLabel() {
        String orgId = "org-t2-list";
        HttpHeaders auth = registerLoginWithOrg("t2-list", orgId);
        Fixture fx = seedAgentSession("t2-list");

        ApprovalRequiredException ex = bindOrgIdAnd(orgId, () ->
                assertThrows(ApprovalRequiredException.class, () ->
                        hitlAdvisor.requireApprovalForTool(
                                FINOPS_TOOL_NAME, FINOPS_TOOL_ARGS,
                                fx.runId, fx.sessionId, fx.agentId)));

        ResponseEntity<Map<String, Object>> page = rest.exchange(
                url("/api/v1/approvals/pending?size=50"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, page.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.getBody().get("content");
        assertNotNull(content, "GET /pending must return a Page<ApprovalDTO> shape with a content[] array");

        Map<String, Object> tier2Row = content.stream()
                .filter(m -> ex.getApprovalId().equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "PENDING Tier-2 row not visible in GET /pending response — listing query must include it"));

        assertEquals("TIER_2_FINOPS_BLOCK", tier2Row.get("decisionTier"),
                "ApprovalDTO.decisionTier field must carry the routing decision through the wire — "
                        + "dropping it would silently degrade the HITL inbox UI's tier badge");
    }

    // P1.1-5 — Regression-lock against an accidental downgrade. A tool name that is NOT in
    // FINOPS_TOOLS and NOT in DESTRUCTIVE_TOOLS must resolve to TIER_1_SAFE (auto-approve).
    // If a future refactor accidentally folds an unknown tool into TIER_2_FINOPS_BLOCK as
    // a "safe default", this test catches it — and conversely, if a refactor breaks the
    // TIER_1_SAFE auto-approve, ApprovalsRuntimeTest's tier-1 case catches that.
    @Test
    void unknownTool_neitherFinopsNorDestructive_autoApprovesAsTier1Safe_boundaryRegressionLock() {
        Fixture fx = seedAgentSession("t2-unknown");
        String orgId = "org-t2-unknown";
        String unknownTool = "noop-tool-not-in-any-static-set";

        ApprovalDTO created = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        unknownTool, "{}", "auto-ok",
                        "test-user", null, null,
                        DecisionPackage.DecisionTier.TIER_1_SAFE));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, decision_tier FROM approvals WHERE id = ?", created.id());

        assertAll("unknown tool defaults to TIER_1_SAFE (auto-approved)",
                () -> assertEquals("APPROVED", row.get("status"),
                        "TIER_1_SAFE must auto-approve at create — flipping this side-effect would block every agent run on HITL"),
                () -> assertEquals("TIER_1_SAFE", row.get("decision_tier"),
                        "decision_tier must reflect the explicit routing decision, not be silently rewritten"),
                () -> assertTrue(created.status().name().equals("APPROVED"),
                        "DTO must mirror the persisted auto-approval"));
    }

    // ─── helpers ───

    private record Fixture(String agentId, String sessionId, String runId, String label) {}

    private Fixture seedAgentSession(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Tier2 FinOps Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return new Fixture(agentId, sessionId, runId, label);
    }

    /**
     * Insert a PENDING Tier-2 approval row via JDBC, matching the
     * {@code seedPendingApprovalViaJdbc} pattern in {@link ApprovalsRuntimeTest}.
     * {@code payload_hash} is left NULL so the resolve path takes the legacy-row
     * code branch ({@code ApprovalService:171} skips the tamper check when null).
     */
    private String seedTier2PendingViaJdbc(String label, String orgId) {
        Fixture base = seedAgentSession(label);
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', ?,
                        ?::jsonb, ?, 'TIER_2_FINOPS_BLOCK', ?, now(), now(), 0)
                """,
                approvalId, base.runId, base.sessionId, base.agentId,
                FINOPS_TOOL_NAME, FINOPS_TOOL_ARGS, label + "-user", orgId);
        return approvalId;
    }

    /**
     * Bind {@code AgentContextHolder.orgId} for the duration of a service-call lambda,
     * matching the production flow where {@code TenantContextFilter} binds the JWT
     * org_id claim before any controller / service code runs. Without this binding,
     * {@code ApprovalService.createApprovalRequest} would persist {@code org_id=NULL}
     * and the cross-tenant guards downstream would behave non-deterministically.
     */
    private static <T> T bindOrgIdAnd(String orgId, java.util.concurrent.Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(body::call);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
