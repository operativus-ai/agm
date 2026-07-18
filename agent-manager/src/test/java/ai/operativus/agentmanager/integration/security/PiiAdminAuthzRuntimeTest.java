package ai.operativus.agentmanager.integration.security;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the authz contract on every
 *   {@link ai.operativus.agentmanager.compute.api.PiiAdminController} endpoint. All six
 *   endpoints are gated by a class-level {@code @PreAuthorize("hasRole('ADMIN')")}
 *   (added in PR #968 — the PII policy dictionary is global with no {@code org_id}
 *   column, so without the gate any authenticated user could read/poison/delete the
 *   dictionary and cross-bind policies on other tenants' agents).
 *
 *   <p>Closes 6 {@code TODO: focused authz test (PR #968)} entries on
 *   {@link ai.operativus.agentmanager.arch.AdminEndpointCoverageArchTest#ADMIN_ENDPOINT_COVERAGE}
 *   — bumps each tag to {@code focused: PiiAdminAuthzRuntimeTest}.
 *
 *   <p>For each endpoint the matrix asserts:
 *   <ol>
 *     <li>Anonymous request → 401 (rejected at JWT filter)</li>
 *     <li>{@code ROLE_USER} request → 403 (rejected at the class-level
 *         {@code @PreAuthorize("hasRole('ADMIN')")} gate)</li>
 *     <li>{@code ROLE_ADMIN} request → not 401 and not 403 (proves the gate doesn't
 *         block admins; downstream handler behavior — FK violations on bind, missing-ID
 *         on delete, etc. — is intentionally NOT asserted here)</li>
 *   </ol>
 *
 *   <p><b>Synthetic ids</b>: path variables use {@code probe-} prefixed values; for the
 *   admin happy path on bind/unbind, the {@code agent_pii_policies} INSERT will fail the
 *   FK to {@code pii_policies} (random UUID has no row) and the global handler maps
 *   {@code DataIntegrityViolationException} to 409. That still satisfies "neither 401
 *   nor 403" — the gate-cleared contract is what's pinned, not service-layer behavior
 *   past the gate.
 *
 *   <p><b>POST body for createPolicy</b>: minimal valid {@code PiiPolicyDTO} JSON.
 *   The pattern field must be a compilable regex or
 *   {@link ai.operativus.agentmanager.core.exception.BusinessValidationException} fires
 *   before the gate (Spring validates {@code @RequestBody} after authz, so this is purely
 *   defensive in case the order ever flips).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class PiiAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final Map<String, Object> POLICY_BODY = Map.of(
            "name", "authz-probe-policy",
            "description", "for authz tests",
            "patternType", "REGEX",
            "pattern", "\\d{3}-\\d{2}-\\d{4}",
            "scrubStrategy", "REDACT",
            "enabled", true,
            "taxonomicCategory", "UNCATEGORIZED",
            "complianceFramework", "STANDARD");

    private static final UUID PROBE_POLICY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROBE_AGENT_ID = "probe-agent";

    private static final List<EndpointSpec> ENDPOINTS = List.of(
            new EndpointSpec("/api/v1/pii-policies",
                    HttpMethod.GET, null),
            new EndpointSpec("/api/v1/pii-policies",
                    HttpMethod.POST, POLICY_BODY),
            new EndpointSpec("/api/v1/pii-policies/" + PROBE_POLICY_ID,
                    HttpMethod.DELETE, null),
            new EndpointSpec("/api/v1/pii-policies/agents/" + PROBE_AGENT_ID,
                    HttpMethod.GET, null),
            new EndpointSpec("/api/v1/pii-policies/agents/" + PROBE_AGENT_ID
                    + "/bind/" + PROBE_POLICY_ID,
                    HttpMethod.POST, null),
            new EndpointSpec("/api/v1/pii-policies/agents/" + PROBE_AGENT_ID
                    + "/unbind/" + PROBE_POLICY_ID,
                    HttpMethod.DELETE, null));

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void unauthenticatedRequestsAreRejected401OnAllEndpoints() {
        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> resp = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body()),
                    String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                    "unauthenticated " + ep.method() + " " + ep.path()
                            + " must be rejected with 401; got " + resp.getStatusCode());
        }
    }

    @Test
    void roleUserIsForbidden403OnAllEndpoints() {
        HttpHeaders userAuth = authenticateAs("pii-authz-user",
                "pii-authz-user@test.local", "pass-pii-1234", List.of("ROLE_USER"));

        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> resp = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), userAuth),
                    String.class);
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                    "ROLE_USER " + ep.method() + " " + ep.path()
                            + " must hit the @PreAuthorize(\"hasRole('ADMIN')\") gate; got "
                            + resp.getStatusCode());
        }
    }

    @Test
    void roleAdminPassesAuthzGateOnAllEndpoints() {
        HttpHeaders adminAuth = authenticateAs("pii-authz-admin",
                "pii-authz-admin@test.local", "pass-pii-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> resp = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), adminAuth),
                    String.class);
            HttpStatusCode status = resp.getStatusCode();
            assertNotEquals(HttpStatus.UNAUTHORIZED, status,
                    "ROLE_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 401; got " + status);
            assertNotEquals(HttpStatus.FORBIDDEN, status,
                    "ROLE_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 403; got " + status);
        }
    }

    /**
     * Cross-tenant isolation matrix for the per-tenant PII dictionary (PR #979). Org A
     * creates a policy; Org B's admin must not be able to read, mutate, or even confirm
     * its existence. Each mutating endpoint that takes a {@code policyId} returns 404
     * (existence-leak protection — matches the convention from PR #972 cancelRun and
     * {@code ComplianceController.requireSameOrgOrSelf}). The list endpoint returns the
     * caller's tenant's dictionary only, with no rows from the foreign tenant.
     */
    @Test
    void crossOrgPolicyAccessIsIsolated() {
        HttpHeaders adminA = registerLoginWithOrg("pii-cross-a-admin", "org-pii-cross-a");
        HttpHeaders adminB = registerLoginWithOrg("pii-cross-b-admin", "org-pii-cross-b");

        // Org A creates a policy in its own tenant.
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/pii-policies"),
                HttpMethod.POST,
                new HttpEntity<>(POLICY_BODY, adminA),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "Org A admin must be able to create a policy in its own tenant");
        assertNotNull(created.getBody(), "create response must echo the persisted policy DTO");
        UUID orgAPolicyId = UUID.fromString((String) created.getBody().get("id"));

        // Org B's admin: every mutating endpoint that takes Org A's policyId must 404
        // (existence-leak protection — no information about the foreign policy leaks).
        ResponseEntity<String> deleteResp = rest.exchange(
                url("/api/v1/pii-policies/" + orgAPolicyId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminB),
                String.class);

        String foreignAgentId = "agent-cross-b";
        ResponseEntity<String> bindResp = rest.exchange(
                url("/api/v1/pii-policies/agents/" + foreignAgentId + "/bind/" + orgAPolicyId),
                HttpMethod.POST,
                new HttpEntity<>(adminB),
                String.class);
        ResponseEntity<String> unbindResp = rest.exchange(
                url("/api/v1/pii-policies/agents/" + foreignAgentId + "/unbind/" + orgAPolicyId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminB),
                String.class);

        // List endpoint: Org B's view must NOT include Org A's policy.
        ResponseEntity<List<Map<String, Object>>> orgBList = rest.exchange(
                url("/api/v1/pii-policies"),
                HttpMethod.GET,
                new HttpEntity<>(adminB),
                new ParameterizedTypeReference<>() {});

        // Org A's policy must still exist after Org B's mutation attempts.
        ResponseEntity<List<Map<String, Object>>> orgAList = rest.exchange(
                url("/api/v1/pii-policies"),
                HttpMethod.GET,
                new HttpEntity<>(adminA),
                new ParameterizedTypeReference<>() {});

        assertAll("PR #979 tenant scoping — Org B cannot observe or mutate Org A's policy",
                () -> assertEquals(HttpStatus.NOT_FOUND, deleteResp.getStatusCode(),
                        "DELETE on a foreign-tenant policyId must 404 (existence-leak): got "
                                + deleteResp.getStatusCode()),
                () -> assertEquals(HttpStatus.NOT_FOUND, bindResp.getStatusCode(),
                        "BIND on a foreign-tenant policyId must 404: got " + bindResp.getStatusCode()),
                () -> assertEquals(HttpStatus.NOT_FOUND, unbindResp.getStatusCode(),
                        "UNBIND on a foreign-tenant policyId must 404: got " + unbindResp.getStatusCode()),
                () -> assertEquals(HttpStatus.OK, orgBList.getStatusCode(),
                        "Org B list must succeed (tenant has zero policies, but the call is admin-cleared)"),
                () -> {
                    List<Map<String, Object>> bRows = orgBList.getBody();
                    assertNotNull(bRows, "Org B list body must be non-null (empty array, not null)");
                    assertTrue(bRows.stream()
                                    .noneMatch(p -> orgAPolicyId.toString().equals(String.valueOf(p.get("id")))),
                            "Org B's list must NOT contain Org A's policy id " + orgAPolicyId
                                    + "; got " + bRows);
                },
                () -> assertEquals(HttpStatus.OK, orgAList.getStatusCode()),
                () -> {
                    List<Map<String, Object>> aRows = orgAList.getBody();
                    assertNotNull(aRows);
                    assertTrue(aRows.stream()
                                    .anyMatch(p -> orgAPolicyId.toString().equals(String.valueOf(p.get("id")))),
                            "Org A's own policy must still exist after Org B's 404'd mutation attempts; got " + aRows);
                });
    }

    private record EndpointSpec(String path, HttpMethod method, Object body) {
    }
}
