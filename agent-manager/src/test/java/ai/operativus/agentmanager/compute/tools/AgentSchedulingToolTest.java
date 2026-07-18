package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.core.model.ScheduleDTO;
import ai.operativus.agentmanager.core.registry.ScheduleOperations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for {@link AgentSchedulingTool}. Asserts the
 * 3 vectors per docs/plans/agm-tools-impl.md §3:
 *   (a) success returns "SUCCESS:" + created id + cron expression
 *   (b) IllegalArgumentException returns "FAILED: Invalid CRON expression" + bad cron
 *   (c) generic Exception returns "FAILED: System error scheduling task:" + message
 *
 * State: Stateless. Mockito stub of ScheduleOperations is the independent ground truth
 * (A18) — production formats whatever the registry returns or throws.
 */
class AgentSchedulingToolTest {

    private final ScheduleOperations registry = mock(ScheduleOperations.class);
    private final AgentSchedulingTool tool = new AgentSchedulingTool(registry);

    private static ScheduleDTO created(String id, String cron) {
        return new ScheduleDTO(id, "n", "d", cron, "AGENT", "agent-1", "sess-1", "ctx", true, null, null, null, null, null, null);
    }

    // (a) success path
    @Test
    void success_returnsSuccessIdAndCron() {
        when(registry.createSchedule(ArgumentMatchers.any())).thenReturn(created("42", "0 9 * * *"));

        String result = tool.schedule_task("0 9 * * *", "ctx", "agent-1", "sess-1");

        assertTrue(result.startsWith("SUCCESS:"), "expected SUCCESS prefix, got: " + result);
        assertTrue(result.contains("42"), "expected created id, got: " + result);
        assertTrue(result.contains("0 9 * * *"), "expected cron expression, got: " + result);
    }

    // (b) invalid cron -> IllegalArgumentException -> formatted FAILED
    @Test
    void invalidCron_returnsFailedInvalidCronWithBadValue() {
        String badCron = "not a cron";
        when(registry.createSchedule(ArgumentMatchers.any())).thenThrow(new IllegalArgumentException("invalid"));

        String result = tool.schedule_task(badCron, "ctx", "agent-1", "sess-1");

        assertTrue(result.startsWith("FAILED: Invalid CRON expression"),
                "expected invalid-cron prefix, got: " + result);
        assertTrue(result.contains(badCron), "expected bad cron echoed, got: " + result);
    }

    // (c) generic exception -> system error
    @Test
    void genericException_returnsSystemError() {
        when(registry.createSchedule(ArgumentMatchers.any())).thenThrow(new RuntimeException("DB down"));

        String result = tool.schedule_task("0 9 * * *", "ctx", "agent-1", "sess-1");

        assertTrue(result.startsWith("FAILED: System error scheduling task:"),
                "expected system-error prefix, got: " + result);
        assertTrue(result.contains("DB down"), "expected exception message echoed, got: " + result);
    }
}
