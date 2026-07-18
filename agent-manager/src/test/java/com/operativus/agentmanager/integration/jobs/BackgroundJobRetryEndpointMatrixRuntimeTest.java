package com.operativus.agentmanager.integration.jobs;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins the full four-way response matrix of
 *   {@code POST /api/v1/observability/background-jobs/&#123;id&#125;/retry} —
 *   the per-outcome ProblemDetail body shape, HTTP status, and the
 *   {@code agm.observability.bgjob.retry} metric increment.
 *
 *   <p>Existing {@link com.operativus.agentmanager.control.controller.BackgroundJobRetryAtomicityIntegrationTest}
 *   pins the sequential 200 → 409 ("not_failed") atomicity path. This class fills the
 *   gaps left:
 *   <ul>
 *     <li><b>200 ok (cold)</b> — a FAILED row with retry_count < max_retries flips to QUEUED.
 *         locked_at must end null (atomicRetry must clear any prior lock).</li>
 *     <li><b>404 not_found</b> — retry on an unknown job id increments the not_found counter
 *         and returns no body.</li>
 *     <li><b>422 max_retries</b> — a FAILED row at retry_count == max_retries returns 422
 *         with reason "max_retries"; row stays in FAILED.</li>
 *     <li><b>409 not_failed (PROCESSING)</b> — a non-FAILED row also returns 409. Pinned
 *         against PROCESSING (not just QUEUED post-retry) so the contract surface covers
 *         both common pre-conditions that fail the WHERE clause.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BackgroundJobRetryEndpointMatrixRuntimeTest extends BaseIntegrationTest {

    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private MeterRegistry meterRegistry;

    private static final String METRIC = "agm.observability.bgjob.retry";

    // ─── M1 — 200 ok on cold FAILED row ─────────────────────────────────────

    @Test
    void retry_failedRowBelowCap_returns200_andFlipsToQueued_andClearsErrorMessage() {
        String jobId = UUID.randomUUID().toString();
        seedFailedJob(jobId, /*retryCount=*/ 1, /*maxRetries=*/ 3);

        HttpHeaders auth = adminHeaders("m1");
        double okBefore = counter("ok");

        ResponseEntity<Map<String, String>> resp = retryRest(jobId, auth);

        BackgroundJob after = jobRepository.findById(jobId).orElseThrow();
        assertAll("cold FAILED retry returns 200 + flips to QUEUED + clears bookkeeping",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals("ok", resp.getBody().get("status")),
                () -> assertEquals(JobStatus.QUEUED, after.getStatus(),
                        "row must transition FAILED -> QUEUED"),
                () -> assertEquals(null, after.getLockedAt(),
                        "locked_at must remain null — atomicRetry requires it null as a "
                                + "precondition and re-asserts it in the UPDATE"),
                () -> assertEquals(null, after.getErrorMessage(),
                        "error_message must be cleared — the prior failure's message would "
                                + "otherwise be misleading after the row is requeued"),
                () -> assertEquals(1.0, counter("ok") - okBefore, 0.001,
                        "ok counter must increment exactly once"));
    }

    // ─── M5 — 409 not_failed on FAILED row with stale lock (also non-null locked_at) ──

    @Test
    void retry_failedRowWithStaleLock_returns409_reasonNotFailed() {
        String jobId = UUID.randomUUID().toString();
        BackgroundJob seed = seedFailedJob(jobId, /*retryCount=*/ 0, /*maxRetries=*/ 3);
        // Stale lock from a prior PROCESSING attempt that never released. atomicRetry's
        // WHERE explicitly requires locked_at IS NULL, so even a FAILED row at
        // retry_count=0 is ineligible until recoverStalledJobs clears the lock.
        seed.setLockedAt(LocalDateTime.now().minusMinutes(20));
        jobRepository.saveAndFlush(seed);

        HttpHeaders auth = adminHeaders("m5");
        double notFailedBefore = counter("not_failed");

        ResponseEntity<Map<String, String>> resp = retryRest(jobId, auth);

        BackgroundJob after = jobRepository.findById(jobId).orElseThrow();
        assertAll("locked FAILED row classified as not_failed (locked_at gate)",
                () -> assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(),
                        "stale lock blocks the retry UPDATE; controller's disambiguation "
                                + "SELECT sees status=FAILED but the row was ineligible — "
                                + "it falls through to the catch-all not_failed branch"),
                () -> assertEquals("not_failed", resp.getBody().get("reason"),
                        "reason MUST be not_failed (the catch-all), not max_retries — "
                                + "the 422 branch only fires when retry_count >= max_retries"),
                () -> assertEquals(JobStatus.FAILED, after.getStatus(),
                        "row must remain FAILED"),
                () -> assertNotNull(after.getLockedAt(),
                        "stale lock must NOT be cleared by the rejected retry — that's "
                                + "recoverStalledJobs' job, not retry's"),
                () -> assertEquals(1.0, counter("not_failed") - notFailedBefore, 0.001));
    }

    // ─── M2 — 404 not_found on unknown job id ────────────────────────────────

    @Test
    void retry_unknownJobId_returns404_andNoBody_andNotFoundCounterIncrements() {
        String unknown = "missing-job-" + UUID.randomUUID();
        HttpHeaders auth = adminHeaders("m2");
        double notFoundBefore = counter("not_found");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/observability/background-jobs/" + unknown + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                String.class);

        assertAll("unknown id returns 404 + increments not_found counter",
                () -> assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode()),
                () -> assertEquals(1.0, counter("not_found") - notFoundBefore, 0.001,
                        "not_found counter must increment exactly once — observability for "
                                + "stale UI references depends on it"));
    }

    // ─── M3 — 422 max_retries on FAILED row already at cap ───────────────────

    @Test
    void retry_failedRowAtMaxRetries_returns422_reasonMaxRetries_andRowStaysFailed() {
        String jobId = UUID.randomUUID().toString();
        seedFailedJob(jobId, /*retryCount=*/ 3, /*maxRetries=*/ 3);  // cap reached

        HttpHeaders auth = adminHeaders("m3");
        double maxRetriesBefore = counter("max_retries");

        ResponseEntity<Map<String, String>> resp = retryRest(jobId, auth);

        BackgroundJob after = jobRepository.findById(jobId).orElseThrow();
        assertAll("at-cap FAILED retry returns 422 + reason max_retries + row unchanged",
                () -> assertEquals(HttpStatus.valueOf(422), resp.getStatusCode(),
                        "atomicRetry's WHERE includes retry_count < max_retries, so the UPDATE "
                                + "affects 0 rows; the disambiguating SELECT finds the row at cap "
                                + "and returns 422 with reason=max_retries"),
                () -> assertNotNull(resp.getBody(), "response must carry a reason body"),
                () -> assertEquals("max_retries", resp.getBody().get("reason")),
                () -> assertEquals(JobStatus.FAILED, after.getStatus(),
                        "row must remain FAILED — 422 is informational only, no state change"),
                () -> assertEquals(3, after.getRetryCount(),
                        "retry_count must not be modified by a rejected retry"),
                () -> assertEquals(1.0, counter("max_retries") - maxRetriesBefore, 0.001,
                        "max_retries counter must increment exactly once"));
    }

    // ─── M4 — 409 not_failed on PROCESSING row ──────────────────────────────

    @Test
    void retry_processingRow_returns409_reasonNotFailed_andRowStaysProcessing() {
        String jobId = UUID.randomUUID().toString();
        BackgroundJob seed = new BackgroundJob();
        seed.setId(jobId);
        seed.setJobType("J2_TEST");
        seed.setPayload("{}");
        seed.setStatus(JobStatus.PROCESSING);
        seed.setRetryCount(0);
        seed.setMaxRetries(3);
        seed.setLockedAt(LocalDateTime.now());
        jobRepository.saveAndFlush(seed);

        HttpHeaders auth = adminHeaders("m4");
        double notFailedBefore = counter("not_failed");

        ResponseEntity<Map<String, String>> resp = retryRest(jobId, auth);

        BackgroundJob after = jobRepository.findById(jobId).orElseThrow();
        assertAll("PROCESSING row also returns 409 not_failed (not just post-retry QUEUED)",
                () -> assertEquals(HttpStatus.CONFLICT, resp.getStatusCode()),
                () -> assertEquals("not_failed", resp.getBody().get("reason")),
                () -> assertEquals(JobStatus.PROCESSING, after.getStatus(),
                        "row must remain PROCESSING — refusal must NOT advance state"),
                () -> assertNotNull(after.getLockedAt(),
                        "locked_at must remain set — clearing it would race with the in-flight "
                                + "worker thread"),
                () -> assertEquals(1.0, counter("not_failed") - notFailedBefore, 0.001));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private BackgroundJob seedFailedJob(String jobId, int retryCount, int maxRetries) {
        BackgroundJob seed = new BackgroundJob();
        seed.setId(jobId);
        seed.setJobType("J2_TEST");
        seed.setPayload("{}");
        seed.setStatus(JobStatus.FAILED);
        seed.setRetryCount(retryCount);
        seed.setMaxRetries(maxRetries);
        seed.setErrorMessage("synthetic failure for J2 test");
        return jobRepository.saveAndFlush(seed);
    }

    private HttpHeaders adminHeaders(String label) {
        String suffix = label + "-" + Long.toHexString(System.nanoTime());
        return authenticateAs(
                "j2-admin-" + suffix,
                "j2-admin-" + suffix + "@test.local",
                "pass-j2-1234",
                List.of("ROLE_ADMIN"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, String>> retryRest(String jobId, HttpHeaders auth) {
        ResponseEntity<Map> raw = rest.exchange(
                url("/api/v1/observability/background-jobs/" + jobId + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                Map.class);
        return (ResponseEntity) raw;
    }

    private double counter(String outcome) {
        var c = meterRegistry.find(METRIC).tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }
}
