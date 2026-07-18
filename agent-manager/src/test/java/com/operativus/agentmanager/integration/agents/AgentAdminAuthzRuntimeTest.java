package com.operativus.agentmanager.integration.agents;

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
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pins the authz contract on every
 *   {@link com.operativus.agentmanager.control.controller.AgentAdminController} endpoint.
 *   After PR #969 added the class-level {@code @PreAuthorize("hasRole('ADMIN')")} gate,
 *   all 18 endpoints are uniformly admin-gated regardless of whether the underlying
 *   service-method still carries {@code @PreAuthorize("hasPermission(...)")} (most do, a
 *   handful never did). The matrix tests one consolidated contract per role.
 *
 *   <p>Closes 18 {@code TODO: focused authz test (PR #969)} entries on
 *   {@link com.operativus.agentmanager.arch.AdminEndpointCoverageArchTest#ADMIN_ENDPOINT_COVERAGE}
 *   — bumps each tag to {@code focused: AgentAdminAuthzRuntimeTest}.
 *
 *   <p>For each endpoint the matrix asserts:
 *   <ol>
 *     <li>Anonymous request → 401 (rejected at JWT filter)</li>
 *     <li>{@code ROLE_USER} request → 403 (rejected at the class-level
 *         {@code @PreAuthorize("hasRole('ADMIN')")} gate)</li>
 *     <li>{@code ROLE_ADMIN} request → not 401 and not 403 (proves the gate doesn't
 *         block admins; downstream handler behavior — 404 on probe ids, 400 on body
 *         validation, etc. — is intentionally NOT asserted here)</li>
 *   </ol>
 *
 *   <p><b>Synthetic ids</b>: path variables use {@code probe-} prefixed values to ensure
 *   the class-level gate fires before the service body's {@code findByIdAndOrgId}
 *   lookup-404. The "neither 401 nor 403" assertion accepts 404 / 400 / 500 / 202 / 204
 *   / 200 — all represent the gate having cleared.
 *
 *   <p><b>Bodies on POST/PUT</b>: minimally-valid {@link com.operativus.agentmanager.core.model.definitions.AgentDefinition}
 *   shape so jakarta {@code @Valid} on the controller param passes before the gate fires
 *   (the {@code @Valid}-before-{@code @PreAuthorize} ordering pattern documented in
 *   {@code MiscAdminAuthzRuntimeTest}).
 *
 *   <p><b>cancelRun cross-tenant ownership</b>: pinned separately by
 *   {@code AgentAdminServiceTest.cancelRun_foreignOrgRun_throws404_andDoesNotDelegate}
 *   (PR #972). That test exercises the service-layer orgId guard added after the class
 *   gate landed. This matrix only pins the role gate.
 *
 *   <p>Companion to {@link AgentsCrudRuntimeTest} (positive-path delete authz + cross-tenant
 *   isolation) and {@link AgentTenantIsolationRuntimeTest} (cross-tenant 404 surface).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAdminAuthzRuntimeTest extends BaseIntegrationTest {

    // Minimally-valid AgentDefinition body — all @NotBlank fields present so @Valid passes
    // and the request reaches the class-level authz gate. JSON keys reflect the
    // @JsonProperty mapping on AgentDefinition: `agentId` (id), `model` (modelId).
    private static final Map<String, Object> AGENT_BODY = Map.of(
            "agentId", "authz-probe-agent",
            "name", "authz-probe",
            "description", "for authz tests",
            "instructions", "do nothing",
            "model", "gpt-4o-mini");

    private static final Map<String, Object> BULK_ACTION_BODY = Map.of(
            "ids", List.of(),
            "action", "delete");

    private static final Map<String, Object> BULK_EXPORT_BODY = Map.of(
            "ids", List.of());

    /**
     * All 18 endpoints on {@code AgentAdminController}. Class-level
     * {@code @PreAuthorize("hasRole('ADMIN')")} (PR #969) gates every entry uniformly.
     */
    private static final List<EndpointSpec> ENDPOINTS = List.of(
            new EndpointSpec("/api/admin/agents", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents", HttpMethod.POST, AGENT_BODY),
            new EndpointSpec("/api/admin/agents/probe-id", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id", HttpMethod.PUT, AGENT_BODY),
            new EndpointSpec("/api/admin/agents/probe-id", HttpMethod.DELETE, null),
            new EndpointSpec("/api/admin/agents/probe-id/restore", HttpMethod.POST, null),
            new EndpointSpec("/api/admin/agents/probe-id/history", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/logs", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/audit", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/export", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/import", HttpMethod.POST, AGENT_BODY),
            new EndpointSpec("/api/admin/agents/probe-id/topology", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/dx-metrics", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/versions", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/agents/probe-id/rollback/probe-audit-id",
                    HttpMethod.POST, null),
            new EndpointSpec("/api/admin/agents/bulk-action", HttpMethod.POST, BULK_ACTION_BODY),
            new EndpointSpec("/api/admin/agents/bulk-export", HttpMethod.POST, BULK_EXPORT_BODY),
            new EndpointSpec("/api/admin/agents/runs/probe-run-id/cancel",
                    HttpMethod.POST, null));

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void unauthenticatedRequestsAreRejected401OnAllEndpoints() {
        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body()),
                    String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                    "unauthenticated " + ep.method() + " " + ep.path()
                            + " must be rejected with 401; got " + response.getStatusCode());
        }
    }

    @Test
    void roleUserIsForbidden403OnAllEndpoints() {
        HttpHeaders userAuth = authenticateAs("agent-authz-user",
                "agent-authz-user@test.local", "pass-aa-1234", List.of("ROLE_USER"));

        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), userAuth),
                    String.class);
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    "ROLE_USER " + ep.method() + " " + ep.path()
                            + " must hit the @PreAuthorize(\"hasRole('ADMIN')\") gate; got "
                            + response.getStatusCode());
        }
    }

    @Test
    void roleAdminPassesAuthzGateOnAllEndpoints() {
        HttpHeaders adminAuth = authenticateAs("agent-authz-admin",
                "agent-authz-admin@test.local", "pass-aa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        for (EndpointSpec ep : ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), adminAuth),
                    String.class);
            HttpStatusCode status = response.getStatusCode();
            assertNotEquals(HttpStatus.UNAUTHORIZED, status,
                    "ROLE_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 401; got " + status);
            assertNotEquals(HttpStatus.FORBIDDEN, status,
                    "ROLE_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 403; got " + status);
        }
    }

    private record EndpointSpec(String path, HttpMethod method, Object body) {
    }
}
