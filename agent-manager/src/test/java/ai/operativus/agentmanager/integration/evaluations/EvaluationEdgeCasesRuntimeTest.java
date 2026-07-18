package ai.operativus.agentmanager.integration.evaluations;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Edge-case + drift-pin runtime coverage for the
 *   {@code /api/v1/evaluations} surface that complements {@link EvaluationsRuntimeTest}
 *   (POST happy-paths, cascade-delete, run pipeline E2E). Specifically:
 *   <ul>
 *     <li>{@code GET /suites/{id}} unknown id → 404 (controller's
 *         {@code findById().orElse(notFound())})</li>
 *     <li>{@code DELETE /suites/{id}} unknown id → 404 (controller's
 *         {@code existsById} guard)</li>
 *     <li>{@code DELETE /cases/{id}} happy + unknown → 204 / 404</li>
 *     <li>{@code POST /suites/{id}/run} blank {@code agentId} → 400 (controller's
 *         explicit null/blank check, BEFORE the job is enqueued)</li>
 *     <li>{@code GET /metrics} on an empty DB → 200 with all-zero aggregates
 *         (no DivideByZero on {@code passedCases/totalCases})</li>
 *     <li>{@code POST /feedback} valid body → 200 + {@code {status: "received"}}</li>
 *     <li>{@code POST /feedback} rating out of {@code 1..5} → 400 via Bean Validation
 *         {@code @Min(1) @Max(5)} on {@code SubmitFeedbackRequest.rating}</li>
 *   </ul>
 *
 *   {@code GET /suites/{suiteId}/cases} on an unknown {@code suiteId} now returns
 *   404 (G2 — tenant-scope EvaluationController, changeset 071). Pre-G2 it returned
 *   200-with-empty-list because the controller skipped the suite-existence check.
 *   {@code EvaluationControllerTenantIsolationRuntimeTest} owns the cross-tenant
 *   contract; this class continues to pin same-org edge cases.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
         NoOpReflectionServiceConfig.class})
