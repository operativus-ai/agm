package ai.operativus.agentmanager.integration.approvals;

import ai.operativus.agentmanager.control.service.ApprovalService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.ApprovalDTO;
import ai.operativus.agentmanager.core.model.DecisionPackage;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Regression-lock for the {@code payload_hash} jsonb round-trip
 *   bug discovered while authoring {@link ApprovalsTier2FinOpsRuntimeTest}.
 *   {@code ApprovalService.createApprovalRequest} stores {@code tool_arguments} into a
 *   {@code @JdbcTypeCode(SqlTypes.JSON)} column; Postgres jsonb normalizes whitespace
 *   and key order on persist. The hash is computed at create time from the raw INPUT
 *   string; at resolve time it is recomputed from the entity getter, which reflects
 *   the round-tripped (Postgres-normalized) value. The two strings differ →
 *   {@code BusinessValidationException("Approval payload integrity check failed")} →
 *   HTTP 400. Production HITL via {@code HitlAdvisor.requireApprovalForTool} was
 *   broken end-to-end and the only existing test on the hash path
 *   ({@code payloadHashTamperDetection_…}) was a deliberate-fail tamper test that
 *   masked the regression.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Fix in this PR: after {@code approvalRepository.save(approval)}, flush and refresh
 * the entity so {@code tool_arguments} reflects the jsonb-round-tripped value, then
 * recompute and persist the hash. This guarantees create-time and resolve-time hashes
 * are computed on the same canonical (Postgres-normalized) string.
 *
 * Test surface: drives the production path verbatim — non-trivial JSON with multiple
 * keys (where Postgres reorders) and embedded whitespace.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ApprovalsCreateViaServiceResolveRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private ApprovalService approvalService;

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // FIX-A-1 — Multi-key JSON: Postgres jsonb may reorder keys + add whitespace on persist.
    // The pre-fix code hashed on the input string; post-fix code refreshes the entity
    // first and hashes on the round-tripped form. Both paths must converge to the same
    // canonical form so resolve's recompute-and-compare succeeds.
    @Test
    void createViaService_thenResolveViaHttp_multiKeyJson_returns200() {
        String orgId = "org-fix-payload-multi";
        HttpHeaders auth = registerLoginWithOrg("fix-payload-multi", orgId);
        Fixture fx = seedAgentSession("fix-multi");

        ApprovalDTO created = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        "train_model",
                        "{\"model\":\"big-llm\",\"epochs\":50,\"lr\":0.001,\"batch_size\":64}",
                        "Needs approval", "test-user",
                        "trace", "impact",
                        DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + created.id() + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("service-created multi-key JSON resolves cleanly post-fix",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "service-created approval must resolve via HTTP — payload_hash jsonb "
                                + "round-trip must NOT break the integrity check"),
                () -> assertEquals("APPROVED", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, created.id())));
    }

    // FIX-A-2 — Single-key JSON: simpler input where Postgres normalization may be no-op.
    // Still a regression-lock: if a future change accidentally re-introduces the
    // pre-fix code path, this case may pass (single key = no reorder) but case 1 would
    // catch it. Having both ensures coverage regardless of the specific perturbation
    // Postgres applies.
    @Test
    void createViaService_thenResolveViaHttp_singleKeyJson_returns200() {
        String orgId = "org-fix-payload-single";
        HttpHeaders auth = registerLoginWithOrg("fix-payload-single", orgId);
        Fixture fx = seedAgentSession("fix-single");

        ApprovalDTO created = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        "delete_database", "{\"db\":\"prod\"}",
                        "Needs approval", "test-user",
                        "trace", "impact",
                        DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + created.id() + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM approvals WHERE id = ?", String.class, created.id()));
    }

    // FIX-A-3 — Tamper-detection still works post-fix. Seed via service, mutate
    // tool_arguments via JDBC out-of-band, then attempt resolve — must still 400.
    // The fix must NOT weaken the integrity check, only ensure legitimate resolves
    // succeed.
    @Test
    void postFix_tamperDetectionStillFires_directDbMutationOfToolArgumentsRejectsResolve() {
        String orgId = "org-fix-payload-tamper";
        HttpHeaders auth = registerLoginWithOrg("fix-payload-tamper", orgId);
        Fixture fx = seedAgentSession("fix-tamper");

        ApprovalDTO created = bindOrgIdAnd(orgId, () ->
                approvalService.createApprovalRequest(
                        fx.runId, fx.sessionId, fx.agentId,
                        "delete_database", "{\"db\":\"prod\"}",
                        "Needs approval", "test-user",
                        "trace", "impact",
                        DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE));

        // Out-of-band tamper: direct UPDATE of tool_arguments after creation.
        // payload_hash was computed on the original {"db":"prod"} — recompute at
        // resolve time will see {"db":"staging"} and fail.
        jdbc.update("UPDATE approvals SET tool_arguments = ?::jsonb WHERE id = ?",
                "{\"db\":\"staging\"}", created.id());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/approvals/" + created.id() + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVED"), auth),
                JSON_MAP);

        assertAll("tamper-detection contract preserved post-fix",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "out-of-band tool_arguments mutation must still reject resolve — "
                                + "the fix must not weaken the integrity guard"),
                () -> assertEquals("PENDING", jdbc.queryForObject(
                        "SELECT status FROM approvals WHERE id = ?", String.class, created.id()),
                        "tampered row must stay PENDING — no resolve happened"));
    }

    // ─── helpers ───

    private record Fixture(String agentId, String sessionId, String runId) {}

    private Fixture seedAgentSession(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        String runId = "run-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Fix Payload Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return new Fixture(agentId, sessionId, runId);
    }

    private static <T> T bindOrgIdAnd(String orgId, java.util.concurrent.Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(body::call);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
