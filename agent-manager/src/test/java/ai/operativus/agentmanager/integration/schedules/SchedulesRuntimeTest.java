package ai.operativus.agentmanager.integration.schedules;

import ai.operativus.agentmanager.control.repository.ScheduleRepository;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.SchedulerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Domain Responsibility: Runtime coverage for the Schedules surface —
 *   {@link ai.operativus.agentmanager.control.controller.SchedulesController},
 *   {@link ai.operativus.agentmanager.control.service.ScheduleService}, and the
 *   {@link ai.operativus.agentmanager.control.service.ScheduleExecutionPoller}
 *   {@code @Scheduled} loop. Pins CRUD + cron-validation, the poller's due-detection
 *   and dispatch semantics, and the three known gaps (no self-disable on target
 *   deletion, no DB-blip retry, no multi-instance advisory lock).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §12 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T037 (9 cases).
 *
 * Notes on as-shipped behaviour:
 *   - {@code schedules} has no {@code next_fire_at} column — next-fire is computed
 *     on every poll from the last-run timestamp plus the cron expression. Case 1
 *     therefore verifies persistence only, not a stored next-fire value.
 *   - {@code ScheduleExecutionPoller.triggerScheduleExecution} now persists the
 *     {@code schedule_runs} row in {@code RUNNING} state synchronously and then dispatches
 *     the actual execution onto a virtual thread; the terminal status is propagated back
 *     after the underlying {@code AgentOperations.run(...)} returns (case 2).
 *   - Spec §12 case 6 names the history endpoint {@code GET /{id}/history}; the
 *     production controller exposes it as {@code GET /{id}/runs}. Case 6 pins the
 *     as-shipped path and documents the drift.
 *   - Spec §12 case 9 says a schedule whose target agent is deleted should
 *     "self-disable or fail fast". Reality: the poller records the run as FAILED with
 *     the ResourceNotFoundException message but leaves {@code is_active = true} — the
 *     next poll would re-fire and fail again in a loop. Case 9 pins this as a GAP.
 *   - All 9 T037 cases are now enabled. Case 3 (one-shot) flips
 *     {@code schedules.is_active=false} on first dispatch via {@code schedules.one_shot}
 *     (changeset 060). Case 7 (DB-blip retry): {@code evaluateSchedules()} is
 *     {@code @Retryable(DataAccessException)}. Case 8 (advisory lock):
 *     {@code findByIsActiveTrueForUpdateSkipLocked()} provides Postgres row-level
 *     {@code FOR UPDATE SKIP LOCKED} semantics.
 *
 * Test-time scheduler control: {@code application-test.properties} pins
 * {@code agentmanager.scheduler.schedule-poll-ms=86400000} so the poller does not
 * self-fire during a case. Tests drive it deterministically via
 * {@link SchedulerTestSupport#tickSchedulePoll()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        SchedulerTestSupport.class})
public class SchedulesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private SchedulerTestSupport scheduler;

    // T037-7 spies on the locked-active-schedules query so we can simulate a transient
    // DataAccessException on the first attempt. With no stubbing this delegates to the
    // real Spring Data implementation, so other cases are unaffected.
    @MockitoSpyBean private ScheduleRepository scheduleRepoSpy;

    @BeforeEach
    void seedModelBeforeTest() {
        // agents.model_id FK — same one-row seed used by T018/T035/T036.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // §12 T037-1 — POST /api/v1/schedules persists a row with the supplied cron
    // expression and round-trips via GET /{id}. Spec phrases this as "next-fire time
    // computed correctly", but the schema stores no next_fire_at column — next-fire is
    // computed on demand by the poller. This case therefore pins persistence only; the
    // poller's due-detection is pinned separately in case 2.
    @Test
    void createSchedule_persistsRowAndRoundTripsViaGet() {
        String agentId = seedAgent("t037-1");
        HttpHeaders headers = authHeaders("t037-1-creator");

        Map<String, Object> body = Map.of(
                "name", "daily-report",
                "description", "fires once per day at midnight",
                "cronExpression", "0 0 0 * * *",
                "targetType", "AGENT",
                "targetId", agentId,
                "contextualPrompt", "Summarize yesterday's activity.",
                "isActive", true
        );

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST, new HttpEntity<>(body, headers), JSON_MAP);

        String id = (String) created.getBody().get("id");
        ResponseEntity<Map<String, Object>> fetched = rest.exchange(
                url("/api/v1/schedules/" + id), HttpMethod.GET, new HttpEntity<>(headers), JSON_MAP);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE id = ?", Integer.class, id);

        assertAll("create + get",
                () -> assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                        "POST /schedules must return 201 CREATED"),
                () -> assertNotNull(id, "create response must carry a generated id"),
                () -> assertEquals(1, count, "row must be persisted"),
                () -> assertEquals(HttpStatus.OK, fetched.getStatusCode(),
                        "GET by id must succeed immediately after create"),
                () -> assertEquals("0 0 0 * * *", fetched.getBody().get("cronExpression"),
                        "round-tripped cron must match the submitted value"),
                () -> assertEquals("AGENT", fetched.getBody().get("targetType")),
                () -> assertEquals(agentId, fetched.getBody().get("targetId")),
                () -> assertEquals(Boolean.TRUE, fetched.getBody().get("isActive"),
                        "newly-created schedule must be active by default"));
    }

    // §12 T037-2 — Poller tick on an active schedule with no prior runs fires
    // immediately (ScheduleExecutionPoller.isScheduleDue empty-history branch returns
    // true at line 81). After the GAP fixes that landed alongside this assertion the
    // poller now:
    //
    //   (a) inserts the schedule_runs row in RUNNING state synchronously at dispatch,
    //       then dispatches the actual execution onto a virtual thread; the row
    //       transitions to a terminal status (COMPLETED / FAILED) only after the
    //       agent run returns — replacing the prior dispatch-time-COMPLETED stamp.
    //
    //   (b) wires sessionId into the correct positional slot of AgentOperations.run
    //       (4th param), so AgentService.ensureSessionExists(...) is reached with
    //       a real null-or-id argument instead of receiving the orgId by accident.
    //       AGENT-target dispatch consequently lands as COMPLETED end-to-end.
    @Test
    void pollerFiresActiveSchedule_dispatchesAsync_stampsTerminalCompletedAfterAgentRun() {
        String agentId = seedAgent("t037-2");
        String scheduleId = seedScheduleViaJdbc("t037-2", agentId, "0 0 12 * * *", true);

        scheduler.tickSchedulePoll();

        // Row exists immediately — synchronous insert in triggerScheduleExecution
        // before the virtual thread is spawned.
        Integer initialRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);
        assertEquals(1, initialRowCount,
                "poller must create exactly one schedule_runs row on first tick");

        // Await the virtual thread's terminal status update. Generous timeout
        // because the synchronous run() goes through the full advisor chain.
        Awaitility.await("schedule_runs terminal status")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    String s = jdbc.queryForObject(
                            "SELECT status FROM schedule_runs WHERE schedule_id = ?",
                            String.class, scheduleId);
                    return "COMPLETED".equals(s) || "FAILED".equals(s);
                });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, output::text AS output, error_message, completed_at " +
                        "FROM schedule_runs WHERE schedule_id = ?",
                scheduleId);

        assertAll("poller dispatch + async terminal status",
                () -> assertEquals("COMPLETED", row.get("status"),
                        "AGENT-target dispatch must end COMPLETED now that the poller " +
                                "wires sessionId into the correct positional slot of AgentOperations.run"),
                () -> assertNotNull(row.get("completed_at"),
                        "completed_at must be stamped when the virtual thread finishes the run"),
                () -> assertNotNull(row.get("output"),
                        "output JSON must carry the underlying agent run id"),
                () -> assertTrue(((String) row.get("output")).contains("run_id"),
                        "output JSON must reference the AgentOperations.run RunResponse.runId"));
    }

    // §12 T037-3 — schedules.one_shot=true (changeset 060). Poller's
    // triggerScheduleExecution() flips is_active=false in the same outer transaction
    // that inserts the schedule_runs row, so the schedule fires exactly once. A
    // second poller tick must not produce a second schedule_runs row.
    @Test
    void oneShotSchedule_disablesItselfAfterFirstFire() {
        String agentId = seedAgent("t037-3");
        String scheduleId = seedOneShotScheduleViaJdbc("t037-3", agentId, "0 0 12 * * *");

        scheduler.tickSchedulePoll();

        Awaitility.await("schedule disables itself synchronously on first dispatch")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                        "SELECT is_active FROM schedules WHERE id = ?",
                        Boolean.class, scheduleId)));

        Integer firstRunCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);

        // Second tick must not re-fire the now-inactive schedule.
        scheduler.tickSchedulePoll();
        Integer secondRunCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);

        assertAll("one-shot semantics",
                () -> assertEquals(1, firstRunCount,
                        "first tick must produce exactly one schedule_runs row"),
                () -> assertEquals(1, secondRunCount,
                        "second tick must not re-fire — is_active was flipped on the first tick"));
    }

    // §12 T037-4 — ScheduleService.createSchedule validates via
    // CronExpression.isValidExpression and throws IllegalArgumentException on a
    // malformed expression. The global exception handler maps that to 400.
    @Test
    void createSchedule_invalidCron_returns400() {
        String agentId = seedAgent("t037-4");
        HttpHeaders headers = authHeaders("t037-4-creator");

        Map<String, Object> body = Map.of(
                "name", "broken",
                "cronExpression", "this is not cron",
                "targetType", "AGENT",
                "targetId", agentId,
                "isActive", true
        );

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST, new HttpEntity<>(body, headers), JSON_MAP);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE cron_expression = ?",
                Integer.class, "this is not cron");

        assertAll("invalid cron rejected at boundary",
                () -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                        "POST with malformed cron must return 400"),
                () -> assertEquals(0, rowCount,
                        "no row must be persisted for a rejected cron"));
    }

    // §12 T037-5 — ScheduleExecutionPoller.evaluateSchedules calls
    // scheduleRepository.findByIsActiveTrue(), so is_active=false rows are never
    // evaluated and never fire. Verify by seeding an inactive schedule with no prior
    // runs (which would otherwise fire immediately) and asserting zero runs.
    @Test
    void disabledSchedule_notFiredEvenWhenDue() {
        String agentId = seedAgent("t037-5");
        String scheduleId = seedScheduleViaJdbc("t037-5", agentId, "0 0 12 * * *", false);

        scheduler.tickSchedulePoll();

        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);

        assertEquals(0, runCount,
                "findByIsActiveTrue must exclude is_active=false rows from poller evaluation");
    }

    // §12 T037-6 — Spec names the endpoint GET /{id}/history; the as-shipped
    // SchedulesController exposes it as GET /{id}/runs (line 66). Pins the as-shipped
    // path — documents the spec drift.
    @Test
    void historyEndpoint_exposedAsRunsNotHistory_returnsPastFiresDescending() {
        String agentId = seedAgent("t037-6");
        String scheduleId = seedScheduleViaJdbc("t037-6", agentId, "0 0 12 * * *", true);
        HttpHeaders headers = authHeaders("t037-6-viewer");

        // Two poller ticks would only produce one run (the second is blocked by
        // isScheduleDue — last-run + cron.next is in the future). Seed two historical
        // runs directly so the ordering assertion is non-trivial.
        seedScheduleRunViaJdbc(scheduleId, "run-t037-6-older", java.time.LocalDateTime.now().minusHours(2));
        seedScheduleRunViaJdbc(scheduleId, "run-t037-6-newer", java.time.LocalDateTime.now().minusMinutes(10));

        ResponseEntity<String> historyAttempt = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/history"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // F4 — /runs is paginated; extract `.content` from the Page envelope.
        ResponseEntity<Map<String, Object>> runsReal = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(headers), JSON_MAP);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) runsReal.getBody().get("content");

        assertAll("history endpoint drift + ordering",
                () -> assertTrue(historyAttempt.getStatusCode().is4xxClientError(),
                        "/history path documented by spec does not exist on this controller"),
                () -> assertEquals(HttpStatus.OK, runsReal.getStatusCode(),
                        "/runs is the actual as-shipped path"),
                () -> assertEquals(2, runs.size(),
                        "both historical runs must surface"),
                () -> assertEquals("run-t037-6-newer", runs.get(0).get("id"),
                        "service orders by started_at DESC — most recent first"),
                () -> assertEquals("run-t037-6-older", runs.get(1).get("id")));
    }

    // §12 T037-7 — evaluateSchedules() is annotated @Retryable(DataAccessException) with
    // 3 attempts and 100ms→200ms backoff. We force a transient failure on the FIRST
    // invocation of findByIsActiveTrueForUpdateSkipLocked() and let the second attempt
    // succeed via doCallRealMethod(). The retry must:
    //   (a) re-invoke evaluateSchedules() once after the throw, and
    //   (b) produce exactly one schedule_runs row (no double fire, because the first
    //       attempt's @Transactional rolled back before any dispatch happened).
    @Test
    void pollerRetriesOnTransientDbFailure_noDoubleFire() {
        String agentId = seedAgent("t037-7");
        String scheduleId = seedScheduleViaJdbc("t037-7", agentId, "0 0 12 * * *", true);

        // Mockito's .doCallRealMethod() cannot be chained on an abstract Spring Data
        // repository method — the underlying interface method has no real Java body for
        // Mockito to invoke. Pre-fetch the live list through the spy (forwards to the
        // Spring Data proxy by default) and stash it for the retry's second attempt.
        java.util.List<ai.operativus.agentmanager.core.entity.Schedule> liveSchedules =
                new java.util.ArrayList<>(scheduleRepoSpy.findByIsActiveTrueForUpdateSkipLocked());

        doThrow(new TransientDataAccessResourceException("simulated DB blip"))
                .doReturn(liveSchedules)
                .when(scheduleRepoSpy).findByIsActiveTrueForUpdateSkipLocked();

        scheduler.tickSchedulePoll();

        Awaitility.await("schedule_runs row appears after retry succeeds")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Integer c = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                            Integer.class, scheduleId);
                    return c != null && c == 1;
                });

        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);

        assertAll("retry semantics",
                () -> assertEquals(1, runCount,
                        "@Retryable must produce exactly one schedule_runs row — the failed " +
                        "first attempt's @Transactional rolled back before dispatch"),
                () -> verify(scheduleRepoSpy, atLeast(2))
                        .findByIsActiveTrueForUpdateSkipLocked());
    }

    // §12 T037-8 — Multi-instance safety. evaluateSchedules() is @Transactional and uses
    // findByIsActiveTrueForUpdateSkipLocked() (SELECT … FOR UPDATE SKIP LOCKED). Four
    // threads simulate concurrent pollers; only the first to acquire the row lock gets a
    // non-empty list and dispatches the schedule. The remaining three threads skip the
    // locked row and do nothing. Exactly one schedule_runs row must exist after all
    // threads complete.
    @Test
    void multiInstance_advisoryLockPreventsDoubleFire() throws InterruptedException {
        String agentId = seedAgent("t037-8");
        String scheduleId = seedScheduleViaJdbc("t037-8", agentId, "0 0 12 * * *", true);

        int threads = 4;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go    = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done  = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    scheduler.tickSchedulePoll();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();
        done.await(30, java.util.concurrent.TimeUnit.SECONDS);
        exec.shutdown();

        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);
        assertEquals(1, runCount,
                "FOR UPDATE SKIP LOCKED must prevent concurrent evaluateSchedules() calls " +
                "from double-firing the same schedule");
    }

    // §12 T037-9 — Schedule whose target agent is missing self-disables on first fire:
    // the poller's catch(ResourceNotFoundException) block flips schedules.is_active=false
    // before returning, preventing the re-fire storm. Run row is still FAILED with a
    // descriptive error_message.
    @Test
    void scheduleOfDeletedAgent_recordsFailedRun_andSelfDisables() {
        // schedules.target_id has no FK to agents (001-schema.sql line 425), so a
        // non-existent id is accepted by the schema.
        String scheduleId = seedScheduleViaJdbc(
                "t037-9", "agent-does-not-exist-" + UUID.randomUUID(), "0 0 12 * * *", true);

        scheduler.tickSchedulePoll();

        Awaitility.await("schedule_runs FAILED terminal")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> "FAILED".equals(jdbc.queryForObject(
                        "SELECT status FROM schedule_runs WHERE schedule_id = ?",
                        String.class, scheduleId)));
        Awaitility.await("schedule is_active=false")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                        "SELECT is_active FROM schedules WHERE id = ?",
                        Boolean.class, scheduleId)));

        Map<String, Object> run = jdbc.queryForMap(
                "SELECT status, error_message FROM schedule_runs WHERE schedule_id = ?", scheduleId);

        assertAll("failed-target run + self-disable",
                () -> assertEquals("FAILED", run.get("status"),
                        "poller's catch block must flip the run to FAILED when the target agent is missing"),
                () -> assertNotNull(run.get("error_message"),
                        "error_message must surface the ResourceNotFoundException"),
                () -> assertTrue(((String) run.get("error_message")).toLowerCase().contains("agent"),
                        "error must identify the missing resource kind"));
    }

    // ─── R6 RBAC: schedule mutations require admin ─────────────────────────────

    /**
     * R6 production fix. {@link ai.operativus.agentmanager.control.controller.SchedulesController}
     *   now carries {@code @PreAuthorize("hasRole('ADMIN')")} on the four mutating endpoints
     *   (POST /, PUT /{id}, DELETE /{id}, POST /{id}/trigger). A bare {@code ROLE_USER}
     *   caller is rejected with 403 before {@link ai.operativus.agentmanager.control.service.ScheduleService}
     *   runs — verified here by asserting that the schedules table stays unchanged after
     *   each call. Reads (GET / and GET /{id}/runs / GET /batches) stay open and aren't
     *   exercised here.
     */
    @Test
    void scheduleMutationsRequireAdmin_403ForRoleUser_R6ProductionFix() {
        HttpHeaders userOnly = authHeadersRoleUserOnly("sched-rbac-user");

        long before = jdbc.queryForObject("SELECT COUNT(*) FROM schedules", Long.class);

        Map<String, Object> create = new HashMap<>();
        create.put("name", "R6 guard probe");
        create.put("cron", "0 0 * * * ?");
        create.put("targetId", "agent-non-existent");
        create.put("active", true);

        ResponseEntity<Map<String, Object>> post = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST,
                new HttpEntity<>(create, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, post.getStatusCode(),
                "ROLE_USER caller must be rejected by SchedulesController.createSchedule @PreAuthorize");

        ResponseEntity<Map<String, Object>> put = rest.exchange(
                url("/api/v1/schedules/non-existent-id"), HttpMethod.PUT,
                new HttpEntity<>(create, userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, put.getStatusCode(),
                "ROLE_USER caller must be rejected by SchedulesController.updateSchedule @PreAuthorize");

        ResponseEntity<Void> delete = rest.exchange(
                url("/api/v1/schedules/non-existent-id"), HttpMethod.DELETE,
                new HttpEntity<>(userOnly), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, delete.getStatusCode(),
                "ROLE_USER caller must be rejected by SchedulesController.deleteSchedule @PreAuthorize");

        ResponseEntity<Map<String, Object>> trigger = rest.exchange(
                url("/api/v1/schedules/non-existent-id/trigger"), HttpMethod.POST,
                new HttpEntity<>(userOnly), JSON_MAP);
        assertEquals(HttpStatus.FORBIDDEN, trigger.getStatusCode(),
                "ROLE_USER caller must be rejected by SchedulesController.triggerSchedule @PreAuthorize");

        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM schedules", Long.class);
        assertEquals(before, after,
                "rejected mutations must NOT touch the schedules table — rejection must precede ScheduleService");
    }

    // ─── helpers ───

    private String seedAgent(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        // Stamp DEFAULT_SYSTEM_ORG to match seedScheduleViaJdbc and the
        // post-tenant-isolation fallback in DatabaseAgentRegistry.resolveOrgId.
        // Without it, agents.org_id is NULL while schedules.org_id is DEFAULT_SYSTEM_ORG,
        // and findByIdAndOrgId('…','DEFAULT_SYSTEM_ORG') misses → ResourceNotFoundException
        // → schedule self-disables → poller-dispatch test asserts COMPLETED but sees FAILED.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "Schedule Test Agent " + label);
        return agentId;
    }

    /**
     * Inserts a schedules row directly so we can (a) set is_active=false without
     * round-tripping through the service's default-true branch, (b) set an arbitrary
     * schedule id for deterministic assertions.
     */
    private String seedScheduleViaJdbc(String label, String targetId, String cron, boolean active) {
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        // Stamp DEFAULT_SYSTEM_ORG so the seeded schedule is visible to test callers that
        // register without an explicit users.org_id override (the post-tenant-isolation
        // flow falls back to DEFAULT_SYSTEM_ORG when the JWT has no orgId claim).
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AGENT', ?, ?, ?, ?, now(), now())
                """,
                scheduleId, "sched-" + label, "Schedules runtime test " + label,
                cron, targetId, active, "Run scheduled task " + label, "DEFAULT_SYSTEM_ORG");
        return scheduleId;
    }

    /**
     * One-shot variant: stamps schedules.one_shot=true so the poller will flip
     * is_active=false on first dispatch. Mirrors seedScheduleViaJdbc otherwise.
     */
    private String seedOneShotScheduleViaJdbc(String label, String targetId, String cron) {
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, one_shot, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AGENT', ?, true, true, ?, ?, now(), now())
                """,
                scheduleId, "sched-" + label, "Schedules runtime test " + label,
                cron, targetId, "Run scheduled task " + label, "DEFAULT_SYSTEM_ORG");
        return scheduleId;
    }

    private void seedScheduleRunViaJdbc(String scheduleId, String runId, java.time.LocalDateTime startedAt) {
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output)
                VALUES (?, ?, 'COMPLETED', ?, ?, ?::jsonb)
                """,
                runId, scheduleId, startedAt, startedAt.plusSeconds(2), "{\"run_id\":\"synthetic\"}");
    }

    private HttpHeaders authHeaders(String username) {
        // Admin role required by SchedulesController @PreAuthorize gates on the mutating
        // endpoints (POST, PUT /{id}, DELETE /{id}, POST /{id}/trigger). Tests in this
        // class exercise those endpoints to set up fixtures, so the default helper grants
        // admin. Use {@link #authHeadersRoleUserOnly(String)} for negative RBAC assertions.
        return authenticateAs(username, username + "@test.local", "pw-t037-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders authHeadersRoleUserOnly(String username) {
        return authenticateAs(username, username + "@test.local", "pw-t037-1234", List.of("ROLE_USER"));
    }
}