public class EvaluationEdgeCasesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // ─── GET /api/v1/evaluations/suites/{id} ─────────────────────────────────

    @Test
    void getSuite_unknownId_returns404() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-get-404");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /suites/{unknown} must be 404 via findById().orElse(notFound()). A 200 "
                        + "here would mean a phantom suite payload is being constructed for a "
                        + "missing id, which would confuse the FE detail page");
    }

    // ─── DELETE /api/v1/evaluations/suites/{id} ──────────────────────────────

    @Test
    void deleteSuite_unknownId_returns404() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-delsuite-404");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/evaluations/suites/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE /suites/{unknown} must be 404 via existsById guard. A 204 here would "
                        + "mean the controller flipped to idempotent-delete semantics (matches "
                        + "ModelsEdgeCasesRuntimeTest.deleteUnknownId — different contract, "
                        + "deliberate product decision when reconciled)");
    }

    // ─── DELETE /api/v1/evaluations/cases/{id} ───────────────────────────────

    @Test
    void deleteCase_happyPath_returns204AndRemovesRow() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-delcase-happy");
        String suiteId = createSuiteViaApi(auth, "Suite For Case Deletion");
        String caseId = addCaseViaApi(auth, suiteId);

        Long beforeRow = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_cases WHERE id = ?", Long.class, caseId);
        assertEquals(1L, beforeRow, "fixture precondition: case must exist before delete");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/evaluations/cases/" + caseId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "DELETE /cases/{id} happy-path must return 204");

        Long afterRow = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_cases WHERE id = ?", Long.class, caseId);
        assertEquals(0L, afterRow,
                "case row must be gone after 204 — a 1 here would mean deleteById was a no-op "
                        + "or the surrounding transaction rolled back");
    }

    @Test
    void deleteCase_unknownId_returns404() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-delcase-404");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/evaluations/cases/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE /cases/{unknown} must be 404 via existsById guard. Symmetric with "
                        + "deleteSuite_unknownId_returns404 above");
    }

    // ─── POST /api/v1/evaluations/suites/{id}/run ────────────────────────────

    @Test
    void runSuite_blankAgentId_returns400_andNoJobEnqueued() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-runblank-400");
        String suiteId = createSuiteViaApi(auth, "Suite For Blank AgentId");

        Long jobsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'EVALUATION_RUN'",
                Long.class);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/run?agentId="),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "blank agentId must short-circuit at the controller's null/blank guard before "
                        + "the job is enqueued. A 202 here would mean an EVALUATION_RUN job for "
                        + "agentId='' lands in background_jobs and the handler later fails with "
                        + "a confusing 'agent not found' error");

        Long jobsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'EVALUATION_RUN'",
                Long.class);
        assertEquals(jobsBefore, jobsAfter,
                "no EVALUATION_RUN job may have been enqueued — the 400 must precede the "
                        + "jobQueueService.enqueue call");
    }

    // ─── GET /api/v1/evaluations/metrics ─────────────────────────────────────

    @Test
    void getMetrics_emptyDb_returnsAllZerosNotErrorOrNaN() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-metrics-empty");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/metrics"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "GET /metrics on an empty DB must be 200 — a 500 here would mean a divide-by-zero "
                        + "snuck in (controller guards passRate with `totalCases > 0` and uses "
                        + "OptionalDouble for averages — both guards must hold)");

        Map<String, Object> body = resp.getBody();
        assertNotNull(body, "metrics body must not be null");
        assertEquals(0, ((Number) body.get("totalRuns")).intValue(), "totalRuns must be 0 on empty DB");
        assertEquals(0, ((Number) body.get("totalCases")).intValue(), "totalCases must be 0 on empty DB");
        assertEquals(0, ((Number) body.get("passedCases")).intValue());
        assertEquals(0, ((Number) body.get("failedCases")).intValue());
        assertEquals(0.0, ((Number) body.get("passRate")).doubleValue(),
                "passRate must be 0.0 when totalCases=0 — a NaN here would mean the "
                        + "`totalCases > 0` guard was removed and the unsigned division fired");
        assertEquals(0.0, ((Number) body.get("averageScore")).doubleValue(),
                "averageScore must be 0.0 (OptionalDouble.orElse(0.0)) when no runs have scores");
        assertEquals(0.0, ((Number) body.get("averageLatencyMs")).doubleValue());
    }

    // ─── POST /api/v1/evaluations/feedback ───────────────────────────────────

    @Test
    void submitFeedback_validRequest_returns200WithReceivedStatus() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-feedback-happy");

        Map<String, Object> body = new HashMap<>();
        body.put("runId", "run-feedback-fixture-" + UUID.randomUUID());
        body.put("rating", 4);
        body.put("comment", "concise and accurate");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/feedback"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "valid feedback must be 200 — controller logs and returns {status:received}. "
                        + "If this controller later persists feedback, a 201 would be the more "
                        + "appropriate status code, and this assertion is the signal point");
        assertNotNull(resp.getBody());
        assertEquals("received", resp.getBody().get("status"),
                "controller returns the literal {status:received} envelope today. A schema "
                        + "evolution would surface here");
    }

    @Test
    void submitFeedback_ratingOutOfRange_returns400_viaBeanValidation() {
        HttpHeaders auth = authenticatedHeaders("eval-edge-feedback-badrating");

        Map<String, Object> body = new HashMap<>();
        body.put("runId", "run-rating-oob-" + UUID.randomUUID());
        body.put("rating", 6);
        body.put("comment", "out-of-range rating");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/feedback"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "rating=6 must be rejected by @Max(5) on SubmitFeedbackRequest.rating. A 200 "
                        + "here would mean @Valid was dropped from the controller method or the "
                        + "bounds annotation was loosened. The same applies for rating=0 vs @Min(1)");
    }

    // ─── GET /suites/{suiteId}/cases on unknown suiteId — post-G2 contract ────

    @Test
    void getCasesForSuite_unknownSuiteId_returns404() {
        // G2 — controller now checks suite ownership via existsByIdAndOrgId. An
        // unknown (or cross-tenant) suiteId returns 404 with the same shape as a
        // missing suite. Mirrors GET /suites/{id} drift pin above.
        HttpHeaders auth = authenticatedHeaders("eval-edge-getcases-unknown");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/does-not-exist-" + UUID.randomUUID() + "/cases"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /suites/{unknown}/cases must be 404 via existsByIdAndOrgId guard "
                        + "(no existence leak; no empty-list information disclosure)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders authenticatedHeaders(String username) {
        // ROLE_ADMIN required for /api/v1/evaluations write paths (see
        // EvaluationControllerAuthzRuntimeTest for the gate pins).
        return authenticateAs(username, username + "@test.local", "pass-eval-edge-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createSuiteViaApi(HttpHeaders auth, String name) {
        Map<String, String> body = Map.of("name", name, "description", "edge-case fixture");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "fixture precondition: createSuite must succeed; got " + resp.getStatusCode());
        return (String) resp.getBody().get("id");
    }

    private String addCaseViaApi(HttpHeaders auth, String suiteId) {
        Map<String, String> body = Map.of(
                "name", "edge-case-fixture-" + UUID.randomUUID(),
                "input", "Edge-case fixture input",
                "expectedOutput", "^.*$");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "fixture precondition: addCase must succeed; got " + resp.getStatusCode());
        return (String) resp.getBody().get("id");
    }
}
