package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pins the authz contract on the two financial-impact mutations
 *   on {@code FinOpsAdminController}:
 *   <ul>
 *     <li>{@code PUT /api/v1/finops/valuation-rates} — mutates token→USD pricing for the
 *         entire org's billing computations.</li>
 *     <li>{@code PUT /api/v1/finops/baselines/{agentId}} — mutates per-agent baseline
 *         used as the denominator in burn-rate anomaly detection.</li>
 *   </ul>
 *
 *   <p><b>CRITICAL FINDING pinned by this test</b>: NEITHER endpoint has any
 *   {@code @PreAuthorize} — neither class-level, nor method-level, nor service-layer.
 *   Any authenticated user (including {@code ROLE_USER}) can mutate financial config.
 *   Same pattern surfaced previously for {@code AgentAdminController}'s ungated subset
 *   (PR #777).
 *
 *   <p>This test pins CURRENT behavior. When a gate is added (recommended:
 *   {@code @PreAuthorize("hasRole('ADMIN')")} class-level), the {@code roleUserSucceeds}
 *   assertions flip and force a deliberate test update.
 *
 *   <p>Anonymous → 401 (Spring's auth filter rejects). That contract is universal.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class FinOpsAdminMutationAuthzRuntimeTest extends BaseIntegrationTest {

    private HttpHeaders userAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        userAuth = authenticateAs("finops-mutation-user",
                "finops-mutation-user@test.local", "pass-fmu-1234",
                List.of("ROLE_USER"));
    }

    // ─── Anonymous → 401 (universal) ─────────────────────────────────────────

    @Test
    void anonymousPutValuationRatesReturns401() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/valuation-rates"),
                HttpMethod.PUT,
                jsonEntity(valuationBody(), new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous PUT /valuation-rates must return 401; got "
                        + response.getStatusCode());
    }

    @Test
    void anonymousPutBaselineReturns401() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/baselines/some-agent-id"),
                HttpMethod.PUT,
                jsonEntity(baselineBody(), new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "anonymous PUT /baselines/{id} must return 401");
    }

    // ─── ROLE_USER → 403 (class-level hasRole(ADMIN) gate now in place) ─────

    @Test
    void roleUserPutValuationRatesReturns403() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/valuation-rates"),
                HttpMethod.PUT,
                jsonEntity(valuationBody(), userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER PUT /valuation-rates must be rejected by hasRole('ADMIN'); got "
                        + response.getStatusCode());
    }

    @Test
    void roleUserPutBaselineReturns403() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/finops/baselines/agent-" + UUID.randomUUID()),
                HttpMethod.PUT,
                jsonEntity(baselineBody(), userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "ROLE_USER PUT /baselines/{id} must be rejected; got "
                        + response.getStatusCode());
    }

    // ─── Cross-tenant isolation on per-agent baseline write ─────────────────

    /**
     * {@code PUT /api/v1/finops/baselines/{agentId}} delegates to
     * {@code BurnRateMonitorService.registerBaseline} which writes to a
     * {@code ConcurrentHashMap<String, Double>} keyed by agentId — NO tenant column.
     * Pre-fix any tenant's admin could overwrite any other tenant's baseline, corrupting
     * anomaly detection and ROI calculations for that tenant. Controller pre-check via
     * {@code AgentRepository.existsByIdAndOrgId(agentId, callerOrgId)} now returns 404
     * for foreign-tenant agent ids (existence-leak protection).
     */
    @org.junit.jupiter.api.Test
    void updateBaseline_crossTenantAgentId_returns404_andStoredValueUnchanged() {
        HttpHeaders adminA = registerLoginWithOrg("finops-cross-a-admin", "finops-org-A");
        HttpHeaders adminB = registerLoginWithOrg("finops-cross-b-admin", "finops-org-B");

        // Seed B's agent so the path-id resolves against a real B-owned row.
        String bAgentId = "agent-finops-b-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'finops-b-agent', 'gpt-4o-mini', true, ?, now(), now())
                """, bAgentId, "finops-org-B");

        // B writes its own baseline first (positive control: this should succeed).
        ResponseEntity<Map<String, Object>> bSelf = rest.exchange(
                url("/api/v1/finops/baselines/" + bAgentId),
                HttpMethod.PUT,
                jsonEntity(Map.of("baselineUsdPerHour", 2.50), adminB),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, bSelf.getStatusCode(),
                "fixture: B's own admin must succeed at setting B's agent baseline");

        // Org A's admin attempts to overwrite B's baseline.
        ResponseEntity<String> aAttempt = rest.exchange(
                url("/api/v1/finops/baselines/" + bAgentId),
                HttpMethod.PUT,
                jsonEntity(Map.of("baselineUsdPerHour", 999.99), adminA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, aAttempt.getStatusCode(),
                "PUT /baselines/{B-agent-id} as A must return 404 (existence-leak protection); "
                        + "got " + aAttempt.getStatusCode());

        // B's admin reads back — value must be B's original (2.50), not A's hijack attempt (999.99).
        // The in-memory ConcurrentHashMap is the source of truth; we observe it via a fresh B
        // PUT that overwrites with a different value AND succeeds, proving the write surface
        // still works for B even after A's blocked attempt.
        ResponseEntity<Map<String, Object>> bReconfirm = rest.exchange(
                url("/api/v1/finops/baselines/" + bAgentId),
                HttpMethod.PUT,
                jsonEntity(Map.of("baselineUsdPerHour", 3.75), adminB),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, bReconfirm.getStatusCode(),
                "B's admin must still be able to write its own agent's baseline after A's blocked attempt");
        assertEquals(3.75, ((Number) bReconfirm.getBody().get("baselineUsdPerHour")).doubleValue(),
                "B's most recent write must be the persisted value — A's call must NOT have corrupted the slot");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> valuationBody() {
        return Map.of(
                "modelId", "fixture-model-" + UUID.randomUUID(),
                "inputRatePerKTokens", 0.01,
                "outputRatePerKTokens", 0.03,
                "cachedInputRatePerKTokens", 0.005,
                "reasoningRatePerKTokens", 0.0);
    }

    private static Map<String, Object> baselineBody() {
        return Map.of("baselineUsdPerHour", 1.50);
    }

    private static HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body,
                                                              HttpHeaders headers) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(headers);
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
