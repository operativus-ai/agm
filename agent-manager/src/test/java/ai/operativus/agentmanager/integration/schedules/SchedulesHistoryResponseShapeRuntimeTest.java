package ai.operativus.agentmanager.integration.schedules;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the {@code ScheduleRunDTO} wire shape returned by
 *   {@code GET /api/v1/schedules/{id}/runs}. The DTO has 7 fields consumed by the
 *   FE schedule-detail page; a silent rename or drop breaks operator visibility
 *   into schedule execution history.
 *
 *   {@link SchedulesRuntimeTest#historyEndpoint_exposedAsRunsNotHistory_returnsPastFiresDescending}
 *   pins the endpoint path + DESC ordering. This test pins the full DTO field map
 *   across COMPLETED / FAILED / RUNNING terminal states.
 *
 *   <p>F4: the endpoint is now paginated. {@code ScheduleService.getScheduleRuns}
 *   takes a {@code Pageable} and returns a {@code Page<ScheduleRunDTO>}; the wire
 *   shape is the standard nested-content envelope pinned by
 *   {@code spring.data.web.pageable.serialization-mode=direct}. Default sort is
 *   startedAt DESC and is enforced by the derived-method name on the repository
 *   layer; the {@code Pageable}'s sort field is ignored.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesHistoryResponseShapeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    /**
     * Spring Boot 4 / Spring Data 4 with {@code spring.data.web.pageable.serialization-mode=direct}
     * nests metadata under {@code page} ({@code size}, {@code number}, {@code totalElements},
     * {@code totalPages}). Helper hides the cast.
     */
    @SuppressWarnings("unchecked")
    private static long totalElementsOf(Map<String, Object> page) {
        Map<String, Object> meta = (Map<String, Object>) page.get("page");
        return ((Number) meta.get("totalElements")).longValue();
    }

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P2.4-1 — Full DTO field map on a COMPLETED run. 7 fields: id, scheduleId, status,
    // startedAt, completedAt, errorMessage, output. Pin presence + value for every one.
    @Test
    void getScheduleRuns_completedRun_carriesAllSevenScheduleRunDtoFields() {
        HttpHeaders auth = adminAuth("history-shape-completed");
        Fixture fx = seedSchedule("history-shape-completed");

        String runId = "run-" + UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(10);
        LocalDateTime completedAt = startedAt.plusSeconds(8);
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output, error_message)
                VALUES (?, ?, 'COMPLETED', ?, ?, ?::jsonb, NULL)
                """,
                runId, fx.scheduleId, startedAt, completedAt,
                "{\"run_id\":\"agent-run-123\",\"tokens\":42}");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> row = contentOf(resp.getBody()).stream()
                .filter(m -> runId.equals(m.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "seeded run must appear in /runs response"));

        assertAll("ScheduleRunDTO field map — populated COMPLETED row (7 fields)",
                () -> assertEquals(runId, row.get("id")),
                () -> assertEquals(fx.scheduleId, row.get("scheduleId"),
                        "scheduleId is the FE bridge back to the parent schedule; never drop or rename"),
                () -> assertEquals("COMPLETED", row.get("status")),
                () -> assertNotNull(row.get("startedAt"), "startedAt must be present on COMPLETED"),
                () -> assertNotNull(row.get("completedAt"), "completedAt must be present on COMPLETED"),
                () -> assertNull(row.get("errorMessage"), "errorMessage must be null on COMPLETED"),
                () -> assertNotNull(row.get("output"), "output JsonNode must be present on COMPLETED"),
                () -> assertTrue(row.containsKey("id")
                                && row.containsKey("scheduleId")
                                && row.containsKey("status")
                                && row.containsKey("startedAt")
                                && row.containsKey("completedAt")
                                && row.containsKey("errorMessage")
                                && row.containsKey("output"),
                        "all 7 ScheduleRunDTO keys must be present in wire response"));
    }

    // Bug #26 fix — schedule_runs row written by ScheduleExecutionPoller for an AGENT-target
    // schedule populates agent_run_id. The DTO surfaces that as the new agentRunId field so
    // FE/CLI consumers can navigate from a schedule_run row directly to the agent_run it
    // produced, instead of parsing the legacy {"run_id":"..."} value out of the output JSONB.
    // Pre-fix the column did not exist; the DTO didn't carry the field at all.
    @Test
    void getScheduleRuns_agentTargetRun_carriesAgentRunIdField() {
        HttpHeaders auth = adminAuth("history-shape-agentrunid");
        Fixture fx = seedSchedule("history-shape-agentrunid");

        String runId = "run-" + UUID.randomUUID();
        String agentRunId = "agent-run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                            output, error_message, agent_run_id)
                VALUES (?, ?, 'COMPLETED', ?, ?, ?::jsonb, NULL, ?)
                """,
                runId, fx.scheduleId,
                LocalDateTime.now().minusMinutes(3),
                LocalDateTime.now().minusMinutes(3).plusSeconds(8),
                "{\"run_id\":\"" + agentRunId + "\"}", agentRunId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> row = contentOf(resp.getBody()).stream()
                .filter(m -> runId.equals(m.get("id"))).findFirst().orElseThrow();

        assertAll("agentRunId surfacing",
                () -> assertEquals(agentRunId, row.get("agentRunId"),
                        "agentRunId column must round-trip through the DTO so FE can deep-link "
                                + "to the produced agent_run row without parsing the output JSON"),
                () -> assertTrue(row.containsKey("agentRunId"),
                        "agentRunId key must be present in the wire response even when null — "
                                + "absence breaks FE consumers that destructure the field"));
    }

    // P2.4-2 — FAILED run carries errorMessage; completedAt populated; output may be null
    // (depends on whether failure produced any output). Pin the error path so FE can
    // render "run failed: <reason>" reliably.
    @Test
    void getScheduleRuns_failedRun_carriesErrorMessageAndStatusFailed() {
        HttpHeaders auth = adminAuth("history-shape-failed");
        Fixture fx = seedSchedule("history-shape-failed");

        String runId = "run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                            output, error_message)
                VALUES (?, ?, 'FAILED', ?, ?, NULL, ?)
                """,
                runId, fx.scheduleId,
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().minusMinutes(5).plusSeconds(2),
                "AgentRegistry.findById returned empty for agentId=foo");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        Map<String, Object> row = contentOf(resp.getBody()).stream()
                .filter(m -> runId.equals(m.get("id"))).findFirst().orElseThrow();

        assertAll("FAILED run carries errorMessage",
                () -> assertEquals("FAILED", row.get("status")),
                () -> assertEquals("AgentRegistry.findById returned empty for agentId=foo",
                        row.get("errorMessage"),
                        "errorMessage is the FE's "
                        + "operator-facing failure reason; must survive serialization verbatim"),
                () -> assertNull(row.get("output"), "output may be null on FAILED"));
    }

    // P2.4-3 — RUNNING (in-flight) run has completedAt=null and output=null. Pin the
    // null-serialization contract so a future change that emits "" or 0 doesn't
    // confuse the FE's "is it still running?" check.
    @Test
    void getScheduleRuns_runningRow_completedAtAndOutputAreNullNotEmpty() {
        HttpHeaders auth = adminAuth("history-shape-running");
        Fixture fx = seedSchedule("history-shape-running");

        String runId = "run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                            output, error_message)
                VALUES (?, ?, 'RUNNING', ?, NULL, NULL, NULL)
                """,
                runId, fx.scheduleId, LocalDateTime.now().minusSeconds(30));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        Map<String, Object> row = contentOf(resp.getBody()).stream()
                .filter(m -> runId.equals(m.get("id"))).findFirst().orElseThrow();

        assertAll("RUNNING row has null terminals",
                () -> assertEquals("RUNNING", row.get("status")),
                () -> assertNotNull(row.get("startedAt"), "startedAt is set immediately at dispatch"),
                () -> assertNull(row.get("completedAt"), "completedAt must be null while RUNNING — FE's RUNNING marker"),
                () -> assertNull(row.get("output"), "output must be null while RUNNING"),
                () -> assertNull(row.get("errorMessage"), "errorMessage must be null while RUNNING"));
    }

    // P2.4-4 — F4 pagination contract. Seed 50 runs (sorted by ID so the DESC ordering
    // by startedAt is deterministic per-row), then page through them. Default sort is
    // startedAt DESC (enforced at the repo derived-method level).
    @Test
    void getScheduleRuns_pagination_supportsPageAndSizeParams() {
        HttpHeaders auth = adminAuth("history-pagination");
        Fixture fx = seedSchedule("history-pagination");

        // Seed 50 runs with monotonically increasing startedAt so DESC ordering is
        // predictable: run-049 (newest) ... run-000 (oldest).
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        for (int i = 0; i < 50; i++) {
            String runId = String.format("run-history-page-%03d", i);
            LocalDateTime startedAt = base.plusSeconds(i);
            jdbc.update("""
                    INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output, error_message)
                    VALUES (?, ?, 'COMPLETED', ?, ?, ?::jsonb, NULL)
                    """,
                    runId, fx.scheduleId, startedAt, startedAt.plusSeconds(1),
                    "{\"i\":" + i + "}");
        }

        // Page 0, size 10 — newest 10 (run-049 ... run-040).
        ResponseEntity<Map<String, Object>> page0 = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs?page=0&size=10"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, page0.getStatusCode());
        List<Map<String, Object>> page0Content = contentOf(page0.getBody());
        assertAll("page 0 (newest first)",
                () -> assertEquals(10, page0Content.size(), "size=10 must cap content to 10 rows"),
                () -> assertEquals(50, totalElementsOf(page0.getBody()),
                        "totalElements must reflect the full row count, not page size"),
                () -> assertEquals("run-history-page-049", page0Content.get(0).get("id"),
                        "first row of page 0 must be the newest (DESC default)"),
                () -> assertEquals("run-history-page-040", page0Content.get(9).get("id"),
                        "last row of page 0 must be 10th newest"));

        // Page 4, size 10 — oldest 10 (run-009 ... run-000).
        ResponseEntity<Map<String, Object>> page4 = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs?page=4&size=10"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        List<Map<String, Object>> page4Content = contentOf(page4.getBody());
        assertAll("page 4 (oldest)",
                () -> assertEquals(10, page4Content.size()),
                () -> assertEquals("run-history-page-009", page4Content.get(0).get("id"),
                        "first row of page 4 must be 41st newest = run-009"),
                () -> assertEquals("run-history-page-000", page4Content.get(9).get("id"),
                        "last row of page 4 must be the oldest"));

        // Page 5 — out of range; empty content, totalElements still 50.
        ResponseEntity<Map<String, Object>> page5 = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId + "/runs?page=5&size=10"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertAll("out-of-range page",
                () -> assertTrue(contentOf(page5.getBody()).isEmpty(),
                        "page 5 (beyond 50 rows / 10 per page) must be empty"),
                () -> assertEquals(50, totalElementsOf(page5.getBody())));
    }

    // ─── helpers ───

    private record Fixture(String agentId, String scheduleId) {}

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-shape-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private Fixture seedSchedule(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "History Shape Agent " + label);
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '0 0 12 * * *', 'AGENT', ?, true, ?, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, scheduleId, "sched-" + label, "history shape " + label,
                agentId, "Run scheduled task " + label);
        return new Fixture(agentId, scheduleId);
    }
}
