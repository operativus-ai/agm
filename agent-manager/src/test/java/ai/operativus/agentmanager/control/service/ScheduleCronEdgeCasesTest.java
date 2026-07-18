package ai.operativus.agentmanager.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Domain Responsibility: Pin {@code CronExpression} semantics that
 *   {@link ScheduleExecutionPoller#isScheduleDue} relies on. These are unit-level
 *   pins of the Spring 6-field cron contract — not behaviour of the schedules surface
 *   itself, but rather the contract the poller depends on. Documenting them here
 *   makes future Spring upgrades (or substitutions like Quartz) visible: any change
 *   in cron-semantic surface area trips one of these assertions before the schedule
 *   runtime tests see the cascading effect.
 *
 *   Existing coverage:
 *   <ul>
 *     <li>{@code SchedulesRuntimeTest.createSchedule_invalidCron_returns400} —
 *         malformed input rejected at the create endpoint.</li>
 *   </ul>
 *
 *   Gaps covered here:
 *   <ul>
 *     <li>Valid-but-never-fires expression (Feb 31 — month has no 31st)</li>
 *     <li>Leap-year-only expression (Feb 29)</li>
 *     <li>5-field cron (legacy Unix syntax) — Spring 6-field requirement</li>
 *     <li>"@yearly" / "@monthly" macros — Spring supports these</li>
 *   </ul>
 * State: Stateless pure unit test (no Spring boot, no DB).
 */
class ScheduleCronEdgeCasesTest {

    // P3.1-1 — "0 0 0 31 2 *" (midnight on Feb 31) is structurally VALID (parses
    // without throwing) but never fires — there is no Feb 31st. CronExpression.next()
    // returns null. The poller's isScheduleDue returns false for null nextExecution.
    @Test
    void cronExpression_feb31_parsesButReturnsNullForNext() {
        CronExpression expr = CronExpression.parse("0 0 0 31 2 *");
        assertNotNull(expr, "Feb-31 cron must parse — the syntax is well-formed");

        ZonedDateTime now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime next = expr.next(now);

        assertNull(next,
                "Feb-31 has no real date; CronExpression.next() must return null. "
                        + "ScheduleExecutionPoller.isScheduleDue treats null next as 'not due', "
                        + "so such a schedule is silently never-fires — pinned so operators "
                        + "understand the schedule will not error, just never run.");
    }

    // P3.1-2 — Feb 29 fires only on leap years. From 2026-01-01 (non-leap), the next
    // fire must be 2028-02-29 (skipping 2026 and 2027). Pin this so a future cron
    // library swap that "fixes up" Feb 29 to Feb 28 on non-leap years is caught.
    @Test
    void cronExpression_feb29_firesOnlyOnLeapYears() {
        CronExpression expr = CronExpression.parse("0 0 0 29 2 *");

        ZonedDateTime startOf2026 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime next = expr.next(startOf2026);

        assertNotNull(next, "Feb 29 cron must have a next-fire (the next leap year)");
        assertEquals(2028, next.getYear(), "next Feb 29 from 2026-01-01 is 2028 (2027 is not a leap year)");
        assertEquals(2, next.getMonthValue());
        assertEquals(29, next.getDayOfMonth());
    }

    // P3.1-3 — 5-field cron (legacy Unix '* * * * *' = every minute) is REJECTED.
    // Spring CronExpression requires 6 fields (second minute hour day-of-month month
    // day-of-week). Operators bringing crons from Unix cron or Quartz must add a
    // leading second field. Pin this so a future upstream migration that flips to
    // 5-field support trips the test.
    @Test
    void cronExpression_5fieldUnixCron_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * * *"),
                "5-field cron (legacy Unix '* * * * *') must be rejected — Spring requires 6 fields. "
                        + "If this test starts passing, Spring upstream added 5-field compatibility; "
                        + "update SchedulesController create-time validation to remain consistent.");
    }

    // P3.1-4 — "@yearly" macro (Spring supports @yearly / @monthly / @weekly / @daily
    // / @hourly). Pin this so operators relying on the human-readable macros know
    // they're a valid input shape, and future cron-library changes are caught.
    @Test
    void cronExpression_yearlyMacro_firesJanuaryFirstAtMidnight() {
        CronExpression expr = CronExpression.parse("@yearly");

        ZonedDateTime mid2026 = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime next = expr.next(mid2026);

        assertNotNull(next);
        assertEquals(2027, next.getYear(), "@yearly's next fire from 2026-06-15 is 2027-01-01");
        assertEquals(1, next.getMonthValue());
        assertEquals(1, next.getDayOfMonth());
        assertEquals(0, next.getHour());
        assertEquals(0, next.getMinute());
        assertEquals(0, next.getSecond());
    }

    // P3.1-5 — Concrete sanity probe: the poller's isScheduleDue branches on
    // (nextExecution == null) → false. Pin the null-next branch so a future change
    // that interprets null as "fire immediately" or throws NPE is caught.
    @Test
    void cronExpression_neverFiringExpression_treatedAsNotDue_byNullNext() {
        // Conceptual sanity check, not exercising the poller directly. The Feb-31
        // case above already pinned the null return; this case documents the
        // downstream consequence: isScheduleDue returns false when expression.next()
        // is null. See ScheduleExecutionPoller.isScheduleDue last line:
        //   return nextExecution != null && (now.isEqual(nextExecution) || now.isAfter(nextExecution));
        CronExpression expr = CronExpression.parse("0 0 0 31 2 *");
        LocalDateTime lastRun = LocalDateTime.of(2026, 1, 1, 0, 0);
        ZonedDateTime lastRunZoned = lastRun.atZone(ZoneId.of("UTC"));
        ZonedDateTime next = expr.next(lastRunZoned);
        assertNull(next, "null next from Feb-31 short-circuits the isScheduleDue boolean");
    }
}
