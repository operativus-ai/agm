package com.operativus.agentmanager.integration.security;

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
 * Domain Responsibility: Centralized project-wide pin for the {@code @PreAuthorize} matrix on
 *   admin-protected endpoints. One test class enforces the {@code 401 (unauth) / 403 (wrong role)
 *   / non-401-non-403 (right role)} contract across a representative sample of admin endpoints
 *   so per-controller tests do not need to repeat the same authz assertions. New
 *   {@code @PreAuthorize}-protected admin endpoints should be added to this matrix rather than
 *   re-pinning authz inside their own test classes.
 *   <p>
 *   The sampled endpoints are:
 *   <ul>
 *     <li>{@code GET  /api/admin/retention/policies}            — ROLE_ADMIN.
 *     <li>{@code POST /api/admin/retention/purge}               — ROLE_ADMIN.
 *     <li>{@code GET  /api/admin/system-audit-logs}             — ROLE_ADMIN.
 *     <li>{@code POST /api/models}                              — ROLE_ADMIN
 *         (model-admin surface; service-layer @PreAuthorize on ModelService.createModel).
 *     <li>{@code DELETE /api/models/{id}}                       — ROLE_ADMIN
 *         (model-admin surface; service-layer @PreAuthorize on ModelService.deleteModel).
 *     <li>{@code GET  /api/v1/observability/background-jobs}    — ROLE_ADMIN
 *         (background-job monitor list).
 *     <li>{@code GET  /api/v1/observability/background-jobs/status-summary} — ROLE_ADMIN
 *         (background-job monitor per-status counts).
 *     <li>{@code POST /api/v1/observability/background-jobs/{id}/retry}     — ROLE_ADMIN
 *         (background-job atomic-retry action; admin path returns 404 on the synthetic
 *         id used here, which still satisfies the matrix's "neither 401 nor 403" check).
 *     <li>{@code GET  /api/admin/audit-logs}                    — ROLE_ADMIN
 *         (Fix A class-level @PreAuthorize; closes #286 G-1+G-4).
 *     <li>{@code GET  /api/admin/audit-logs/export}             — ROLE_ADMIN
 *         (Fix A class-level @PreAuthorize supersedes the prior method-level annotation;
 *         no method-level annotation remains).
 *     <li>{@code POST /api/v1/admin/incident/halt-all-runs}     — ROLE_SUPER_ADMIN
 *         (distinct from ADMIN to pin the role-hierarchy gate; the ADMIN→403 case is also
 *         covered by {@link com.operativus.agentmanager.control.controller.QuarantineLifecycleIntegrationTest},
 *         duplicated here only as part of the project-wide matrix).
 *     <li>{@code GET    /api/admin/composio/connection}         — ROLE_ADMIN
 *         (Composio per-org connection config; ADMIN-gated at the class level on
 *         {@code ComposioConnectionAdminController}; service-layer scopes to caller's org).
 *     <li>{@code PUT    /api/admin/composio/connection}         — ROLE_ADMIN.
 *     <li>{@code DELETE /api/admin/composio/connection}         — ROLE_ADMIN
 *         (404 when no row for caller's org still satisfies "neither 401 nor 403").
 *     <li>{@code GET    /api/v1/schedules/batches}              — ROLE_ADMIN
 *         (S1 fix: spot-batch-jobs have no org_id column; endpoint returns system-wide
 *         data, so ADMIN-gating prevents cross-tenant metadata exposure).
 *     <li>{@code GET    /api/admin/composio/actions}            — ROLE_SUPER_ADMIN
 *         (Composio action catalog is system-wide config; SUPER_ADMIN-gated at the
 *         class level on {@code ComposioAdminController}).
 *     <li>{@code POST   /api/admin/composio/actions}            — ROLE_SUPER_ADMIN.
 *     <li>{@code PUT    /api/admin/composio/actions/{id}}       — ROLE_SUPER_ADMIN.
 *     <li>{@code DELETE /api/admin/composio/actions/{id}}       — ROLE_SUPER_ADMIN.
 *   </ul>
 *   {@code /api/diagnostics/thread} is intentionally NOT in the matrix: it is currently
 *   reachable by any authenticated principal (no {@code @PreAuthorize}) and is exercised
 *   by {@link com.operativus.agentmanager.integration.crosscutting.VirtualThreadMdcRuntimeTest}.
 *   If admin-gating is later added to that endpoint, extend this matrix accordingly.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AdminEndpointAuthzRuntimeTest extends BaseIntegrationTest {

    // ModelRequest carries @NotBlank/@Valid annotations that fire at the controller layer
    // BEFORE service-method @PreAuthorize. An empty body would 400 first and the matrix
    // would not exercise the authz gate. Send a minimally-valid body so validation passes
    // and we reach the @PreAuthorize aspect on ModelService.createModel.
    private static final Map<String, Object> MODEL_VALID_BODY = Map.of(
            "name", "authz-matrix-probe",
            "provider", "fake",
            "modelName", "fake-gpt",
            "modelType", "CHAT");

    // ProviderCredentialRequest carries @NotBlank on provider + apiKey. Empty body would 400
    // before the @PreAuthorize gate fires; supply minimally-valid values so the matrix
    // exercises the authz check rather than validation.
    private static final Map<String, Object> PROVIDER_CRED_VALID_BODY = Map.of(
            "provider", "OPENAI",
            "apiKey", "authz-matrix-probe-key");

    // ProviderCredentialTestRequest validation: @NotBlank on provider + model (apiKey optional).
    // Body must pass @Valid so the 403 case hits @PreAuthorize rather than a 400 from validation.
    private static final Map<String, Object> PROVIDER_CRED_TEST_VALID_BODY = Map.of(
            "provider", "OPENAI",
            "model", "gpt-4o-mini");

    // AlertRuleRequest validation: @NotBlank on name/metricName/condition/severity,
    // @Pattern on condition (GT|GTE|LT|LTE|EQ) and severity (INFO|WARNING|CRITICAL),
    // @PositiveOrZero on windowSeconds. Body must pass @Valid so the 403 case actually
    // hits @PreAuthorize rather than a 400 from validation.
    private static final Map<String, Object> ALERT_RULE_VALID_BODY = Map.of(
            "name", "authz-matrix-probe-rule",
            "metricName", "agent.runs.failed",
            "condition", "GT",
            "threshold", 0.0,
            "windowSeconds", 60,
            "severity", "WARNING",
            "enabled", true);

    // AlertIntegrationRequest validation: @NotBlank on name/type/endpointUrl,
    // @Pattern on type (WEBHOOK|SLACK|PAGERDUTY). example.com is a public URL that
    // passes SsrfGuard (which only blocks RFC-1918 + cloud-metadata targets per the
    // AlertIntegrationController javadoc), so the bind reaches the @PreAuthorize check.
    private static final Map<String, Object> ALERT_INTEGRATION_VALID_BODY = Map.of(
            "name", "authz-matrix-probe-integration",
            "type", "WEBHOOK",
            "endpointUrl", "https://example.com/webhook",
            "enabled", true);

    private static final List<EndpointSpec> ADMIN_ENDPOINTS = List.of(
            new EndpointSpec("/api/admin/retention/policies", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/retention/purge", HttpMethod.POST, Map.of()),
            new EndpointSpec("/api/admin/system-audit-logs", HttpMethod.GET, null),
            new EndpointSpec("/api/models", HttpMethod.POST, MODEL_VALID_BODY),
            new EndpointSpec("/api/models/non-existent-model-id", HttpMethod.DELETE, null),
            new EndpointSpec("/api/v1/observability/background-jobs", HttpMethod.GET, null),
            new EndpointSpec("/api/v1/observability/background-jobs/status-summary", HttpMethod.GET, null),
            new EndpointSpec("/api/v1/observability/background-jobs/non-existent-job-id/retry",
                    HttpMethod.POST, null),
            new EndpointSpec("/api/admin/audit-logs", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/audit-logs/export", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/composio/connection", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/composio/connection", HttpMethod.PUT,
                    Map.of("connectionId", "authz-probe-conn", "version", 0)),
            new EndpointSpec("/api/admin/composio/connection", HttpMethod.DELETE, null),
            // SDD agm-ops-resilience-37 PR-C — MCP lifecycle reconnect verb. The id segment
            // must hit a non-existent-but-shape-valid value so the @PreAuthorize check fires
            // before the lookup-404; the matrix verifies authz wiring, not domain behavior.
            new EndpointSpec("/api/mcp/servers/non-existent-mcp-id/reconnect", HttpMethod.POST, Map.of()),
            // S1 fix — spot-batch-jobs list is system-level (no org_id column); gate with
            // ROLE_ADMIN to prevent cross-tenant exposure of all orgs' batch-job metadata.
            new EndpointSpec("/api/v1/schedules/batches", HttpMethod.GET, null),
            // Per-(org, provider) LLM API key admin surface — DB-only key resolution path.
            new EndpointSpec("/api/v1/provider-credentials", HttpMethod.GET, null),
            new EndpointSpec("/api/v1/provider-credentials/non-existent-id", HttpMethod.GET, null),
            new EndpointSpec("/api/v1/provider-credentials", HttpMethod.POST, PROVIDER_CRED_VALID_BODY),
            new EndpointSpec("/api/v1/provider-credentials/non-existent-id", HttpMethod.PUT, PROVIDER_CRED_VALID_BODY),
            new EndpointSpec("/api/v1/provider-credentials/non-existent-id", HttpMethod.DELETE, null),
            new EndpointSpec("/api/v1/provider-credentials/test", HttpMethod.POST, PROVIDER_CRED_TEST_VALID_BODY),
            // Live provider catalog passthrough — admin-only since it exposes provider-side
            // metadata and uses the org's ProviderCredential to call the provider API.
            new EndpointSpec("/api/v1/models/catalog/OPENAI", HttpMethod.GET, null),
            // DR-FR-4 routing-decision telemetry — admin-only read surface.
            new EndpointSpec("/api/v1/admin/routing-decisions", HttpMethod.GET, null),
            new EndpointSpec("/api/v1/admin/routing-decisions/non-existent-id", HttpMethod.GET, null),
            // Routing-vector backfill — admin eager-populates routing_vectors for the org.
            new EndpointSpec("/api/v1/admin/routing-embeddings/backfill", HttpMethod.POST, null),
            // Alert rule + alert-event admin surface (AlertingController). Reads
            // (/api/alerts/rules, /api/alerts/events) are intentionally open to any
            // authenticated tenant member with service-layer tenant scoping; only the
            // write paths + the ack-alert action are admin-gated. Without these matrix
            // entries, a regression that drops @PreAuthorize from any write would have
            // gone unnoticed (the mass-assignment guard documented on AlertRuleRequest
            // depends on the admin gate being live).
            new EndpointSpec("/api/alerts/rules", HttpMethod.POST, ALERT_RULE_VALID_BODY),
            new EndpointSpec("/api/alerts/rules/non-existent-rule-id", HttpMethod.PUT, ALERT_RULE_VALID_BODY),
            new EndpointSpec("/api/alerts/rules/non-existent-rule-id", HttpMethod.DELETE, null),
            new EndpointSpec("/api/alerts/events/non-existent-event-id/acknowledge",
                    HttpMethod.POST, null),
            // Alert integration admin surface (AlertIntegrationController). Same shape:
            // GET list is open, writes + the operator-fired test-dispatch are admin.
            // Test-fire is admin-gated specifically because it spends an outbound HTTP
            // request and could be abused for an SSRF probe per the controller javadoc.
            new EndpointSpec("/api/alerts/integrations", HttpMethod.POST, ALERT_INTEGRATION_VALID_BODY),
            new EndpointSpec("/api/alerts/integrations/non-existent-integration-id",
                    HttpMethod.PUT, ALERT_INTEGRATION_VALID_BODY),
            new EndpointSpec("/api/alerts/integrations/non-existent-integration-id",
                    HttpMethod.DELETE, null),
            new EndpointSpec("/api/alerts/integrations/non-existent-integration-id/test",
                    HttpMethod.POST, null));

    // Composio create/update bodies are minimally-valid so jakarta validation passes
    // and the request reaches the @PreAuthorize gate (otherwise a 400 would mask the
    // authz check). The action_name is uppercased server-side and turned into an id.
    private static final Map<String, Object> COMPOSIO_CREATE_BODY = Map.of(
            "actionName", "AUTHZ_MATRIX_PROBE",
            "tier", 2,
            "enabled", true);
    private static final Map<String, Object> COMPOSIO_UPDATE_BODY = Map.of(
            "tier", 2,
            "enabled", true,
            "version", 0);

    private static final List<EndpointSpec> SUPER_ADMIN_ENDPOINTS = List.of(
            new EndpointSpec("/api/v1/admin/incident/halt-all-runs", HttpMethod.POST,
                    Map.of("reason", "authz matrix probe")),
            new EndpointSpec("/api/admin/composio/actions", HttpMethod.GET, null),
            new EndpointSpec("/api/admin/composio/actions", HttpMethod.POST, COMPOSIO_CREATE_BODY),
            new EndpointSpec("/api/admin/composio/actions/non-existent-action-id",
                    HttpMethod.PUT, COMPOSIO_UPDATE_BODY),
            new EndpointSpec("/api/admin/composio/actions/non-existent-action-id",
                    HttpMethod.DELETE, null));

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void unauthenticatedRequestsAreRejected401OnAllAdminEndpoints() {
        for (EndpointSpec ep : ADMIN_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body()),
                    String.class);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                    "unauthenticated " + ep.method() + " " + ep.path()
                            + " must be rejected with 401; got " + response.getStatusCode());
        }

        for (EndpointSpec ep : SUPER_ADMIN_ENDPOINTS) {
            ResponseEntity<String> superAdminResp = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body()),
                    String.class);
            assertEquals(HttpStatus.UNAUTHORIZED, superAdminResp.getStatusCode(),
                    "unauthenticated " + ep.method() + " " + ep.path()
                            + " must be rejected with 401");
        }
    }

    @Test
    void roleUserIsForbidden403OnAllAdminEndpoints() {
        HttpHeaders userAuth = authenticateAs("matrix-user", "matrix-user@test.local",
                "pass-mtx-1234", List.of("ROLE_USER"));

        for (EndpointSpec ep : ADMIN_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), userAuth),
                    String.class);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    "ROLE_USER " + ep.method() + " " + ep.path()
                            + " must be rejected with 403; got " + response.getStatusCode());
        }

        for (EndpointSpec ep : SUPER_ADMIN_ENDPOINTS) {
            ResponseEntity<String> superAdminResp = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), userAuth),
                    String.class);
            assertEquals(HttpStatus.FORBIDDEN, superAdminResp.getStatusCode(),
                    "ROLE_USER " + ep.method() + " " + ep.path()
                            + " must be rejected with 403");
        }
    }

    @Test
    void roleAdminPassesAuthzGateOnAdminEndpoints() {
        HttpHeaders adminAuth = authenticateAs("matrix-admin", "matrix-admin@test.local",
                "pass-mtx-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        for (EndpointSpec ep : ADMIN_ENDPOINTS) {
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

    @Test
    void roleAdminButNotSuperAdminIsForbidden403OnSuperAdminEndpoints() {
        HttpHeaders adminAuth = authenticateAs("matrix-not-super", "matrix-not-super@test.local",
                "pass-mtx-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        for (EndpointSpec ep : SUPER_ADMIN_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), adminAuth),
                    String.class);

            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                    "ROLE_ADMIN (without ROLE_SUPER_ADMIN) " + ep.method() + " " + ep.path()
                            + " must be rejected with 403; got " + response.getStatusCode());
        }
    }

    @Test
    void roleSuperAdminPassesAuthzGateOnSuperAdminEndpoints() {
        HttpHeaders superAuth = authenticateAs("matrix-super", "matrix-super@test.local",
                "pass-mtx-1234", List.of("ROLE_SUPER_ADMIN"));

        for (EndpointSpec ep : SUPER_ADMIN_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(ep.path()),
                    ep.method(),
                    new HttpEntity<>(ep.body(), superAuth),
                    String.class);

            HttpStatusCode status = response.getStatusCode();
            assertNotEquals(HttpStatus.UNAUTHORIZED, status,
                    "ROLE_SUPER_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 401; got " + status);
            assertNotEquals(HttpStatus.FORBIDDEN, status,
                    "ROLE_SUPER_ADMIN " + ep.method() + " " + ep.path()
                            + " must NOT be rejected with 403; got " + status);
        }
    }

    private record EndpointSpec(String path, HttpMethod method, Object body) {}
}
