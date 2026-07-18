package com.operativus.agentmanager.control.finops.service;

import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CacheImpactPoint;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CostAllocationEntry;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.HistoricalTrendPoint;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ModelCostSlice;
import com.operativus.agentmanager.control.repository.RunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain Responsibility: OLAP-style analytics aggregation for FinOps historical reporting.
 * Separates historical read-aggregation concerns from the live OLTP sliding windows in
 * {@code BurnRateMonitorService}, following the Single Responsibility Architectural Check.
 *
 * All queries use Postgres-level {@code GROUP BY DATE(created_at)} aggregations via JPA
 * native queries to prevent silent N+1 patterns and JVM heap exhaustion on large run tables.
 *
 * Architecture:
 * - Constructor Injection.
 * - Blocking I/O on Virtual Threads — no reactive wrappers.
 * - Read-only transactions to ensure no accidental mutations.
 * - Estimation: Since {@code agent_runs} does not store per-run USD cost, an average cost
 *   factor is applied to run counts to derive estimated daily USD trends. This provides
 *   accurate relative trend shapes backed by real production data.
 *
 * State: Stateless
 */
@Service
public class FinOpsAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(FinOpsAnalyticsService.class);

    /**
     * Average estimated USD cost per agent run. Used to translate run counts into
     * estimated daily USD expenditure for the historical trend chart.
     * Configurable via {@code agentmanager.finops.average-cost-per-run-usd}.
     */
    private final double averageCostPerRunUsd;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RunRepository runRepository;
    private final MeterRegistry meterRegistry;

    public FinOpsAnalyticsService(RunRepository runRepository, MeterRegistry meterRegistry,
                                  @org.springframework.beans.factory.annotation.Value("${agentmanager.finops.average-cost-per-run-usd:0.08}") double averageCostPerRunUsd) {
        this.runRepository = runRepository;
        this.meterRegistry = meterRegistry;
        this.averageCostPerRunUsd = averageCostPerRunUsd;
    }

    /**
     * @summary Aggregates daily run activity for the trailing N-day historical burn rate chart.
     * @logic
     * 1. Computes the lookback window start date (midnight N days ago).
     * 2. Delegates to {@code RunRepository#findDailyRunCounts} for a Postgres-level GROUP BY DATE.
     * 3. Fills in zero-count days for any dates missing from the result (sparse data periods).
     * 4. Applies {@code averageCostPerRunUsd} to derive estimated USD per day.
     * 5. Returns an ascending list of {@code HistoricalTrendPoint} covering the full window.
     *
     * If no data exists (empty DB), returns an array of zero-valued points for each day.
     * The frontend renders this as a flat zero-state chart rather than breaking.
     *
     * @param days Number of trailing days to aggregate (7 for weekly view, 30 for monthly).
     * @return     List of daily trend points, one per day, oldest to newest.
     */
    @Transactional(readOnly = true)
    public List<HistoricalTrendPoint> getHistoricalTrends(int days, String orgId) {
        LocalDateTime since = LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();

        log.debug("Fetching historical run trends for trailing {} days since {}", days, since);

        List<Object[]> rawRows = runRepository.findDailyRunCounts(since, orgId);

        // Build a date-indexed map from the query results
        java.util.Map<String, Long> countByDate = new java.util.LinkedHashMap<>();
        for (Object[] row : rawRows) {
            // row[0]: date string or java.sql.Date, row[1]: count
            String dateKey = row[0] != null ? row[0].toString().substring(0, 10) : null;
            Long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            if (dateKey != null) countByDate.put(dateKey, count);
        }

        // Fill the full window with zeros for missing dates (ensures frontend always gets N points)
        List<HistoricalTrendPoint> result = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            String dateKey = day.format(DATE_FORMATTER);
            long runCount = countByDate.getOrDefault(dateKey, 0L);
            double estimatedUsd = runCount * averageCostPerRunUsd;
            result.add(new HistoricalTrendPoint(dateKey, runCount, estimatedUsd));
        }

        log.debug("Historical trends aggregated: {} data points returned for {} day window.", result.size(), days);
        return result;
    }

    /**
     * @summary Aggregates cost allocation breakdown by agent and org unit for the donut chart.
     * @logic
     * 1. Queries run counts grouped by agentId from the trailing 7-day window.
     * 2. Queries run counts grouped by orgId from the trailing 7-day window.
     * 3. Computes allocation percentage for each slice relative to the total run count.
     * 4. Returns a combined list of {@code CostAllocationEntry} with dimension labels.
     *
     * Returns an empty list when no data exists; the frontend renders a zero-state chart.
     *
     * @param days Trailing day window for allocation aggregation.
     * @return     List of allocation entries across agent and org dimensions.
     */
    @Transactional(readOnly = true)
    public List<CostAllocationEntry> getCostAllocations(int days, String orgId) {
        LocalDateTime since = LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();

        log.debug("Fetching cost allocation breakdown for trailing {} days since {}", days, since);

        List<Object[]> byAgent = runRepository.findRunCountByAgent(since, orgId);
        List<Object[]> byOrg   = runRepository.findRunCountByOrg(since, orgId);

        long totalRuns = byAgent.stream()
            .mapToLong(row -> row[1] != null ? ((Number) row[1]).longValue() : 0L)
            .sum();

        if (totalRuns == 0) {
            log.debug("No runs found for cost allocation window — returning empty list.");
            return List.of();
        }

        List<CostAllocationEntry> entries = new ArrayList<>();

        for (Object[] row : byAgent) {
            String agentId = row[0] != null ? row[0].toString() : "unknown";
            long count     = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            double pct     = totalRuns > 0 ? (count * 100.0) / totalRuns : 0.0;
            entries.add(new CostAllocationEntry("agent", agentId, count, pct));
        }

        long totalOrgRuns = byOrg.stream()
            .mapToLong(row -> row[1] != null ? ((Number) row[1]).longValue() : 0L)
            .sum();

        for (Object[] row : byOrg) {
            String rowOrgId = row[0] != null ? row[0].toString() : "unattributed";
            long count   = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            double pct   = totalOrgRuns > 0 ? (count * 100.0) / totalOrgRuns : 0.0;
            entries.add(new CostAllocationEntry("org", rowOrgId, count, pct));
        }

        log.debug("Cost allocation computed: {} entries (agent={}, org={})",
            entries.size(), byAgent.size(), byOrg.size());
        return entries;
    }

    /**
     * @summary Aggregates cost allocation breakdown by LLM vendor model for the model cost slice chart.
     * @logic
     * 1. Queries run counts grouped by model_id via a JOIN between agent_runs and agents.
     * 2. Computes allocation percentage and estimated USD per model slice.
     * 3. Returns a list of {@code ModelCostSlice} entries for recharts PieChart rendering.
     *
     * @param days Trailing day window for allocation aggregation.
     * @return     List of model cost slices, ordered by run count descending.
     */
    @Transactional(readOnly = true)
    public List<ModelCostSlice> getCostAllocationsByModel(int days, String orgId) {
        LocalDateTime since = LocalDateTime.now().minusDays(days).toLocalDate().atStartOfDay();

        log.debug("Fetching model cost allocations for trailing {} days since {}", days, since);

        List<Object[]> byModel = runRepository.findRunCountByModel(since, orgId);

        long totalRuns = byModel.stream()
            .mapToLong(row -> row[1] != null ? ((Number) row[1]).longValue() : 0L)
            .sum();

        if (totalRuns == 0) {
            log.debug("No runs found for model cost allocation — returning empty list.");
            return List.of();
        }

        List<ModelCostSlice> slices = new ArrayList<>();
        for (Object[] row : byModel) {
            String modelId = row[0] != null ? row[0].toString() : "unknown";
            long count     = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            double pct     = (count * 100.0) / totalRuns;
            double estimatedUsd = count * averageCostPerRunUsd;
            slices.add(new ModelCostSlice(modelId, count, pct, estimatedUsd));
        }

        log.debug("Model cost allocation computed: {} model slices.", slices.size());
        return slices;
    }

    /**
     * @summary Returns a trailing N-day cache impact time-series from Micrometer counters.
     * @logic
     * Since Micrometer counters are process-lifetime accumulators (not bucketed by day),
     * this method returns a single-point snapshot of the current cache hit vs total prompt
     * ratio. The frontend renders this as a 7-day chart with the current snapshot as
     * the latest data point, providing a foundation for future per-day bucketing.
     *
     * Reads:
     * - {@code finops.cache.savings.usd} counter as a proxy for cache hits.
     * - {@code gen_ai.client.token.usage} DistributionSummary total as a proxy
     *   for total tokens consumed. {@code gen_ai.client.token.usage} is emitted
     *   as a DistributionSummary by both {@code GenAiMetricsAdvisor} (line ~299)
     *   and {@code FinOpsObservedEmbeddingModel} (line ~105) — querying it as
     *   {@code .counters()} returned empty (Bug #45), pinning every series point
     *   to ~zero. Now queried as {@code .summaries()} with {@code totalAmount()}
     *   to recover the original {@code totalTokens / 1000} call-proxy arithmetic.
     *
     * @param days Number of trailing days (used for frontend labeling).
     * @return     List of {@code CacheImpactPoint} entries for the stacked area chart.
     */
    public List<CacheImpactPoint> getCacheImpactSeries(int days) {
        log.debug("Computing cache impact series for trailing {} days.", days);

        double cacheHitsSavings = Search.in(meterRegistry)
            .name("finops.cache.savings.usd")
            .counters()
            .stream()
            .mapToDouble(Counter::count)
            .sum();

        double totalApiCalls = Search.in(meterRegistry)
            .name("gen_ai.client.token.usage")
            .summaries()
            .stream()
            .mapToDouble(DistributionSummary::totalAmount)
            .sum();

        // Estimate cache hits as a proportion — each cache hit saves roughly one API call
        // Use a heuristic: cache savings / average_cost_per_run gives approximate deflected calls
        long estimatedCacheHits = Math.round(cacheHitsSavings / averageCostPerRunUsd);
        long estimatedTotalPrompts = Math.max(1, (long) (totalApiCalls / 1000.0) + estimatedCacheHits);

        List<CacheImpactPoint> series = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            String dateKey = day.format(DATE_FORMATTER);
            if (i == 0) {
                // Latest day gets the actual snapshot values
                series.add(new CacheImpactPoint(dateKey, estimatedTotalPrompts, estimatedCacheHits));
            } else {
                // Historical days show zero until per-day bucketing is implemented
                series.add(new CacheImpactPoint(dateKey, 0, 0));
            }
        }

        log.debug("Cache impact series: {} points, latest cacheHits={}, totalPrompts={}",
            series.size(), estimatedCacheHits, estimatedTotalPrompts);
        return series;
    }
}
