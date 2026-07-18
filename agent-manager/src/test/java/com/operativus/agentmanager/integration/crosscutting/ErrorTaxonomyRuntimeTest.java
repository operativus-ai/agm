package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Black-box coverage of the AGM error taxonomy — proves every exception
 *   surfaced from a controller is funnelled through
 *   {@link com.operativus.agentmanager.core.exception.GlobalExceptionHandler} and returned as
 *   RFC 7807 {@code application/problem+json} with the documented {@code urn:problem-type:*}
 *   {@code type} and matching HTTP status. No controller should leak a raw stack trace, a
 *   framework default {@code /error} JSON body, or a plain {@code text/plain} message.
 * State: Stateless (per-test DB truncation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T050 §27.11.
 *
 * Coverage target (matrix §27.11, the 9 {@code @ExceptionHandler} methods in
 * {@link com.operativus.agentmanager.core.exception.GlobalExceptionHandler}):
 *   - {@code ResourceNotFoundException}   → 404 / urn:problem-type:resource-not-found
 *   - {@code NoResourceFoundException}    → 404 / urn:problem-type:not-found
 *   - {@code BusinessValidationException} → 400 / urn:problem-type:business-validation-error
 *   - {@code MethodArgumentNotValidException} → 400 / urn:problem-type:invalid-request
 *   - {@code BadCredentialsException}     → 401 / urn:problem-type:unauthorized
 *   - {@code StaleDataException}          → 409 / urn:problem-type:stale-data (gap-pinned)
 *   - {@code HttpClientErrorException.TooManyRequests} → 429 / urn:problem-type:rate-limit-exceeded (gap-pinned)
 *   - {@code IllegalArgumentException}, {@code Exception} — already exercised indirectly by
 *     sibling tests; not re-pinned here to keep the file focused on the taxonomy contract.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ErrorTaxonomyRuntimeTest extends BaseIntegrationTest {

    // §27.11 case 1 — ResourceNotFoundException (thrown explicitly by
    // KnowledgePreviewController#previewDocument when the UUID is unknown) must be surfaced as
    // 404 / problem+json with urn:problem-type:resource-not-found and the resourceName/identifier
    // properties copied off the exception. The endpoint is under /api/knowledge/** which requires
    // authentication — authenticating as ROLE_USER is sufficient.
    @Test
    void resourceNotFoundReturns404ProblemJson() {
        HttpHeaders auth = authenticateAs("et-rnf-" + shortUuid(),
                "et-rnf-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
        String missingId = UUID.randomUUID().toString();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/knowledge/" + missingId + "/preview"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "unknown knowledge id must surface as 404 via ResourceNotFoundException → GlobalExceptionHandler");
        assertProblemJson(resp);
        String body = resp.getBody();
        assertNotNull(body, "404 must carry a problem+json body");
        assertTrue(body.contains("\"type\":\"urn:problem-type:resource-not-found\""),
                "type must be the documented resource-not-found urn. Got: " + body);
        assertTrue(body.contains("\"resourceName\":\"KnowledgeContent\""),
                "problem+json must copy ResourceNotFoundException.resourceName. Got: " + body);
        assertTrue(body.contains("\"identifier\":\"" + missingId + "\""),
                "problem+json must copy ResourceNotFoundException.identifier. Got: " + body);
    }

    // §27.11 case 2 — Spring's NoResourceFoundException (raised when no controller mapping nor
    // static resource matches) must be mapped to 404 with urn:problem-type:not-found. This pins
    // that unmapped /api/** paths go through GlobalExceptionHandler rather than returning
    // Spring's default /error JSON. We authenticate so the path passes the security filter chain
    // (unauthenticated /api/** under authenticated() → 401, which is a different code path).
    @Test
    void unknownEndpointReturns404NotFound() {
        HttpHeaders auth = authenticateAs("et-nrf-" + shortUuid(),
                "et-nrf-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/does-not-exist-" + shortUuid()),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "unmapped /api path must be 404 via NoResourceFoundException → GlobalExceptionHandler");
        assertProblemJson(resp);
        String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"type\":\"urn:problem-type:not-found\""),
                "type must be urn:problem-type:not-found (not resource-not-found — those are distinct urns). Got: " + body);
    }

    // §27.11 case 3 — Spring Security's BadCredentialsException, thrown inside the auth filter
    // chain during POST /api/auth/login when the password is wrong, must be caught and mapped to
    // 401 problem+json with urn:problem-type:unauthorized. A misconfigured chain would return
    // Spring's default 401 with WWW-Authenticate headers and no body — which would fail the
    // problem+json contract and the urn assertion.
    @Test
    void badCredentialsReturns401ProblemJson() {
        // Seed a real user so the AuthenticationManager reaches the password check (otherwise
        // UsernameNotFoundException — a different code path).
        String username = "et-creds-" + shortUuid();
        authenticateAs(username, username + "@test.local", "correct-pass-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        Map<String, String> badLogin = Map.of("username", username, "password", "wrong-pass-1234");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(badLogin, jsonHeaders()), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "bad password must surface as 401 via BadCredentialsException → GlobalExceptionHandler");
        assertProblemJson(resp);
        String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"type\":\"urn:problem-type:unauthorized\""),
                "type must be urn:problem-type:unauthorized. Got: " + body);
        assertTrue(body.contains("Invalid username or password"),
                "detail must be the scrubbed generic message (never leak whether the username exists). Got: " + body);
    }

    // §27.11 case 4 — MethodArgumentNotValidException from @Valid failures (e.g. blank
    // @NotBlank name on ModelRequest) must be surfaced as 400 with urn:problem-type:invalid-request
    // AND an `invalidParams` array describing each failed field. The invalidParams shape is the
    // hard contract clients depend on for form-field highlighting — drop that property and UI
    // error reporting silently regresses.
    @Test
    void invalidRequestBodyReturns400WithFieldDetails() {
        HttpHeaders adminAuth = authenticateAs("et-inv-" + shortUuid(),
                "et-inv-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_ADMIN"));

        // Blank name + blank provider + blank modelName — three @NotBlank violations on ModelRequest.
        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("name", "");
        bad.put("provider", "");
        bad.put("modelName", "");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/models"), HttpMethod.POST,
                new HttpEntity<>(bad, adminAuth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "blank @NotBlank fields must surface as 400 via MethodArgumentNotValidException");
        assertProblemJson(resp);
        String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"type\":\"urn:problem-type:invalid-request\""),
                "type must be urn:problem-type:invalid-request. Got: " + body);
        assertTrue(body.contains("\"invalidParams\""),
                "problem+json must carry invalidParams[] — clients depend on this for field-level "
                        + "error highlighting. Got: " + body);
        // Spot-check a field name survives the serialization (order-insensitive).
        assertTrue(body.contains("\"field\":\"name\"") || body.contains("\"field\":\"provider\"")
                        || body.contains("\"field\":\"modelName\""),
                "invalidParams must include at least one of the blank fields by name. Got: " + body);
    }

    // §27.11 case 5 — BusinessValidationException thrown by the service layer (e.g. when
    // ModelService sees an unsupported provider string) must be mapped to 400 with
    // urn:problem-type:business-validation-error. This distinguishes *domain* rejections from
    // *syntactic* bad-request (case 4's urn:invalid-request) — two different problem types so UI
    // can render them differently (toast vs. field-level).
    @Test
    void businessValidationReturns400ProblemJson() {
        HttpHeaders adminAuth = authenticateAs("et-bv-" + shortUuid(),
                "et-bv-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_ADMIN"));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "et-bogus-" + shortUuid());
        body.put("provider", "COMPLETELY_MADE_UP_PROVIDER");
        body.put("modelName", "does-not-matter");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/models"), HttpMethod.POST,
                new HttpEntity<>(body, adminAuth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "unsupported provider must surface as 400 via BusinessValidationException → GlobalExceptionHandler");
        assertProblemJson(resp);
        String respBody = resp.getBody();
        assertNotNull(respBody);
        assertTrue(respBody.contains("\"type\":\"urn:problem-type:business-validation-error\""),
                "type must be urn:problem-type:business-validation-error (distinct from invalid-request). "
                        + "Got: " + respBody);
        assertTrue(respBody.contains("Unsupported provider"),
                "detail must pass through the service-layer message. Got: " + respBody);
    }

    // §27.11 case 6 — Every error response across the taxonomy uses application/problem+json
    // and does NOT leak internal implementation details (stack frames, class names, framework
    // package prefixes). This is the cross-cutting contract — a regression that routes a single
    // handler through the default /error JSON would fail this test even if the individual
    // case tests above still pass (e.g. if someone @Order-overrides only one handler).
    @Test
    void errorResponsesNeverLeakInternals() throws Exception {
        HttpHeaders auth = authenticateAs("et-leak-" + shortUuid(),
                "et-leak-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        // Collect responses from each category we can cheaply trigger.
        List<ResponseEntity<String>> errorResponses = List.of(
                // 404 resource-not-found
                rest.exchange(url("/api/knowledge/" + UUID.randomUUID() + "/preview"),
                        HttpMethod.GET, new HttpEntity<>(auth), String.class),
                // 404 unmapped
                rest.exchange(url("/api/bogus-" + shortUuid()),
                        HttpMethod.GET, new HttpEntity<>(auth), String.class),
                // 401 bad credentials
                rest.exchange(url("/api/auth/login"), HttpMethod.POST,
                        new HttpEntity<>(Map.of("username", "nobody-" + shortUuid(),
                                "password", "wrong"), jsonHeaders()), String.class)
        );

        for (ResponseEntity<String> resp : errorResponses) {
            assertProblemJson(resp);
            String body = resp.getBody();
            assertNotNull(body, "every error response must carry a body. Status=" + resp.getStatusCode());
            // Stack trace and package-name leak markers.
            assertFalse(body.contains("\tat "),
                    "problem+json must NOT contain stack frames ('\\tat '). Status="
                            + resp.getStatusCode() + " body=" + body);
            assertFalse(body.contains("java.lang.") || body.contains("org.springframework.")
                            || body.contains("com.operativus."),
                    "problem+json must NOT leak FQCNs / package prefixes — that is an information "
                            + "disclosure regression. Status=" + resp.getStatusCode() + " body=" + body);
            // Must carry the documented shape.
            assertTrue(body.contains("\"status\":"),
                    "problem+json must include a status property. body=" + body);
            assertTrue(body.contains("\"type\":\"urn:problem-type:"),
                    "problem+json must include a urn:problem-type: type. body=" + body);
        }
    }

    // Matrix §27.11 case 7 — StaleDataException → 409 / urn:problem-type:stale-data. Drives
    // the path via racing concurrent PUT /api/admin/agents/{id}: AgentEntity.version has
    // @Version (optimistic locking), AgentAdminService.updateAgent catches
    // ObjectOptimisticLockingFailureException and rethrows StaleDataException, which
    // GlobalExceptionHandler maps to 409 problem+json with urn:problem-type:stale-data.
    //
    // PostgreSQL READ COMMITTED lets two in-flight TXs both read version=N; one wins the
    // UPDATE (WHERE version=N → 1 row, version→N+1), the other's UPDATE affects 0 rows →
    // optimistic-lock failure. 30 concurrent PUTs via a virtual-thread pool reliably
    // interleaves on a modern multi-core JVM; if the race vanishes under load we'll bump.
    @Test
    void staleDataReturns409() throws Exception {
        // Seed the model FK dependency (models table may be empty if truncated by a sibling).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, "gpt-4o-mini", "gpt-4o-mini", "gpt-4o-mini");

        HttpHeaders auth = authenticateAs("et-stale-" + shortUuid(),
                "et-stale-" + shortUuid() + "@test.local", "pass-et-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        String agentId = "agent-stale-" + shortUuid();
        Map<String, Object> createBody = minimalAgentBody(agentId, "Stale Data Target");
        ResponseEntity<String> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST,
                new HttpEntity<>(createBody, auth), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "precondition: POST must create the agent. body=" + created.getBody());

        int burst = 30;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<ResponseEntity<String>> responses;
        try {
            List<CompletableFuture<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> putBody = minimalAgentBody(agentId, "Rename-" + idx);
                    return rest.exchange(
                            url("/api/admin/agents/" + agentId),
                            HttpMethod.PUT,
                            new HttpEntity<>(putBody, auth),
                            String.class);
                }, pool));
            }
            responses = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }

        ResponseEntity<String> conflict = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst()
                .orElse(null);
        if (conflict == null) {
            fail("expected ≥1 of " + burst + " concurrent PUTs to return 409 Conflict "
                    + "(@Version optimistic-lock race). None did — either the race "
                    + "didn't interleave under this load or AgentAdminService stopped "
                    + "mapping ObjectOptimisticLockingFailureException to StaleDataException. "
                    + "Statuses: " + responses.stream().map(r -> r.getStatusCode().toString()).toList());
        }

        assertProblemJson(conflict);
        String body = conflict.getBody();
        assertNotNull(body, "409 must carry a problem+json body");
        assertTrue(body.contains("\"type\":\"urn:problem-type:stale-data\""),
                "409 type must be urn:problem-type:stale-data. Got: " + body);
        assertTrue(body.contains("\"status\":409"),
                "problem+json must include status=409. Got: " + body);
    }

    // Matrix §27.11 gap — HttpClientErrorException.TooManyRequests → 429 /
    // urn:problem-type:rate-limit-exceeded is the handler for UPSTREAM provider throttling (e.g.
    // OpenAI/Anthropic returning 429). Our local rate limiter is pinned by T050a and uses a
    // different content shape. Flip this when an outbound-path test harness can inject a fake
    // provider 429 (via WireMock or a Fake ChatModel that throws TooManyRequests).
    @Test
    @Disabled("Matrix §27.11 gap — upstream 429 propagation requires a test harness that can "
            + "inject HttpClientErrorException.TooManyRequests from a fake provider. FakeChatModel "
            + "does not currently expose a 'fail with 429' mode.")
    void upstreamProviderRateLimitReturns429() {
        // Intentionally empty.
    }

    // ─── helpers ───

    /**
     * Mirrors {@code AgentsCrudRuntimeTest#minimalAgentBody} — supplies every field required to
     * pass {@code @NotBlank} validation + every primitive {@code boolean} so Jackson's
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} doesn't trip.
     */
    private Map<String, Object> minimalAgentBody(String agentId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Created from ErrorTaxonomyRuntimeTest.staleDataReturns409");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        return body;
    }

    private void assertProblemJson(ResponseEntity<String> resp) {
        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "error response must set a Content-Type header");
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(ct),
                "error response must use application/problem+json; got " + ct
                        + " (status=" + resp.getStatusCode() + ")");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
