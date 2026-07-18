package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pin the partial-failure contract of {@code POST
 *   /api/v1/approvals/bulk-resolve}. The endpoint accepts a list of approval ids + a shared
 *   decision and returns a {@code BulkResolveResponse(int resolved, int failed)} —
 *   per-id outcomes are intentionally collapsed into two counters so a probe for
 *   tenant-membership (or row existence) cannot be inferred from the response. Internally,
 *   each id is resolved in its own {@code Propagation.REQUIRES_NEW} transaction so one
 *   failure does not roll back the successful resolves.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Failure modes that collapse into the {@code failed} counter (per
 * {@code BulkResolveResponse} javadoc + {@code ApprovalService.bulkResolveForOrg} catch
 * block):
 *   - Missing rows (id not in DB)
 *   - Cross-tenant ids (row exists but {@code org_id != callerOrgId} → {@code .filter} drops)
 *   - Already-resolved rows (status != PENDING)
 *   - Payload-hash mismatches (tamper detection)
 *
 * Plan called for per-id outcomes; the live wire-shape is two counters only. Tests pin
 * the counter contract — discovery noted during implementation.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsBulkResolvePartialFailureRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P1.4-1 — Happy path: every id is a valid PENDING row in the caller's org. Pins the
    // success counter increments and that every row actually flips state in DB.
    @Test
    void bulkResolve_allValidPendingIdsInCallerOrg_resolvedCountEqualsIdsLengthAndFailedZero() {
        String orgId = "org-bulk-allvalid";
        HttpHeaders auth = registerLoginWithOrg("bulk-allvalid", orgId);
        String id1 = seedPendingApprovalViaJdbc("bulk-1", orgId);
        String id2 = seedPendingApprovalViaJdbc("bulk-2", orgId);
        String id3 = seedPendingApprovalViaJdbc("bulk-3", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(id1, id2, id3), "decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("all-valid bulk resolve",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(3, ((Number) resp.getBody().get("resolved")).intValue()),
                () -> assertEquals(0, ((Number) resp.getBody().get("failed")).intValue()),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, id1)),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, id2)),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, id3)));
    }

    // P1.4-2 — Empty ids array. No @NotEmpty / @Size validation on BulkResolveRequest, so
    // this is a 200 + (0,0) — NOT a 400. Pinning this prevents a future @Valid tightening
    // from silently breaking a client that sends `{"ids":[], "decision":"APPROVED"}` as
    // a no-op (e.g., a UI that builds the list from a checkbox set and submits empty).
    @Test
    void bulkResolve_emptyIdsArray_returns200WithZeroCounts() {
        String orgId = "org-bulk-empty";
        HttpHeaders auth = registerLoginWithOrg("bulk-empty", orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(), "decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("empty bulk is a no-op",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(0, ((Number) resp.getBody().get("resolved")).intValue()),
                () -> assertEquals(0, ((Number) resp.getBody().get("failed")).intValue()));
    }

    // P1.4-3 — Cross-tenant ids collapse into the failed counter. This is the
    // load-bearing tenant-isolation guarantee for the bulk path: an attacker iterating
    // approval ids across tenants CANNOT distinguish "row exists in other org" from
    // "row missing" — both manifest as failed++ with no per-id breakdown.
    @Test
    void bulkResolve_crossTenantId_collapsesToFailedCounter_andTargetRowUnchanged() {
        String orgA = "org-bulk-A";
        String orgB = "org-bulk-B";
        HttpHeaders authA = registerLoginWithOrg("bulk-tenant-A", orgA);

        String idInOrgA = seedPendingApprovalViaJdbc("bulk-org-a", orgA);
        String idInOrgB = seedPendingApprovalViaJdbc("bulk-org-b", orgB);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(idInOrgA, idInOrgB), "decision", "APPROVED"), authA),
                JSON_MAP);

        assertAll("cross-tenant id silently bucketed as failed",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(1, ((Number) resp.getBody().get("resolved")).intValue(),
                        "caller's own org row must resolve"),
                () -> assertEquals(1, ((Number) resp.getBody().get("failed")).intValue(),
                        "cross-tenant id must count as failed — and only as failed; no per-id breakdown leaks tenant membership"),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, idInOrgA)),
                () -> assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, idInOrgB),
                        "cross-tenant row MUST stay PENDING — REQUIRES_NEW isolation must not leak the resolve attempt"));
    }

    // P1.4-4 — Already-resolved row collapses to failed. BusinessValidationException
    // "Cannot resolve approval in state: APPROVED" fires inside the per-id transaction;
    // outer counts it as failed. The original valid row in the same batch must still
    // resolve — proving REQUIRES_NEW isolation prevents the inner exception from marking
    // the outer transaction rollback-only.
    @Test
    void bulkResolve_alreadyResolvedRow_collapsesToFailed_freshRowStillResolves() {
        String orgId = "org-bulk-terminal";
        HttpHeaders auth = registerLoginWithOrg("bulk-terminal", orgId);

        String freshId = seedPendingApprovalViaJdbc("bulk-fresh", orgId);
        String alreadyResolvedId = seedAlreadyResolvedApproval("bulk-terminal", orgId, "APPROVED");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(freshId, alreadyResolvedId), "decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("terminal-row failure does not roll back the fresh resolve",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(1, ((Number) resp.getBody().get("resolved")).intValue()),
                () -> assertEquals(1, ((Number) resp.getBody().get("failed")).intValue()),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, freshId),
                        "REQUIRES_NEW must isolate the inner BusinessValidationException — fresh resolve commits"));
    }

    // P1.4-5 — Unknown id (no row at all) collapses to failed; same indistinguishability
    // contract as cross-tenant. ResourceNotFoundException fires inside per-id tx; outer
    // counts it as failed.
    @Test
    void bulkResolve_unknownId_collapsesToFailed_validIdsStillResolve() {
        String orgId = "org-bulk-unknown";
        HttpHeaders auth = registerLoginWithOrg("bulk-unknown", orgId);

        String validId = seedPendingApprovalViaJdbc("bulk-real", orgId);
        String unknownId = "approval-does-not-exist-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/bulk-resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(validId, unknownId), "decision", "APPROVED"), auth),
                JSON_MAP);

        assertNotNull(resp.getBody());
        assertAll("unknown id silently bucketed as failed",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(1, ((Number) resp.getBody().get("resolved")).intValue()),
                () -> assertEquals(1, ((Number) resp.getBody().get("failed")).intValue(),
                        "missing-row id must count as failed — indistinguishable from cross-tenant or terminal"));
    }

    // ─── helpers ───

    private String seedPendingApprovalViaJdbc(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Bulk Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, decision_tier, org_id,
                                       created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'bulk-tool',
                        ?::jsonb, ?, 'TIER_3_DESTRUCTIVE', ?, now(), now(), 0)
                """,
                approvalId, runId, sessionId, agentId,
                "{\"k\":\"v\"}", label + "-user", orgId);
        return approvalId;
    }

    private String seedAlreadyResolvedApproval(String label, String orgId, String terminalStatus) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        String approvalId = "approval-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Bulk Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, resolved_by, decision_tier,
                                       org_id, created_at, updated_at, resolved_at, version)
                VALUES (?, ?, ?, ?, CAST(? AS varchar), 'bulk-tool',
                        ?::jsonb, ?, ?, 'TIER_3_DESTRUCTIVE',
                        ?, now(), now(), now(), 0)
                """,
                approvalId, runId, sessionId, agentId, terminalStatus,
                "{\"k\":\"v\"}", label + "-user", label + "-resolver", orgId);
        return approvalId;
    }
}
