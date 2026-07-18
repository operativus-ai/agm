package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import com.operativus.agentmanager.core.model.OrchestrationAggregateResponse;
import com.operativus.agentmanager.core.model.OrchestrationAggregateResponse.StrategyCount;
import com.operativus.agentmanager.core.model.OrchestrationAggregateResponse.TimeBucket;
import com.operativus.agentmanager.core.model.OrchestrationDecisionDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

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
 * Domain Responsibility: Read-side aggregate surface for orchestration decisions
 *     (observability plan T031a). Exposes the strategy-distribution rollup and a
 *     time-bucketed series in a single response so the Orchestration Analytics tab
 *     renders without N round-trips.
 *
 * <p>Per §4.10 the aggregate read paths are typically {@code @Cacheable} with a 60s TTL;
 * this controller is intentionally <b>not</b> cached yet — caching infrastructure can be
 * layered in later when a {@code CacheManager} bean is wired. The underlying SQL is
 * cheap enough to leave uncached for the dashboard's polling cadence.
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/aggregates")
public class OrchestrationAggregateController {

    private static final int MAX_WINDOW_DAYS = 365;
    private static final Set<String> ALLOWED_GRANULARITIES = Set.of("hour", "day", "week", "month");

    private final OrchestrationDecisionRepository repository;

    public OrchestrationAggregateController(OrchestrationDecisionRepository repository) {
        this.repository = repository;
    }

    /**
     * @summary Returns strategy distribution + time-series for the last {@code window} days.
     * @logic
     * - {@code window} clamps to [1, 365] (default 30).
     * - {@code granularity} accepts {@code hour|day|week|month} (default {@code day});
     *   anything else falls back to {@code day} to keep the SQL safe.
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound);
     *   {@code null} (super-admin) bypasses the filter.
     * - {@code distribution}: rows from {@code countDecisionsByStrategy}, mapped to
     *   {@link StrategyCount}.
     * - {@code overTime}: rows from {@code countDecisionsByStrategyOverTime}, regrouped
     *   per bucket so each {@link TimeBucket} carries a {@code Map<strategy, count>}.
     */
    @GetMapping("/orchestration")
    public OrchestrationAggregateResponse getOrchestrationAggregates(
            @RequestParam(value = "window", defaultValue = "30") int window,
            @RequestParam(value = "granularity", defaultValue = "DAY") String granularity) {

        String orgId = AgentContextHolder.getOrgId();
        int safeWindow = Math.min(Math.max(window, 1), MAX_WINDOW_DAYS);
        Instant since = Instant.now().minus(Duration.ofDays(safeWindow));
        String safeGranularity = sanitizeGranularity(granularity);

        List<Object[]> distRows = repository.countDecisionsByStrategy(since, orgId);
        List<StrategyCount> distribution = new ArrayList<>(distRows.size());
        for (Object[] row : distRows) {
            distribution.add(new StrategyCount(asString(row[0]), asLong(row[1])));
        }

        List<Object[]> overTimeRows = repository.countDecisionsByStrategyOverTime(since, orgId, safeGranularity);
        Map<Instant, Map<String, Long>> grouped = new LinkedHashMap<>();
        for (Object[] row : overTimeRows) {
            Instant bucket = asInstant(row[0]);
            String strategy = asString(row[1]);
            long count = asLong(row[2]);
            grouped.computeIfAbsent(bucket, b -> new HashMap<>()).put(strategy, count);
        }
        List<TimeBucket> overTime = new ArrayList<>(grouped.size());
        for (Map.Entry<Instant, Map<String, Long>> entry : grouped.entrySet()) {
            overTime.add(new TimeBucket(entry.getKey(), entry.getValue()));
        }

        return new OrchestrationAggregateResponse(distribution, overTime);
    }

    /**
     * @summary Paginated drill-down list of decisions for a single strategy
     *     (observability plan T031b). Backs the per-strategy decision table on
     *     the Orchestration Analytics tab.
     * @logic
     * - {@code strategy} is required (e.g. {@code ROUTER}, {@code SWARM});
     *   matched verbatim — case-sensitive, no normalization.
     * - {@code page} default 0; {@code size} clamps to [1, 100] (default 20).
     * - Tenant filter via {@code X-Org-Id}; null bypasses (matches other
     *   observability paths).
     * - Sort order is fixed: {@code created_at DESC} (newest first).
     */
    @GetMapping("/orchestration-decisions")
    public Page<OrchestrationDecisionDTO> getOrchestrationDecisions(
            @RequestParam(value = "strategy") String strategy,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        String orgId = AgentContextHolder.getOrgId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return repository.findRecentByStrategy(strategy, orgId, pageable).map(this::toDto);
    }

    private OrchestrationDecisionDTO toDto(OrchestrationDecisionEntity e) {
        return new OrchestrationDecisionDTO(
                e.getId(),
                e.getRunId(),
                e.getOrgId(),
                e.getStrategy(),
                e.getDecisionType(),
                e.getSelectedAgentId(),
                e.getRationale(),
                e.getDecisionPayload(),
                e.getCreatedAt());
    }

    private static String sanitizeGranularity(String raw) {
        if (raw == null) return "day";
        String lower = raw.toLowerCase(Locale.ROOT);
        return ALLOWED_GRANULARITIES.contains(lower) ? lower : "day";
    }

}
