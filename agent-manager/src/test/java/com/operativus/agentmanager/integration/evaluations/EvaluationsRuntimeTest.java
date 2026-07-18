package com.operativus.agentmanager.integration.evaluations;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the {@code /api/v1/evaluations}
 *   surface — {@link com.operativus.agentmanager.control.controller.EvaluationController}
 *   → {@link com.operativus.agentmanager.control.service.EvaluationService} →
 *   {@link com.operativus.agentmanager.control.service.queue.EvaluationRunJobHandler}.
 *   Pins suite/case CRUD, controller-level payload validation, the ON DELETE CASCADE
 *   schema invariant from migration 001 §11, and the full end-to-end run pipeline:
 *   POST → background_jobs → virtual-thread execution → evaluation_runs.status ==
 *   COMPLETED with a scored evaluation_results row.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §8 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T025.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@code POST /suites} returns 200 OK, NOT the 201 CREATED called for in matrix §8
 *     case 1. Case (a) pins the current shape so a refactor to 201 flips the assertion
 *     deliberately. {@code POST /suites/{id}/cases} is similarly a 200.
 *   - {@link com.operativus.agentmanager.control.controller.EvaluationController} performs
 *     its own null/blank checks on {@code name} and {@code input} with a 400 response; it
 *     does NOT use {@code @Valid} and there is no {@code @RequestBody}-level validation.
 *     Cases (b) and (d) pin those controller-level checks. If the controller ever moves
 *     to bean validation the status will still be 400 but the error body shape changes —
 *     these assertions only check the status code so they remain stable across that
 *     refactor.
 *   - Migration 001-schema.sql line 215 declares
 *     {@code FOREIGN KEY (suite_id) REFERENCES evaluation_suites(id) ON DELETE CASCADE}
 *     on {@code evaluation_cases}, so deleting a suite via {@code DELETE /suites/{id}}
 *     cleans up its cases. Case (e) pins this as a runtime invariant; a schema change
 *     that drops CASCADE would surface as orphaned case rows after the delete.
 *   - {@code evaluation_runs} FKs {@code agent_id} to {@code agents(id)} and {@code suite_id}
 *     to {@code evaluation_suites(id)} — neither is ON DELETE CASCADE. Deleting a suite
 *     with historical runs would 500; case (e) deliberately seeds only cases (no runs) to
 *     avoid that gap, and documents the constraint inline.
 *   - {@code POST /suites/{suiteId}/run} enqueues an {@code EVALUATION_RUN} job via
 *     {@link com.operativus.agentmanager.control.service.PersistentJobQueueService} and
 *     returns 202 + {@code {jobId: ...}}. {@link com.operativus.agentmanager.control.service.queue.EvaluationRunJobHandler#execute}
 *     invokes {@link com.operativus.agentmanager.control.service.EvaluationService#runSuite}
 *     synchronously and stores the {@code evaluation_runs.id} back in {@code background_jobs.result}.
 *     But {@code runSuite} spawns ANOTHER {@code Thread.ofVirtual().start(...)} to execute
 *     the cases, so the job completes well before case execution finishes. Case (g) uses
 *     {@link JobQueueTestSupport#awaitJobSuccess} for the outer job, then a separate
 *     Awaitility poll on {@code evaluation_runs.status} to catch the inner virtual-thread
 *     completion.
 *   - {@link com.operativus.agentmanager.control.service.EvaluationService#selectScorer}
 *     picks the scorer in a waterfall: regex-shaped {@code expectedOutput} (starts with
 *     {@code ^} and ends with {@code $}) → {@code regexMatchScorer}; else {@code llmJudgeScorer}
 *     if registered (it is, via {@code @Component} — so the LLM-judge branch is the
 *     default in production); else {@code semanticScorer}; else {@code exactMatchScorer}.
 *     Case (g) deliberately uses {@code "^.*$"} to force the regex path — it avoids the
 *     LLM-judge path whose JSON-structured-output parsing would trip on FakeChatModel's
 *     plain-text response, keeping the scoring deterministic.
 *   - {@link com.operativus.agentmanager.control.service.scoring.RegexMatchScorer} uses
 *     {@code Matcher.find()}, which requires a subsequence match — {@code "^.*$"} matches
 *     any non-empty string, which is the simplest stable pinning condition for a
 *     "passing result" shape in case (g).
 *   - Matrix §8 case 6 ("non-admin cannot trigger runs") and case 7 ("rate-limit prevents
 *     resource exhaustion") are NOT pinned here. The controller has no {@code @PreAuthorize}
 *     and the Resilience4j {@code llmEvaluations} rate limiter is internal to per-case
 *     execution (not per-request), so neither is reachable via a single-request black-box
 *     assertion. These gaps are documented in the Phase 3 summary as carry-forward.
 *   - Matrix §8 case 3 (SSE streaming) is left for the dedicated streaming test matrix
 *     entries. Exercising it here would duplicate {@code StreamingRunsRuntimeTest}'s
 *     SSE-frame harness without adding coverage of anything this file should own.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
         NoOpReflectionServiceConfig.class, JobQueueTestSupport.class})
public class EvaluationsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;
    @Autowired private JobQueueTestSupport jobs;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        fakeModel.reset();
    }

    // §8 — Case (a): POST /suites with a minimal body returns 200 + a JSON body carrying
    // the generated UUID, the supplied name/description, and createdBy="system" (the
    // controller's default when the payload omits createdBy). Pins the create path + the
    // non-idiomatic 200 (matrix asks for 201) so any refactor to align with REST norms is
    // caught and explicit.
    @Test
    void createSuiteReturns200WithPersistedRowAndCreatedByDefaultingToSystem() {
        HttpHeaders auth = authenticatedHeaders("eval-creator");

        Map<String, String> body = Map.of(
                "name", "Smoke Suite",
                "description", "Minimal suite used to pin the create path");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "POST /suites returns 200 today, not the 201 matrix §8 case 1 asks for — when this flips to 201, update the assertion deliberately");
        Map<String, Object> payload = resp.getBody();
        assertNotNull(payload);
        String suiteId = (String) payload.get("id");
        assertNotNull(suiteId, "response must carry the server-generated suite id so the UI can navigate to its detail page");
        assertEquals("Smoke Suite", payload.get("name"));
        assertEquals("system", payload.get("createdBy"),
                "controller defaults createdBy to 'system' when the payload omits it; a different value here would mean the default path changed and every audit that relies on the default is now ambiguous");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_suites WHERE id = ?", Long.class, suiteId);
        assertEquals(1L, rowCount, "evaluation_suites row must exist after the 200 — a 0 would indicate the @Transactional did not commit");
    }

    // §8 — Controller validation: POST /suites with a blank name is rejected with 400 by
    // the controller's own null/blank check, BEFORE the service runs. No row is created.
    // This complements case (a) by pinning the negative path.
    @Test
    void createSuiteWithBlankNameReturns400AndNoRowIsPersisted() {
        HttpHeaders auth = authenticatedHeaders("eval-badreq");

        Map<String, String> body = Map.of("name", "", "description", "blank name");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "controller must short-circuit blank names with 400 — a 500 here would mean the check is gone and IllegalArgumentException is leaking out of the service; a 200 would mean the check is gone entirely and empty-name rows can be created");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_suites WHERE description = ?", Long.class, "blank name");
        assertEquals(0L, rowCount, "no row must be persisted when the request is rejected — otherwise the validation is cosmetic");
    }

    // §8 — Case prerequisite: POST /suites/{id}/cases appends a case; GET /suites/{id}/cases
    // returns it. Pins the case-management API the run pipeline depends on.
    @Test
    void addCaseToSuiteReturns200AndListByIdExposesTheCase() {
        HttpHeaders auth = authenticatedHeaders("eval-case-adder");
        String suiteId = createSuiteViaApi(auth, "Suite With Cases");

        Map<String, String> caseBody = Map.of(
                "name", "greeting-case",
                "input", "Say hello.",
                "expectedOutput", "^.*$");
        ResponseEntity<Map<String, Object>> addResp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(caseBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, addResp.getStatusCode());
        String caseId = (String) addResp.getBody().get("id");
        assertNotNull(caseId);

        // G4 — /cases now returns a paginated envelope; extract `.content`.
        ResponseEntity<Map<String, Object>> listResp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) listResp.getBody().get("content");
        assertNotNull(cases);
        assertTrue(cases.stream().anyMatch(c -> caseId.equals(c.get("id"))),
                "GET /suites/{id}/cases must surface the just-created case — absence means findBySuiteId is not filtering by suite_id correctly");
    }

    // §8 — Controller validation: POST /suites/{id}/cases with no input is rejected with
    // 400 by the controller's explicit null check. Pins the negative-path contract.
    @Test
    void addCaseWithMissingInputReturns400AndNoRowIsPersisted() {
        HttpHeaders auth = authenticatedHeaders("eval-case-badreq");
        String suiteId = createSuiteViaApi(auth, "Suite For Bad Case");

        Map<String, String> caseBody = Map.of("name", "only-name-no-input");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(caseBody, auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "controller must reject a case without an input payload — an input-less case cannot be run by the agent, so accepting it would leak an unrunnable row into the DB");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_cases WHERE name = ?", Long.class, "only-name-no-input");
        assertEquals(0L, rowCount);
    }

    // §8 — Case (e): Deleting a suite cascades to its cases through the FK declared in
    // 001-schema.sql §11. Pins the runtime observable of ON DELETE CASCADE so a schema
    // change that drops it (leaving orphan case rows) surfaces here. NOTE: we do NOT seed
    // evaluation_runs for this suite because the run FK is NOT ON DELETE CASCADE — a
    // delete with runs would 500, which is a separate known gap (documented in the class
    // Javadoc).
    @Test
    void deleteSuiteCascadesToEvaluationCasesViaForeignKey() {
        HttpHeaders auth = authenticatedHeaders("eval-deleter");
        String suiteId = createSuiteViaApi(auth, "Suite To Delete");

        // Seed two cases directly via JDBC — faster than two HTTP POSTs and sufficient
        // for the delete-cascade assertion since we're not testing the create path here.
        jdbc.update("""
                INSERT INTO evaluation_cases (id, suite_id, name, input, expected_output)
                VALUES (?, ?, 'case-one', 'input one', '^.*$')
                """, "case-" + UUID.randomUUID(), suiteId);
        jdbc.update("""
                INSERT INTO evaluation_cases (id, suite_id, name, input, expected_output)
                VALUES (?, ?, 'case-two', 'input two', '^.*$')
                """, "case-" + UUID.randomUUID(), suiteId);

        Long casesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_cases WHERE suite_id = ?", Long.class, suiteId);
        assertEquals(2L, casesBefore, "precondition: two cases seeded under the suite");

        ResponseEntity<Void> delResp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, delResp.getStatusCode(),
                "DELETE /suites/{id} must return 204 — a 404 would mean the suite was already gone before we asked, a 500 likely means the FK cascade is broken and we tried to delete a parent with children");

        Long suiteRemaining = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_suites WHERE id = ?", Long.class, suiteId);
        assertEquals(0L, suiteRemaining, "the suite row must be gone after the 204");

        Long casesRemaining = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_cases WHERE suite_id = ?", Long.class, suiteId);
        assertEquals(0L, casesRemaining,
                "ON DELETE CASCADE on evaluation_cases.suite_id must sweep child rows — non-zero here means either the FK was dropped or the cascade clause was weakened to NO ACTION, which would leave orphan rows every time a suite is deleted");
    }

    // §8 — Case (f) — negative-path for matrix case 2: POST /run on an empty suite returns
    // 202 + jobId (controller doesn't care about emptiness), the background job fires,
    // and EvaluationService.runSuite throws IllegalStateException("Cannot run an empty
    // suite.") BEFORE persisting an evaluation_runs row. The job terminates FAILED after
    // its retry budget is exhausted — we force max_retries=0 so the first attempt is
    // terminal (same pattern as AgentsBulkRuntimeTest T012's DLQ pin).
    @Test
    void runEmptySuiteThroughJobQueueFailsAndNoEvaluationRunRowIsCreated() {
        HttpHeaders auth = authenticatedHeaders("eval-empty-runner");
        String suiteId = createSuiteViaApi(auth, "Empty Suite");
        String agentId = createAgentViaApi(auth, "Placeholder Agent For Empty Run");

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/run?agentId=" + agentId),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, runResp.getStatusCode(),
                "POST /run always enqueues a job synchronously and returns 202 — validation of emptiness is deferred to the handler");
        String jobId = (String) runResp.getBody().get("jobId");
        assertNotNull(jobId);

        jdbc.update("UPDATE background_jobs SET max_retries = 0 WHERE id = ?", jobId);
        jobs.processNow();
        jobs.awaitJobFailure(jobId, Duration.ofSeconds(10));

        Long runCount = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_runs WHERE suite_id = ?", Long.class, suiteId);
        assertEquals(0L, runCount,
                "runSuite must short-circuit BEFORE inserting an evaluation_runs row when the suite has no cases — a >0 here would mean the empty-suite check moved below the save and orphaned runs now pile up");
    }

    // §8 — Case (g) — happy path for matrix cases 2 + 4: full pipeline from POST /run
    // through the outer job, through EvaluationService.runSuite's inner virtual thread,
    // through AgentService.run against FakeChatModel, through RegexMatchScorer, to
    // evaluation_runs.status = COMPLETED and an evaluation_results row with is_passing
    // = true. This is the single end-to-end case that verifies every hop of the pipeline
    // works together — if any hop regresses, this test fails first.
    @Test
    void runSuiteEndToEndCompletesWithPassingRegexResult() {
        HttpHeaders auth = authenticatedHeaders("eval-runner");
        String suiteId = createSuiteViaApi(auth, "Happy Path Suite");
        String agentId = createAgentViaApi(auth, "Eval Target Agent");

        // ^.*$ forces the regex scorer path (selectScorer waterfall) and matches any
        // non-empty FakeChatModel response. Using exactMatch would require plumbing the
        // fake's output through the case's expectedOutput, which ties this test to the
        // fake's response format — brittle coupling for no extra coverage.
        Map<String, String> caseBody = Map.of(
                "name", "any-output-case",
                "input", "Respond however you like.",
                "expectedOutput", "^.*$");
        ResponseEntity<Map<String, Object>> addCase = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(caseBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, addCase.getStatusCode());

        fakeModel.respondWith("canned-agent-response");

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/v1/evaluations/suites/" + suiteId + "/run?agentId=" + agentId),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, runResp.getStatusCode());
        String jobId = (String) runResp.getBody().get("jobId");

        jobs.processNow();
        jobs.awaitJobSuccess(jobId, Duration.ofSeconds(15));

        // The outer job returns as soon as runSuite hands off to its inner virtual thread,
        // so we still need to poll evaluation_runs separately for the true terminal state.
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbc.queryForObject(
                            "SELECT status FROM evaluation_runs WHERE suite_id = ?", String.class, suiteId);
                    assertEquals("COMPLETED", status,
                            "evaluation_runs.status must reach COMPLETED once the inner virtual thread finalizes — any other value means finalizeAndSaveRunMetrics didn't run");
                });

        Map<String, Object> runRow = jdbc.queryForMap(
                "SELECT id, total_cases, passed_cases, failed_cases, average_score FROM evaluation_runs WHERE suite_id = ?",
                suiteId);
        assertEquals(1, runRow.get("total_cases"));
        assertEquals(1, runRow.get("passed_cases"),
                "the single case must pass against regex ^.*$ given FakeChatModel returned a non-empty string — 0 here means the scorer waterfall picked a different scorer or the FakeChatModel response was empty");
        assertEquals(0, runRow.get("failed_cases"));
        assertEquals(1.0, ((Number) runRow.get("average_score")).doubleValue(),
                "regex scorer returns 1.0 on match; aggregate over one case should equal 1.0");

        String runId = (String) runRow.get("id");
        Long resultCount = jdbc.queryForObject(
                "SELECT count(*) FROM evaluation_results WHERE run_id = ? AND is_passing = true",
                Long.class, runId);
        assertEquals(1L, resultCount,
                "exactly one passing evaluation_results row must exist — 0 would mean saveGranularResult never committed, >1 means somehow the single case was scored twice");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        // ROLE_ADMIN required for /api/v1/evaluations write paths (see
        // EvaluationControllerAuthzRuntimeTest for the gate pins).
        return authenticateAs(username, username + "@test.local", "pass-eval-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createSuiteViaApi(HttpHeaders auth, String name) {
        Map<String, String> body = Map.of("name", name, "description", "runtime test suite");
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "suite fixture precondition");
        return (String) resp.getBody().get("id");
    }

    /**
     * Seeds a minimal {@code models} row (provider="fake" → resolved by FakeModelProviderConfig)
     * then creates an agent via {@code POST /api/admin/agents} with memoryEnabled=true so
     * the run path exercises {@code MessageChatMemoryAdvisor} the same way production does.
     */
    private String createAgentViaApi(HttpHeaders auth, String name) {
        String modelId = "eval-model-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);

        String agentId = "eval-agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Eval runtime target");
        body.put("instructions", "Be concise.");
        body.put("model", modelId);
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "agent fixture precondition: must be created before any /run endpoint references it");
        return agentId;
    }
}
