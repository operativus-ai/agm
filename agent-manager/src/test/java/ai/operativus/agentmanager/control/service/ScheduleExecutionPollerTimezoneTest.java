package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ScheduleRepository;
import ai.operativus.agentmanager.control.repository.ScheduleRunRepository;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import ai.operativus.agentmanager.core.entity.Schedule;
import ai.operativus.agentmanager.core.entity.ScheduleRun;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Regression-lock for the fix that makes
 *   {@link ScheduleExecutionPoller} honor {@link Schedule#getTimezone()} on cron
 *   evaluation. Pre-fix, both {@code now} and {@code lastRunStartedAt} were rebased
 *   to {@link ZoneId#systemDefault()} regardless of the schedule's stored IANA zone —
 *   silently breaking any schedule whose timezone differed from the JVM default
 *   (typically UTC in production).
 *
 * Pinning at unit-test level rather than runtime: cron evaluation is a deterministic
 * function of (cron, lastRunStartedAt, now, zone). A runtime test that spins up
 * Postgres + Spring just to call {@code isScheduleDue} adds 13s startup with no
 * incremental coverage over a 0.01s unit test with the repository mocked. The
 * accompanying runtime test {@code SchedulesTimezoneRuntimeTest} would only re-pin
 * what's already pinned here — explicitly deferred to keep the test surface tight.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleExecutionPollerTimezoneTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ScheduleRunRepository scheduleRunRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private AgentOperations agentOperations;
    @Mock private WorkflowService workflowService;

    private ScheduleExecutionPoller poller() {
        return new ScheduleExecutionPoller(scheduleRepository, scheduleRunRepository,
                sessionRepository, agentOperations, workflowService);
    }

    private static ScheduleRun runStartedAt(LocalDateTime startedAt) {
        ScheduleRun r = new ScheduleRun();
        r.setStartedAt(startedAt);
        return r;
    }

    // FIX-D-1 — UTC baseline. Schedule with timezone=UTC + daily-10-AM cron, last run at
    // 10:00 UTC yesterday. now = 10:30 UTC today → due (10 AM today UTC has passed).
    // This is the post-fix baseline that ALSO held pre-fix in any JVM running UTC.
    @Test
    void scheduleWithUtcTimezone_dailyTenAmCron_lastRunYesterdayTenAm_nowIsTenThirty_isDue() {
        Schedule schedule = newSchedule("s-utc", "0 0 10 * * *", "UTC");
        when(scheduleRunRepository.findByScheduleIdOrderByStartedAtDesc(anyString()))
                .thenReturn(List.of(runStartedAt(LocalDateTime.of(2026, 1, 15, 10, 0))));

        ZonedDateTime now = ZonedDateTime.of(2026, 1, 16, 10, 30, 0, 0, ZoneId.of("UTC"));

        assertTrue(poller().isScheduleDue(schedule, now),
                "UTC schedule with last-run 10:00 yesterday must fire when now=10:30 today UTC");
    }

    // FIX-D-2 — Chicago zone, the load-bearing pin. Last run at 10:00 CT yesterday.
    // now = 14:00 UTC today = 08:00 / 09:00 CT today (depending on DST). cron is
    // daily-10-AM. In CT wall-clock, today's 10 AM CT has NOT yet arrived → schedule
    // must NOT be due. Pre-fix this returned TRUE because the poller rebased
    // lastRunStartedAt to systemDefault (e.g. UTC) — 10:00 UTC yesterday + next at
    // 10:00 UTC today, and now 14:00 UTC > 10:00 UTC, so the schedule fired in the
    // wrong wall-clock — 4 hours early in CT.
    @Test
    void scheduleWithChicagoTimezone_dailyTenAmCron_lastRunYesterdayLocal_nowBeforeTenAmCt_isNotDue() {
        Schedule schedule = newSchedule("s-chi", "0 0 10 * * *", "America/Chicago");
        when(scheduleRunRepository.findByScheduleIdOrderByStartedAtDesc(anyString()))
                .thenReturn(List.of(runStartedAt(LocalDateTime.of(2026, 1, 15, 10, 0))));

        // 14:00 UTC = 08:00 CST (winter, no DST). Today's 10 AM CT has not arrived.
        ZonedDateTime now = ZonedDateTime.of(2026, 1, 16, 14, 0, 0, 0, ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.of("America/Chicago"));

        assertFalse(poller().isScheduleDue(schedule, now),
                "Chicago-timezone schedule must NOT fire before today's 10 AM CT — pre-fix bug "
                        + "rebased everything to UTC and incorrectly returned true at 14:00 UTC");
    }

    // FIX-D-3 — Same Chicago schedule, but now AFTER 10 AM CT today. Must be due.
    // Establishes the post-fix-positive case (along with case 1 it brackets the
    // critical wall-clock window).
    @Test
    void scheduleWithChicagoTimezone_nowAfterTenAmCt_isDue() {
        Schedule schedule = newSchedule("s-chi", "0 0 10 * * *", "America/Chicago");
        when(scheduleRunRepository.findByScheduleIdOrderByStartedAtDesc(anyString()))
                .thenReturn(List.of(runStartedAt(LocalDateTime.of(2026, 1, 15, 10, 0))));

        // 17:00 UTC = 11:00 CST. Today's 10 AM CT has passed.
        ZonedDateTime now = ZonedDateTime.of(2026, 1, 16, 17, 0, 0, 0, ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.of("America/Chicago"));

        assertTrue(poller().isScheduleDue(schedule, now),
                "Chicago schedule must fire once today's 10 AM CT has passed");
    }

    // FIX-D-4 — Null timezone falls back to system default. Legacy rows from before the
    // timezone column existed have NULL; the poller must continue to evaluate them
    // (no NPE, no skip — system default zone applied like the pre-fix code).
    @Test
    void scheduleWithNullTimezone_fallsBackToSystemDefault_doesNotThrow() {
        Schedule schedule = newSchedule("s-legacy", "0 0 10 * * *", null);

        ZoneId fallback = ScheduleExecutionPoller.resolveScheduleZone(schedule);
        assertEquals(ZoneId.systemDefault(), fallback,
                "null timezone must resolve to ZoneId.systemDefault() — legacy rows continue to evaluate");
    }

    // FIX-D-5 — Invalid timezone string falls back to system default, does not crash
    // the poll tick. Operators can recover by PUTing a valid value; meanwhile the
    // schedule keeps evaluating against the JVM default.
    @Test
    void scheduleWithInvalidTimezoneString_fallsBackToSystemDefault_doesNotThrow() {
        Schedule schedule = newSchedule("s-bogus", "0 0 10 * * *", "Not/A/Real/Zone");

        ZoneId fallback = ScheduleExecutionPoller.resolveScheduleZone(schedule);
        assertEquals(ZoneId.systemDefault(), fallback,
                "invalid timezone string must defensively fall back, not crash the poll tick");
    }

    // ─── helpers ───

    private static Schedule newSchedule(String id, String cron, String timezone) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setCronExpression(cron);
        s.setTimezone(timezone);
        s.setActive(true);
        return s;
    }
}
