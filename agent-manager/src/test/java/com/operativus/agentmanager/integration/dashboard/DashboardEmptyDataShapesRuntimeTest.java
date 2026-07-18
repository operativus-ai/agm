package com.operativus.agentmanager.integration.dashboard;

import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the FE-contract response envelope on dashboard read
 *   endpoints when the database is empty. Prevents FE null-handling drift — every
 *   list/map endpoint must return its canonical empty shape (e.g. {@code []} or
 *   enum-keyed zero-filled map), never {@code null} and never 404.
 *
 *   <p>Pinned endpoints (5):
 *   <ul>
 *     <li>{@code GET /api/v1/observability/background-jobs/status-summary} —
 *         enum-keyed Map zero-filled per {@code JobStatus} ({@code BackgroundJobController}
 *         pre-fills {@code EnumMap<JobStatus,Long>} with all enum values=0).</li>
 *     <li>{@code GET /api/v1/finops/trends?days=7} — empty trend series</li>
 *     <li>{@code GET /api/v1/finops/allocations?days=7} — empty allocations</li>
 *     <li>{@code GET /api/v1/finops/anomalies/active} — no anomalies</li>
 *     <li>{@code GET /api/v1/finops/burn-rates/active} — no active windows</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class DashboardEmptyDataShapesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @Autowired private BurnRateMonitorService burnRateMonitor;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        // /finops/burn-rates/active and /finops/anomalies/active read in-memory accumulators
        // that survive a DB truncate; clear them so a prior test in the cached context can't
        // bleed windows into these empty-shape assertions.
        burnRateMonitor.reset();
        adminAuth = authenticateAs("dashboard-empty-admin",
                "dashboard-empty-admin@test.local", "pass-dea-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void backgroundJobsStatusSummaryReturnsEnumFilledMapOnEmptyDb() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/observability/background-jobs/status-summary"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "status-summary must return non-null Map");
        // Controller pre-fills the EnumMap with all JobStatus values=0; check the
        // canonical keys exist (FE expects PENDING/RUNNING/COMPLETED/etc. always present).
        for (String key : List.of("QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED")) {
            assertTrue(body.containsKey(key),
                    "status-summary must contain key '" + key
                            + "' (enum-filled by BackgroundJobController); got keys: "
                            + body.keySet());
            assertEquals(0, ((Number) body.get(key)).intValue(),
                    "key '" + key + "' must be 0 on empty DB; got " + body.get(key));
        }
    }

    @Test
    void finopsTrendsReturnsEmptyListOnEmptyDb() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/finops/trends?days=7"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "/finops/trends must return 200; got " + response.getStatusCode());
        assertNotNull(response.getBody(),
                "/finops/trends body must be non-null (FE expects array)");
    }

    @Test
    void finopsAllocationsReturnsEmptyListOnEmptyDb() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/finops/allocations?days=7"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody(),
                "/finops/allocations body must be non-null");
    }

    @Test
    void finopsAnomaliesReturnsEmptyListOnEmptyDb() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/finops/anomalies/active"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody(),
                "/finops/anomalies/active body must be non-null");
        assertTrue(response.getBody().isEmpty(),
                "empty DB must return [] anomalies; got " + response.getBody().size());
    }

    @Test
    void finopsBurnRatesReturnsEmptyListOnEmptyDb() {
        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/finops/burn-rates/active"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody(),
                "/finops/burn-rates/active body must be non-null");
        assertTrue(response.getBody().isEmpty(),
                "empty DB must return [] burn-rate windows");
    }
}
