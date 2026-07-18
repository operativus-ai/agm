package com.operativus.agentmanager.integration.observability;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the HTTP contract for {@code BackgroundJobController}'s
 *   three endpoints (list, status-summary, atomic-retry) and the four
 *   {@code agm.observability.bgjob.retry{outcome}} Micrometer counters that drive the
 *   platform-ops dashboard. Companion to {@link com.operativus.agentmanager.integration.crosscutting.JobQueueRuntimeTest}
 *   which covers the dispatcher/queue side; this class covers the read-side HTTP surface
 *   only.
 *   <p>
 *   Authz coverage is delegated to {@link com.operativus.agentmanager.integration.security.AdminEndpointAuthzRuntimeTest}'s
 *   central matrix; this class only exercises ROLE_ADMIN happy paths and behavior branches.
 *   <p>
 *   Cross-org isolation is intentionally NOT tested here because the controller has no
 *   org-scoping (ADMIN-only platform observability surface). The {@code BackgroundJob}
 *   entity has no {@code org_id} column. Surfaced as a finding for future review.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BackgroundJobsRuntimeTest extends BaseIntegrationTest {

    private static final String COUNTER_NAME = "agm.observability.bgjob.retry";

    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private MeterRegistry meterRegistry;

    /**
     * Inserts a {@link BackgroundJob} row directly via JPA. Uses {@code .save()} so
     * Hibernate's {@code @CreationTimestamp} fires (created_at populated). For tests that
     * need spaced-out timestamps for pagination determinism, use
     * {@link #seedJobWithCreatedAt} instead.
     */
    private BackgroundJob seedJob(JobStatus status, int retryCount, int maxRetries) {
        BackgroundJob j = new BackgroundJob(
                UUID.randomUUID().toString(),
                "agent-" + UUID.randomUUID().toString().substring(0, 8),
                "test.job",
                "{}");
        j.setStatus(status);
        j.setRetryCount(retryCount);
        j.setMaxRetries(maxRetries);
        return jobRepository.save(j);
    }

    /**
     * Inserts via JDBC with an explicit {@code created_at}. Required for pagination
     * tests because @CreationTimestamp resolution can produce ties at fast seed rates,
     * making sort order non-deterministic in Postgres.
     */
    private String seedJobWithCreatedAt(JobStatus status, LocalDateTime createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO background_jobs
                  (id, agent_id, job_type, payload, status, retry_count, max_retries,
                   priority, created_at)
                VALUES (?, ?, 'test.job', '{}', ?, 0, 3, 'NORMAL', ?)
                """, id, "agent-" + id.substring(0, 8), status.name(), createdAt);
        return id;
    }

    private double counter(String outcome) {
        Counter c = meterRegistry.find(COUNTER_NAME).tag("outcome", outcome).counter();
        assertNotNull(c, "counter " + COUNTER_NAME + "{outcome=" + outcome + "} must be registered");
        return c.count();
    }

    private HttpHeaders adminAuth() {
        return authenticateAs("bgjob-admin-" + UUID.randomUUID().toString().substring(0, 6),
                "bgjob-admin@test.local",
                "pass-bgjob-1234",
                List.of("ROLE_ADMIN"));
    }

    // case 1 — list with status filter returns only matching rows in createdAt-DESC order.
    // Pins BackgroundJobController.list filtered branch + repository.findByStatusOrderByCreatedAtDesc.
    @Test
    void listFilteredByStatus_returnsOnlyMatchingRows() {
        seedJob(JobStatus.QUEUED, 0, 3);
        seedJob(JobStatus.PROCESSING, 0, 3);
        seedJob(JobStatus.FAILED, 1, 3);
        seedJob(JobStatus.FAILED, 2, 3);
        seedJob(JobStatus.COMPLETED, 0, 3);

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/observability/background-jobs?status=FAILED"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(2, content.size(), "filter must return exactly the 2 FAILED rows");
        for (Map<String, Object> row : content) {
            assertEquals("FAILED", row.get("status"),
                    "filtered list must contain only the requested status; got " + row.get("status"));
        }
    }

    // case 2 — status-summary returns the full enum (every JobStatus key present, zero-filled).
    // Pins BackgroundJobController.statusSummary's "fill missing statuses with 0L" contract.
    @Test
    void statusSummary_returnsAllEnumKeysWithCorrectCounts() {
        seedJob(JobStatus.QUEUED, 0, 3);
        seedJob(JobStatus.QUEUED, 0, 3);
        seedJob(JobStatus.FAILED, 1, 3);

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, Long>> response = rest.exchange(
                url("/api/v1/observability/background-jobs/status-summary"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Long> body = response.getBody();
        assertNotNull(body);

        for (JobStatus s : JobStatus.values()) {
            assertTrue(body.containsKey(s.name()),
                    "status-summary must include every JobStatus key; missing " + s);
        }
        assertEquals(2L, body.get("QUEUED"));
        assertEquals(1L, body.get("FAILED"));
        assertEquals(0L, body.get("DLQ"), "statuses with no rows must report 0, not be omitted");
    }

    // case 3 — retry happy path: FAILED row + retry_count < max_retries → 200 {status:ok},
    // row promoted to QUEUED, ok counter +1.
    @Test
    void retryFailedJob_promotesToQueuedAndIncrementsOkCounter() {
        BackgroundJob failed = seedJob(JobStatus.FAILED, 1, 3);
        double before = counter("ok");

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, String>> response = rest.exchange(
                url("/api/v1/observability/background-jobs/" + failed.getId() + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ok", response.getBody().get("status"));

        BackgroundJob reloaded = jobRepository.findById(failed.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, reloaded.getStatus(),
                "atomicRetry must promote FAILED → QUEUED");

        assertEquals(1.0, counter("ok") - before, 1e-9,
                "ok counter must increment by exactly 1 on the success branch");
    }

    // case 4 — retry on a non-FAILED row → 409 {reason:not_failed}, status unchanged,
    // not_failed counter +1.
    @Test
    void retryNonFailedJob_returns409AndIncrementsNotFailedCounter() {
        BackgroundJob queued = seedJob(JobStatus.QUEUED, 0, 3);
        double before = counter("not_failed");

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, String>> response = rest.exchange(
                url("/api/v1/observability/background-jobs/" + queued.getId() + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("not_failed", response.getBody().get("reason"));

        BackgroundJob reloaded = jobRepository.findById(queued.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, reloaded.getStatus(),
                "non-eligible retry must not mutate row status");

        assertEquals(1.0, counter("not_failed") - before, 1e-9);
    }

    // case 5 — retry at retry cap (retry_count >= max_retries on a FAILED row) → 422
    // {reason:max_retries}, max_retries counter +1.
    @Test
    void retryAtMaxRetries_returns422AndIncrementsMaxRetriesCounter() {
        BackgroundJob exhausted = seedJob(JobStatus.FAILED, 3, 3);
        double before = counter("max_retries");

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, String>> response = rest.exchange(
                url("/api/v1/observability/background-jobs/" + exhausted.getId() + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(422, response.getStatusCode().value(),
                "Spring 6 renamed UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT; pin the numeric code");
        assertNotNull(response.getBody());
        assertEquals("max_retries", response.getBody().get("reason"));

        assertEquals(1.0, counter("max_retries") - before, 1e-9);
    }

    // case 6 — retry on missing id → 404 (no body), not_found counter +1.
    @Test
    void retryUnknownJob_returns404AndIncrementsNotFoundCounter() {
        double before = counter("not_found");

        HttpHeaders auth = adminAuth();
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/observability/background-jobs/" + UUID.randomUUID() + "/retry"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(1.0, counter("not_found") - before, 1e-9);
    }

    // case 7 — pagination determinism. Seed 7 FAILED rows with explicit, spaced
    // created_at timestamps; page=1&size=3 must return rows 4-6 by createdAt-DESC order.
    @Test
    void pagination_returnsDeterministicSliceInCreatedAtDescOrder() {
        LocalDateTime base = LocalDateTime.of(2026, 4, 29, 12, 0, 0);
        // Newest → oldest: t6, t5, t4, t3, t2, t1, t0
        for (int i = 0; i < 7; i++) {
            seedJobWithCreatedAt(JobStatus.FAILED, base.plusSeconds(i));
        }

        HttpHeaders auth = adminAuth();
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/observability/background-jobs?status=FAILED&page=1&size=3"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        // Boot 4 Page envelope: {content:[...], page:{size,number,totalElements,totalPages}}.
        @SuppressWarnings("unchecked")
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        assertNotNull(pageMeta, "Boot 4 page response must include 'page' meta object");
        assertEquals(7, ((Number) pageMeta.get("totalElements")).intValue(),
                "totalElements must reflect all 7 seeded rows");
        assertEquals(1, ((Number) pageMeta.get("number")).intValue(), "page index must echo the request");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(3, content.size(), "page=1 size=3 must return 3 rows");

        // Page 1 (zero-indexed) of size 3 in DESC order = rows at offsets 3, 4, 5
        // i.e. timestamps base+3s, base+2s, base+1s.
        String first = (String) content.get(0).get("createdAt");
        String second = (String) content.get(1).get("createdAt");
        String third = (String) content.get(2).get("createdAt");
        assertTrue(first.compareTo(second) > 0,
                "rows must be returned in createdAt-DESC order; first=" + first + " second=" + second);
        assertTrue(second.compareTo(third) > 0,
                "rows must be returned in createdAt-DESC order; second=" + second + " third=" + third);
        assertFalse(first.equals(second) || second.equals(third),
                "spaced timestamps must produce strictly distinct values");
    }
}
