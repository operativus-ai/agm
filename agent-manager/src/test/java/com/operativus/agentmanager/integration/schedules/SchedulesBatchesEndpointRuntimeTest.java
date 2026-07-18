package com.operativus.agentmanager.integration.schedules;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin {@code GET /api/v1/schedules/batches} contract.
 *   {@code ScheduleService.getSpotBatches} maps {@code spotBatchJobRepository
 *   .findAllByOrgId(callerOrgId)} → {@code List<SpotBatchJobDTO>}, gated by
 *   {@code @PreAuthorize("hasRole('ADMIN')")} at the controller. F5 added the
 *   {@code org_id} column on {@code spot_batch_jobs} (changeset 070) and the
 *   tenant-scoped repo finder; pre-F5 the endpoint was globally visible to every
 *   ROLE_ADMIN.
 *
 *   <p>Cases:
 *   <ul>
 *     <li>P1.4-1 — empty table → 200 + empty list.</li>
 *     <li>P1.4-2 — multi-row happy path + full DTO field map.</li>
 *     <li>P1.4-3 — RBAC: ROLE_USER → 403 from the method-level {@code @PreAuthorize}.</li>
 *     <li>P1.4-4 — null progress / cost coerced to 0 on the wire.</li>
 *     <li>P1.4-5 — cross-tenant isolation (F5): org A's GET must not see org B's rows.</li>
 *   </ul>
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesBatchesEndpointRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("DELETE FROM spot_batch_jobs");
    }

    // P1.4-1 — Empty table: GET /batches returns 200 + empty list. Pins the no-rows
    // contract — the endpoint must NOT 404 or 204; it returns an empty JSON array.
    @Test
    void getBatches_emptyTable_returns200WithEmptyList() {
        HttpHeaders auth = adminAuth("batches-empty");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/schedules/batches"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertAll("empty batches response",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertNotNull(resp.getBody(), "empty list must serialize as [], not null"),
                () -> assertTrue(resp.getBody().isEmpty(), "no rows → empty content"));
    }

    // P1.4-2 — Multi-row happy path. Seed 2 spot_batch_jobs rows; assert both appear in
    // the response with all 6 DTO fields populated (id, job, status, progress, cost,
    // compute). Pins SpotBatchJobDTO wire shape.
    @Test
    void getBatches_multipleRows_returnsAllWithExpectedFields() {
        HttpHeaders auth = adminAuth("batches-multi");
        String id1 = seedSpotBatchJob("job-finetune", "RUNNING", 42, 1.23, "gpu-a100");
        String id2 = seedSpotBatchJob("job-eval", "COMPLETED", 100, 0.55, "cpu-c6i");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/schedules/batches"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> body = resp.getBody();
        assertEquals(2, body.size(), "both seeded batches must appear in /batches");

        Map<String, Object> row1 = body.stream()
                .filter(m -> id1.equals(m.get("id"))).findFirst().orElseThrow();
        assertAll("SpotBatchJobDTO field map (6 fields)",
                () -> assertEquals("job-finetune", row1.get("job")),
                () -> assertEquals("RUNNING", row1.get("status")),
                () -> assertEquals(42, ((Number) row1.get("progress")).intValue()),
                () -> assertEquals(1.23, ((Number) row1.get("cost")).doubleValue(), 0.0001),
                () -> assertEquals("gpu-a100", row1.get("compute")),
                () -> assertTrue(row1.containsKey("id")
                                && row1.containsKey("job")
                                && row1.containsKey("status")
                                && row1.containsKey("progress")
                                && row1.containsKey("cost")
                                && row1.containsKey("compute"),
                        "all 6 SpotBatchJobDTO keys must be present in wire response"));
    }

    // P1.4-3 — RBAC: ROLE_USER hits the method-level @PreAuthorize and 403s. Pin this
    // to forward-guard against the gate accidentally widening (e.g. dropping the
    // hasRole check during a refactor would make sensitive cost data globally
    // readable).
    @Test
    void getBatches_roleUser_returns403() {
        HttpHeaders userAuth = roleUserAuth("batches-user");
        seedSpotBatchJob("job-secret", "RUNNING", 1, 0.01, "cpu-tiny");

        // RestTemplate would 5xx-throw trying to deserialize the application/problem+json
        // error body as a List<Map>. Use String body and parse status only.
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/schedules/batches"),
                HttpMethod.GET, new HttpEntity<>(userAuth), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER must hit method-level @PreAuthorize('hasRole(ADMIN)') and be rejected");
    }

    // P1.4-4 — Defaults: progress=null in DB and cost=null in DB both surface as 0 in
    // the DTO (controller maps null → 0 explicitly). Pin this so a future refactor
    // doesn't silently start emitting null on the wire — null vs 0 matters to FE
    // formatters.
    @Test
    void getBatches_nullProgressAndCost_serializeAsZeroNotNull() {
        HttpHeaders auth = adminAuth("batches-nulls");
        String id = seedSpotBatchJobWithNulls("job-no-progress", "PENDING", "gpu-old");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/schedules/batches"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        Map<String, Object> row = resp.getBody().stream()
                .filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();

        assertAll("null-to-zero coercion in controller mapping",
                () -> assertEquals(0, ((Number) row.get("progress")).intValue(),
                        "null progress in DB must surface as 0 on the wire — pinned in controller mapping"),
                () -> assertEquals(0.0, ((Number) row.get("cost")).doubleValue(), 0.0001,
                        "null cost in DB must surface as 0.0 on the wire"));
    }

    // P1.4-5 — F5 tenant isolation. Seed one row in DEFAULT_SYSTEM_ORG (caller's org) and
    // one in a different org; the GET response must contain only the caller's row.
    @Test
    void getBatches_crossTenant_callerSeesOnlyOwnOrgRows() {
        HttpHeaders auth = adminAuth("batches-isolation");
        String mine = seedSpotBatchJob("job-mine", "RUNNING", 10, 0.5, "gpu-mine");
        String theirs = seedSpotBatchJobForOrg(
                "job-theirs", "RUNNING", 20, 1.0, "gpu-theirs", "spot-other-org");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/schedules/batches"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        List<Map<String, Object>> body = resp.getBody();
        assertAll("F5 tenant scope",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(1, body.size(),
                        "caller must only see DEFAULT_SYSTEM_ORG rows; got " + body),
                () -> assertEquals(mine, body.get(0).get("id"),
                        "the visible row must be the caller-org row, not the cross-tenant one"),
                () -> assertTrue(body.stream().noneMatch(m -> theirs.equals(m.get("id"))),
                        "cross-tenant row id=" + theirs + " must NOT appear in caller's response"));
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-batches-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders roleUserAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-batches-1234",
                List.of("ROLE_USER"));
    }

    /**
     * Seeds a row without an explicit {@code org_id}; the column default
     * ({@code DEFAULT_SYSTEM_ORG}, changeset 070) makes it visible to the default
     * test caller (which {@code authenticateAs} binds to {@code DEFAULT_SYSTEM_ORG}).
     */
    private String seedSpotBatchJob(String job, String status, int progress, double cost, String compute) {
        String id = "spot-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spot_batch_jobs (id, job, status, progress, cost, compute, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), now())
                """, id, job, status, progress, cost, compute);
        return id;
    }

    private String seedSpotBatchJobWithNulls(String job, String status, String compute) {
        String id = "spot-null-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spot_batch_jobs (id, job, status, progress, cost, compute, created_at, updated_at)
                VALUES (?, ?, ?, NULL, NULL, ?, now(), now())
                """, id, job, status, compute);
        return id;
    }

    /** Seeds a row stamped with an explicit {@code org_id} — used by the isolation test. */
    private String seedSpotBatchJobForOrg(String job, String status, int progress, double cost,
                                          String compute, String orgId) {
        String id = "spot-org-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spot_batch_jobs (id, job, status, progress, cost, compute, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, job, status, progress, cost, compute, orgId);
        return id;
    }
}
