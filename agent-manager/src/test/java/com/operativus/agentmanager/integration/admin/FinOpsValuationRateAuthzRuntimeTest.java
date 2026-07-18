package com.operativus.agentmanager.integration.admin;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Focused authz pin for {@code PUT /api/v1/finops/valuation-rates},
 *   the global token-to-USD rate table on
 *   {@link com.operativus.agentmanager.control.controller.FinOpsAdminController}.
 *
 *   <p><b>Threat model.</b> {@code finops_valuation_rate} has {@code model_id} as its
 *   PK with no {@code org_id} column &mdash; the rate is shared across every tenant.
 *   A tenant ADMIN allowed to mutate it could corrupt every other tenant's cost-reporting
 *   fidelity (set $0/1k &rArr; all spend underreported; set $1000/1k &rArr; every
 *   tenant's burn-rate monitor fires; budgets exceeded). Rate edits are a platform-
 *   operator concern, not a tenant-admin concern.
 *
 *   <p>The class-level gate on {@code FinOpsAdminController} is {@code ROLE_ADMIN}; the
 *   method-level {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} on
 *   {@code updateValuationRate} narrows the write path while leaving the read +
 *   per-agent baseline-write surface (#1007) accessible to tenant admins.
 *
 *   <p>4-case matrix (mirrors {@link ComposioCatalogAdminAuthzRuntimeTest}):
 *   <ol>
 *     <li>Anonymous &rarr; 401</li>
 *     <li>ROLE_USER &rarr; 403</li>
 *     <li>ROLE_ADMIN &rarr; 403 (regression guard: if this flips to 2xx then the
 *         method-level gate was removed or the role hierarchy was inverted)</li>
 *     <li>ROLE_SUPER_ADMIN &rarr; non-401/non-403 (gate cleared; handler may return
 *         200 OK with the upserted rate)</li>
 *   </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class FinOpsValuationRateAuthzRuntimeTest extends BaseIntegrationTest {

    private static final String PATH = "/api/v1/finops/valuation-rates";

    // ValuationRateRequest carries @NotBlank/@PositiveOrZero on modelId + rate fields.
    // An empty body would 400 at the validation layer before @PreAuthorize is reached
    // (Spring evaluates @Valid before the security aspect for @RequestBody handlers);
    // supply minimally-valid values so the test exercises the gate, not validation.
    private static final Map<String, Object> VALID_BODY = Map.of(
            "modelId", "authz-probe-model",
            "inputRatePerKTokens", 1.0,
            "outputRatePerKTokens", 2.0,
            "cachedInputRatePerKTokens", 0.0,
            "reasoningRatePerKTokens", 0.0);

    @Test
    void updateValuationRate_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.PUT,
                new HttpEntity<>(VALID_BODY, HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void updateValuationRate_roleUser_returns403() {
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.PUT,
                new HttpEntity<>(VALID_BODY, userHeaders("vr-user")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void updateValuationRate_roleAdmin_returns403_becauseGateIsSuperAdmin() {
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.PUT,
                new HttpEntity<>(VALID_BODY, adminHeaders("vr-admin")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_ADMIN must NOT clear the SUPER_ADMIN-only gate on the global "
                        + "valuation-rate write. A 2xx here means the method-level "
                        + "@PreAuthorize was removed or the role hierarchy was inverted "
                        + "(both regressions). got " + resp.getStatusCode());
    }

    @Test
    void updateValuationRate_roleSuperAdmin_clearsGate() {
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.PUT,
                new HttpEntity<>(VALID_BODY, superAdminHeaders("vr-super")), String.class);
        HttpStatusCode code = resp.getStatusCode();
        assertNotEquals(HttpStatus.UNAUTHORIZED, code, "401 means JWT filter rejected");
        assertNotEquals(HttpStatus.FORBIDDEN, code,
                "403 means the SUPER_ADMIN gate refused a SUPER_ADMIN principal — bug");
    }

    private HttpHeaders userHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-vr-1234", List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-vr-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders superAdminHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-vr-1234", List.of("ROLE_SUPER_ADMIN"));
    }
}
