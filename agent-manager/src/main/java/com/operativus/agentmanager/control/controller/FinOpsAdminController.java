package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ActiveAnomalyResponse;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ActiveWindowResponse;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.BaselineRequest;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.BaselineResponse;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CacheImpactPoint;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CostAllocationEntry;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.HistoricalTrendPoint;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ModelCostSlice;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.RoiStatsResponse;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ValuationRateRequest;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ValuationRateResponse;
import com.operativus.agentmanager.control.finops.model.FinOpsRecords.ModelValuationRate;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.control.finops.service.FinOpsAnalyticsService;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Domain Responsibility: REST API boundary for FinOps administration.
 * Exposes real-time token-to-USD valuation rates, active session burn-rate telemetry,
 * and agent baseline configuration to the React Admin UI.
 *
 * Architecture:
 * - Constructor Injection only. No field injection.
 * - Synchronous blocking endpoints scaled natively by Virtual Threads.
 * - Immutable DTO records at the API boundary; domain records internally.
 * - No reactive wrappers ({@code Mono}, {@code Flux}).
 *
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/finops")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class FinOpsAdminController {

    private static final Logger log = LoggerFactory.getLogger(FinOpsAdminController.class);

    private final LiveValuationEngine valuationEngine;
    private final BurnRateMonitorService burnRateMonitor;
    private final FinOpsAnalyticsService analyticsService;
    private final AgentRepository agentRepository;
    private final MeterRegistry meterRegistry;

    public FinOpsAdminController(LiveValuationEngine valuationEngine,
                                 BurnRateMonitorService burnRateMonitor,
                                 FinOpsAnalyticsService analyticsService,
                                 AgentRepository agentRepository,
                                 MeterRegistry meterRegistry) {
        this.valuationEngine   = valuationEngine;
        this.burnRateMonitor   = burnRateMonitor;
        this.analyticsService  = analyticsService;
        this.agentRepository   = agentRepository;
        this.meterRegistry     = meterRegistry;
    }

    // -------------------------------------------------------------------------
    // Valuation Rate Management
    // -------------------------------------------------------------------------

    /**
     * @summary Retrieves all currently registered model valuation rates.
     * @logic
     * - Fetches an immutable snapshot from the {@code LiveValuationEngine} concurrent cache.
     * - Maps each domain record ({@code ModelValuationRate}) to the API DTO ({@code ValuationRateResponse}).
     * - Returns the full rate table as a JSON array.
     *
     * @return 200 OK with a list of all model valuation rates.
     */
    @GetMapping("/valuation-rates")
    public ResponseEntity<List<ValuationRateResponse>> getValuationRates() {
        log.debug("REST request to fetch all FinOps valuation rates.");

        List<ValuationRateResponse> rates = valuationEngine.getRateSnapshot()
            .values()
            .stream()
            .map(rate -> new ValuationRateResponse(
                rate.modelId(),
                rate.inputRatePerKTokens(),
                rate.outputRatePerKTokens(),
                rate.cachedInputRatePerKTokens(),
                rate.reasoningRatePerKTokens()))
            .toList();

        log.debug("Returning {} model valuation rates.", rates.size());
        return ResponseEntity.ok(rates);
    }

    /**
     * @summary Registers or hot-updates a model's token-to-USD valuation rate.
     * @logic
     * - Validates the incoming {@code ValuationRateRequest} via Jakarta Bean Validation.
     * - Converts the API DTO to the domain record ({@code ModelValuationRate}).
     * - Delegates to {@code LiveValuationEngine#register} for thread-safe upsert.
     * - Returns the confirmed rate as a response DTO.
     *
     * <p><b>Authorization:</b> Gated on {@code ROLE_SUPER_ADMIN} rather than the class-level
     * {@code ROLE_ADMIN}. Rationale: {@code finops_valuation_rate} is keyed only on
     * {@code model_id} with no {@code org_id} column &mdash; the rate is shared across every
     * tenant. Allowing any tenant ADMIN to mutate it would let one tenant corrupt the
     * cost-reporting fidelity (set $0/1k &rArr; all spend underreported; set $1000/1k &rArr;
     * all tenants' burn-rate monitors fire). Rate edits are a platform-operator concern.
     *
     * @param request The valuation rate to register or update.
     * @return 200 OK with the confirmed valuation rate.
     */
    @PutMapping("/valuation-rates")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ValuationRateResponse> updateValuationRate(
            @Valid @RequestBody ValuationRateRequest request) {
        log.info("REST request to update valuation rate for model [{}]: input=${}/1k, output=${}/1k",
            request.modelId(), request.inputRatePerKTokens(), request.outputRatePerKTokens());

        ModelValuationRate domainRate = new ModelValuationRate(
            request.modelId(),
            request.inputRatePerKTokens(),
            request.outputRatePerKTokens(),
            request.cachedInputRatePerKTokens(),
            request.reasoningRatePerKTokens());

        valuationEngine.register(domainRate);

        ValuationRateResponse response = new ValuationRateResponse(
            domainRate.modelId(),
            domainRate.inputRatePerKTokens(),
            domainRate.outputRatePerKTokens(),
            domainRate.cachedInputRatePerKTokens(),
            domainRate.reasoningRatePerKTokens());

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Burn Rate Monitoring
    // -------------------------------------------------------------------------

    /**
     * @summary Retrieves all active session burn-rate observation windows.
     * @logic
     * - Fetches an immutable snapshot of active sliding-window accumulators
     *   from the {@code BurnRateMonitorService}.
     * - Maps each entry to the API DTO ({@code ActiveWindowResponse}) exposing
     *   the session ID and cumulative USD spend within the current observation window.
     * - Enables diagnostic dashboards to visualize real-time agent spend velocity.
     *
     * @return 200 OK with a list of all active session burn-rate windows.
     */
    @GetMapping("/burn-rates/active")
    public ResponseEntity<List<ActiveWindowResponse>> getActiveBurnRates() {
        log.debug("REST request to fetch active burn-rate observation windows.");

        List<ActiveWindowResponse> windows = burnRateMonitor.getActiveWindows()
            .entrySet()
            .stream()
            .map(entry -> new ActiveWindowResponse(
                entry.getKey(),
                entry.getValue().getCumulativeUsd()))
            .toList();

        log.debug("Returning {} active burn-rate windows.", windows.size());
        return ResponseEntity.ok(windows);
    }

    // -------------------------------------------------------------------------
    // Historical Analytics (OLAP read path)
    // -------------------------------------------------------------------------

    /**
     * @summary Returns a trailing N-day historical burn rate trend series.
     * @logic Delegates to {@code FinOpsAnalyticsService#getHistoricalTrends}, which runs a
     *        Postgres GROUP BY DATE aggregation on {@code agent_runs}. Zero-fills missing days
     *        so the frontend always receives exactly N data points. Defaults to 7-day window.
     *
     * @param days Optional query param (default 7). Values: 7, 30.
     * @return 200 OK with a list of daily {@code HistoricalTrendPoint} records.
     */
    @GetMapping("/trends")
    public ResponseEntity<List<HistoricalTrendPoint>> getHistoricalTrends(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        log.debug("REST request for historical FinOps trends: trailing {} days.", days);
        int clampedDays = Math.max(1, Math.min(days, 90));
        List<HistoricalTrendPoint> trends = analyticsService.getHistoricalTrends(
                clampedDays, AgentContextHolder.getOrgId());
        return ResponseEntity.ok(trends);
    }

    /**
     * @summary Returns cost allocation breakdown by agent and org dimension.
     * @logic Delegates to {@code FinOpsAnalyticsService#getCostAllocations}, which runs
     *        two Postgres GROUP BY aggregations on {@code agent_runs}. Computes relative
     *        allocation percentage for each slice. Returns empty list on zero data.
     *
     * @param days Optional query param (default 7). Controls the aggregation window.
     * @return 200 OK with a list of {@code CostAllocationEntry} records.
     */
    @GetMapping("/allocations")
    public ResponseEntity<List<CostAllocationEntry>> getCostAllocations(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        log.debug("REST request for FinOps cost allocations: trailing {} days.", days);
        int clampedDays = Math.max(1, Math.min(days, 90));
        List<CostAllocationEntry> allocations = analyticsService.getCostAllocations(
                clampedDays, AgentContextHolder.getOrgId());
        return ResponseEntity.ok(allocations);
    }

    // -------------------------------------------------------------------------
    // Model Cost Allocation (LLM Vendor Slicing)
    // -------------------------------------------------------------------------

    /**
     * @summary Returns cost allocation breakdown by LLM vendor model.
     * @logic Delegates to {@code FinOpsAnalyticsService#getCostAllocationsByModel}, which joins
     *        agent_runs with agents to resolve model_id per run. Returns run count, allocation
     *        percentage, and estimated USD per model slice.
     *
     * @param days Optional query param (default 7). Controls the aggregation window.
     * @return 200 OK with a list of {@code ModelCostSlice} records.
     */
    @GetMapping("/allocations/by-model")
    public ResponseEntity<List<ModelCostSlice>> getCostAllocationsByModel(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        log.debug("REST request for FinOps model cost allocations: trailing {} days.", days);
        int clampedDays = Math.max(1, Math.min(days, 90));
        List<ModelCostSlice> slices = analyticsService.getCostAllocationsByModel(
                clampedDays, AgentContextHolder.getOrgId());
        return ResponseEntity.ok(slices);
    }

    // -------------------------------------------------------------------------
    // Cache Impact Time-Series
    // -------------------------------------------------------------------------

    /**
     * @summary Returns a trailing N-day cache impact time-series for the stacked area chart.
     * @logic Delegates to {@code FinOpsAnalyticsService#getCacheImpactSeries}, which reads
     *        Micrometer counters to compute cache hits versus total prompts.
     *
     * @param days Optional query param (default 7). Controls the time-series window.
     * @return 200 OK with a list of {@code CacheImpactPoint} records.
     */
    @GetMapping("/cache-impact")
    public ResponseEntity<List<CacheImpactPoint>> getCacheImpactSeries(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        log.debug("REST request for FinOps cache impact series: trailing {} days.", days);
        int clampedDays = Math.max(1, Math.min(days, 90));
        List<CacheImpactPoint> series = analyticsService.getCacheImpactSeries(clampedDays);
        return ResponseEntity.ok(series);
    }

    // -------------------------------------------------------------------------
    // ROI Statistics (Semantic Cache + Embedding Cost)
    // -------------------------------------------------------------------------

    /**
     * @summary Returns accumulated Semantic Cache ROI and Embedding cost statistics from Micrometer.
     * @logic
     * - Queries the {@code finops.cache.savings.usd} counter total from the MeterRegistry.
     * - Queries the {@code finops.embedding.cost.usd} distribution summary total from the MeterRegistry.
     * - Computes net ROI as savings minus embedding cost.
     * - Both values are process-lifetime accumulators reset on restart.
     *
     * @return 200 OK with {@code RoiStatsResponse} containing savings, embedding cost, and net ROI.
     */
    @GetMapping("/roi-stats")
    public ResponseEntity<RoiStatsResponse> getRoiStats() {
        log.debug("REST request for FinOps ROI statistics.");

        double cacheSavings = Search.in(meterRegistry)
            .name("finops.cache.savings.usd")
            .counters()
            .stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count)
            .sum();

        double embeddingCost = Search.in(meterRegistry)
            .name("finops.embedding.cost.usd")
            .summaries()
            .stream()
            .mapToDouble(io.micrometer.core.instrument.DistributionSummary::totalAmount)
            .sum();

        double netRoi = cacheSavings - embeddingCost;

        log.debug("FinOps ROI stats — cacheSavings=${:.4f}, embeddingCost=${:.4f}, netRoi=${:.4f}",
            cacheSavings, embeddingCost, netRoi);

        return ResponseEntity.ok(new RoiStatsResponse(cacheSavings, embeddingCost, netRoi));
    }

    // -------------------------------------------------------------------------
    // Anomaly Detection
    // -------------------------------------------------------------------------

    /**
     * @summary Returns all active sessions currently exceeding the burn-rate anomaly threshold.
     * @logic Delegates to {@code BurnRateMonitorService#getActiveAnomalies}, which compares each
     *        session's sliding-window burn rate against the registered agent baseline.
     *        Returns an empty list when no sessions are anomalous.
     *
     * @return 200 OK with a list of {@code ActiveAnomalyResponse} records, or empty list.
     */
    @GetMapping("/anomalies/active")
    public ResponseEntity<List<ActiveAnomalyResponse>> getActiveAnomalies() {
        log.debug("REST request for active burn-rate anomalies.");

        List<ActiveAnomalyResponse> anomalies = burnRateMonitor.getActiveAnomalies()
            .stream()
            .map(a -> new ActiveAnomalyResponse(
                a.sessionId(), a.agentId(),
                a.burnRateUsdPerHour(), a.baselineUsdPerHour(), a.anomalyRatio()))
            .toList();

        log.debug("Returning {} active anomalies.", anomalies.size());
        return ResponseEntity.ok(anomalies);
    }

    // -------------------------------------------------------------------------
    // Agent Baseline Configuration
    // -------------------------------------------------------------------------

    /**
     * @summary Registers or updates the expected baseline burn rate for an agent.
     * @logic
     * - Validates the incoming {@code BaselineRequest} via Jakarta Bean Validation.
     * - Delegates to {@code BurnRateMonitorService#registerBaseline} to persist the
     *   expected normal USD/hour for the target agent.
     * - The baseline is used as the denominator in anomaly detection heuristics:
     *   if the observed burn rate exceeds baseline × multiplier threshold, an alert fires.
     *
     * @param agentId The unique identifier of the agent to configure.
     * @param request The baseline burn rate configuration.
     * @return 200 OK with the confirmed baseline configuration.
     */
    @PutMapping("/baselines/{agentId}")
    public ResponseEntity<BaselineResponse> updateBaseline(
            @PathVariable String agentId,
            @Valid @RequestBody BaselineRequest request) {
        // Cross-tenant guard: BurnRateMonitorService.agentBaselineRates is keyed by agentId
        // with NO tenant column. Pre-fix any admin could overwrite any tenant's baseline by
        // calling PUT /api/v1/finops/baselines/{foreign-agent-id} — corrupting anomaly
        // detection and ROI calculations for that tenant. Existence-leak protection:
        // unknown-or-foreign agent → 404 (same convention as PR #972 cancelRun and PR #998
        // memory delete fix).
        String callerOrgId = AgentContextHolder.getOrgId();
        if (callerOrgId == null || !agentRepository.existsByIdAndOrgId(agentId, callerOrgId)) {
            return ResponseEntity.notFound().build();
        }
        log.info("REST request to set burn-rate baseline for agent [{}]: ${}/hr",
            agentId, request.baselineUsdPerHour());

        burnRateMonitor.registerBaseline(agentId, request.baselineUsdPerHour());

        return ResponseEntity.ok(new BaselineResponse(agentId, request.baselineUsdPerHour()));
    }
}
