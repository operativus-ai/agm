package ai.operativus.agentmanager.control.controller.observability;

import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.SafetyAggregateResponse;
import ai.operativus.agentmanager.core.model.SafetyAggregateResponse.FlaggedRun;
import ai.operativus.agentmanager.core.model.SafetyAggregateResponse.HeatmapCell;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asDouble;
import static ai.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asInstant;
import static ai.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asLong;
import static ai.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asString;

/**
 * Domain Responsibility: Read-side aggregate surface for safety-risk telemetry
 *     (observability plan T033a). Exposes a per-agent / per-day heatmap + flagged-runs
 *     top-N for the Safety Analytics tab. Per §H5 this is the tenant-wide complement
 *     to the per-agent line chart (T028).
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/aggregates")
public class SafetyAggregateController {

    private static final int MAX_WINDOW_DAYS = 365;
    private static final int FLAGGED_RUNS_LIMIT = 20;

    private final RunRepository runRepository;

    public SafetyAggregateController(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    /**
     * @summary Returns per-agent / per-day heatmap cells + top-20 flagged runs in the
     *     last {@code window} days.
     * @logic
     * - {@code window} clamps to [1, 365] (default 30).
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound
     *   by {@code TenantContextFilter}); {@code null} (super-admin) bypasses the filter.
     * - Heatmap rows are produced by {@code findSafetyHeatmap}; flagged runs by
     *   {@code findFlaggedRunsTop} with a hard cap of 20 (per spec T033a).
     */
    @GetMapping("/safety")
    public SafetyAggregateResponse getSafetyAggregates(
            @RequestParam(value = "window", defaultValue = "30") int window) {

        String orgId = AgentContextHolder.getOrgId();
        int safeWindow = Math.min(Math.max(window, 1), MAX_WINDOW_DAYS);
        Instant since = Instant.now().minus(Duration.ofDays(safeWindow));

        List<Object[]> heatmapRows = runRepository.findSafetyHeatmap(since, orgId);
        List<HeatmapCell> cells = new ArrayList<>(heatmapRows.size());
        for (Object[] row : heatmapRows) {
            cells.add(new HeatmapCell(
                    asString(row[0]),
                    asInstant(row[1]),
                    asDouble(row[2]),
                    asDouble(row[3]),
                    asLong(row[4]),
                    asLong(row[5])));
        }

        List<Object[]> flaggedRows = runRepository.findFlaggedRunsTop(since, orgId, FLAGGED_RUNS_LIMIT);
        List<FlaggedRun> flagged = new ArrayList<>(flaggedRows.size());
        for (Object[] row : flaggedRows) {
            flagged.add(new FlaggedRun(
                    asString(row[0]),
                    asString(row[1]),
                    asDouble(row[2]),
                    asInstant(row[3])));
        }

        return new SafetyAggregateResponse(cells, flagged);
    }

}
