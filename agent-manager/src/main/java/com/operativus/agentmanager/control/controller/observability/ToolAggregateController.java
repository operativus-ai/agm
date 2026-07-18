package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.AgentRunEventRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.ToolUsageAggregateResponse;
import com.operativus.agentmanager.core.model.ToolUsageAggregateResponse.TimeBucket;
import com.operativus.agentmanager.core.model.ToolUsageAggregateResponse.ToolStat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asDouble;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asInstant;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asLong;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Domain Responsibility: Read-side aggregate surface for tool-invocation telemetry
 *     (observability plan T035b). Drives the Analytics / Tools tab on
 *     {@code ObservabilityPage}.
 *
 * <p>Reads {@code TOOL_COMPLETED} events from {@code agent_run_events}, extracting
 * {@code toolName} / {@code durationMs} / {@code status} from the JSONB payload.
 * Per the plan T035a denormalization (a dedicated {@code tool_name} column with a
 * partial index) is deferred — current scale doesn't justify it; if it becomes hot
 * we can add it without changing this controller's wire shape.
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/aggregates")
public class ToolAggregateController {

    private static final int MAX_WINDOW_DAYS = 365;
    private static final Set<String> ALLOWED_GRANULARITIES = Set.of("hour", "day", "week", "month");

    private final AgentRunEventRepository eventRepository;

    public ToolAggregateController(AgentRunEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * @summary Returns per-tool rollup + tool-usage time-series for the last
     *     {@code window} days.
     * @logic
     * - {@code window} clamps to [1, 365] (default 30).
     * - {@code granularity} accepts {@code hour|day|week|month} (default {@code day});
     *   anything else falls back to {@code day} (defends against SQL injection via
     *   {@code date_trunc}).
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound);
     *   {@code null} (super-admin) bypasses the filter.
     * - {@code tools[]}: per-tool rollup ordered by total_count DESC.
     * - {@code overTime[]}: regrouped per bucket so each {@link TimeBucket} carries
     *   {@code Map<toolName, count>}.
     */
    @GetMapping("/tools")
    public ToolUsageAggregateResponse getToolAggregates(
            @RequestParam(value = "window", defaultValue = "30") int window,
            @RequestParam(value = "granularity", defaultValue = "DAY") String granularity) {

        String orgId = AgentContextHolder.getOrgId();
        int safeWindow = Math.min(Math.max(window, 1), MAX_WINDOW_DAYS);
        Instant since = Instant.now().minus(Duration.ofDays(safeWindow));
        String safeGranularity = sanitizeGranularity(granularity);

        List<Object[]> statRows = eventRepository.aggregateToolUsage(since, orgId);
        List<ToolStat> tools = new ArrayList<>(statRows.size());
        for (Object[] row : statRows) {
            tools.add(new ToolStat(
                    asString(row[0]),
                    asLong(row[1]),
                    asLong(row[2]),
                    asDouble(row[3])));
        }

        List<Object[]> overTimeRows = eventRepository.aggregateToolUsageOverTime(since, orgId, safeGranularity);
        Map<Instant, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (Object[] row : overTimeRows) {
            Instant bucket = asInstant(row[0]);
            String toolName = asString(row[1]);
            long count = asLong(row[2]);
            grouped.computeIfAbsent(bucket, b -> new HashMap<>()).put(toolName, count);
        }
        List<TimeBucket> overTime = new ArrayList<>(grouped.size());
        for (Map.Entry<Instant, Map<String, Long>> entry : grouped.entrySet()) {
            overTime.add(new TimeBucket(entry.getKey(), entry.getValue()));
        }

        return new ToolUsageAggregateResponse(tools, overTime);
    }

    private static String sanitizeGranularity(String raw) {
        if (raw == null) return "day";
        String lower = raw.toLowerCase(Locale.ROOT);
        return ALLOWED_GRANULARITIES.contains(lower) ? lower : "day";
    }

}
