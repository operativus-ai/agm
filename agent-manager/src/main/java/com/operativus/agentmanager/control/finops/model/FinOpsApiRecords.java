package com.operativus.agentmanager.control.finops.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Domain Responsibility: Immutable DTO records for the FinOps admin REST API boundary.
 * These records define the request and response shapes transmitted across the wire
 * between the React Admin UI and the {@code FinOpsAdminController}.
 *
 * Architecture: Pure value objects. No Lombok. No mutable state.
 * Validated via Jakarta Bean Validation annotations at the controller boundary.
 *
 * State: Immutable Records (DTOs)
 */
public final class FinOpsApiRecords {

    private FinOpsApiRecords() {}

    /**
     * Request DTO for registering or updating a model's token-to-USD valuation rate.
     * Transmitted via {@code PUT /api/v1/finops/valuation-rates}.
     *
     * @param modelId                   Model identifier (e.g., "gpt-4o", "claude-3-5-sonnet").
     * @param inputRatePerKTokens       USD cost per 1,000 input (prompt) tokens. Must be positive.
     * @param outputRatePerKTokens      USD cost per 1,000 output (completion) tokens. Must be positive.
     * @param cachedInputRatePerKTokens USD cost per 1,000 cached prompt tokens. Zero = not configured.
     * @param reasoningRatePerKTokens   USD cost per 1,000 reasoning tokens (o1/thinking models). Zero = not configured.
     */
    public record ValuationRateRequest(
        @NotBlank(message = "modelId is required")
        String modelId,

        @Positive(message = "inputRatePerKTokens must be positive")
        double inputRatePerKTokens,

        @Positive(message = "outputRatePerKTokens must be positive")
        double outputRatePerKTokens,

        @jakarta.validation.constraints.PositiveOrZero(message = "cachedInputRatePerKTokens must be zero or positive")
        double cachedInputRatePerKTokens,

        @jakarta.validation.constraints.PositiveOrZero(message = "reasoningRatePerKTokens must be zero or positive")
        double reasoningRatePerKTokens
    ) {}

    /**
     * Response DTO representing a single model valuation rate entry.
     * Returned within the snapshot list from {@code GET /api/v1/finops/valuation-rates}.
     *
     * @param modelId                   Model identifier key.
     * @param inputRatePerKTokens       Current USD cost per 1,000 input tokens.
     * @param outputRatePerKTokens      Current USD cost per 1,000 output tokens.
     * @param cachedInputRatePerKTokens Current USD cost per 1,000 cached prompt tokens (0 = not configured).
     * @param reasoningRatePerKTokens   Current USD cost per 1,000 reasoning tokens (0 = not configured).
     */
    public record ValuationRateResponse(
        String modelId,
        double inputRatePerKTokens,
        double outputRatePerKTokens,
        double cachedInputRatePerKTokens,
        double reasoningRatePerKTokens
    ) {}

    /**
     * Request DTO for configuring an agent's expected baseline burn rate.
     * Transmitted via {@code PUT /api/v1/finops/baselines/{agentId}}.
     *
     * @param baselineUsdPerHour Expected normal USD/hour consumption for the target agent.
     *                           Used as the denominator in anomaly detection heuristics.
     */
    public record BaselineRequest(
        @Positive(message = "baselineUsdPerHour must be positive")
        double baselineUsdPerHour
    ) {}

    /**
     * Response DTO confirming a baseline registration or update.
     *
     * @param agentId            The agent whose baseline was set.
     * @param baselineUsdPerHour The registered baseline USD/hour value.
     */
    public record BaselineResponse(
        String agentId,
        double baselineUsdPerHour
    ) {}

    /**
     * Response DTO representing an active session's burn rate observation window.
     * Returned within the active windows list from {@code GET /api/v1/finops/burn-rates/active}.
     *
     * @param sessionId     Session identifier for the monitored agent execution.
     * @param cumulativeUsd Total USD accumulated within the current observation window.
     */
    public record ActiveWindowResponse(
        String sessionId,
        double cumulativeUsd
    ) {}

    /**
     * Response DTO for a single data point in the 7-day historical burn rate trend.
     * Returned within the trend series from {@code GET /api/v1/finops/trends}.
     *
     * @param date         ISO-8601 date string (e.g., "2026-04-05") representing the day.
     * @param runCount     Number of agent execution runs recorded on this day.
     * @param estimatedUsd Estimated USD expenditure for this day based on run activity.
     */
    public record HistoricalTrendPoint(
        String date,
        long runCount,
        double estimatedUsd
    ) {}

    /**
     * Response DTO for a single entry in the cost allocation breakdown.
     * Returned within the allocation list from {@code GET /api/v1/finops/allocations}.
     *
     * @param dimension         Allocation dimension type: "agent" or "org".
     * @param label             Human-readable label for this slice (agentId or orgId).
     * @param runCount          Number of runs attributed to this dimension during the window.
     * @param allocationPercent Percentage of total runs attributed to this slice (0–100).
     */
    public record CostAllocationEntry(
        String dimension,
        String label,
        long runCount,
        double allocationPercent
    ) {}

    /**
     * Response DTO for a single model cost slice in the vendor cost allocation breakdown.
     * Returned within the model allocation list from {@code GET /api/v1/finops/allocations/by-model}.
     *
     * @param modelId          LLM model identifier (e.g., "claude-3-5-sonnet", "gpt-4o").
     * @param runCount         Number of runs attributed to this model during the window.
     * @param allocationPercent Percentage of total runs attributed to this model (0–100).
     * @param estimatedUsd     Estimated USD expenditure for this model slice.
     */
    public record ModelCostSlice(
        String modelId,
        long runCount,
        double allocationPercent,
        double estimatedUsd
    ) {}

    /**
     * Response DTO for a single data point in the cache impact time-series.
     * Returned within the cache impact series from {@code GET /api/v1/finops/cache-impact}.
     *
     * @param date          ISO-8601 date string (e.g., "2026-04-05") representing the day.
     * @param totalPrompts  Total prompts processed (API calls + cache-deflected).
     * @param cacheHits     Number of prompts deflected by the semantic cache.
     */
    public record CacheImpactPoint(
        String date,
        long totalPrompts,
        long cacheHits
    ) {}

    /**
     * Response DTO for an active burn-rate anomaly detected by the sliding window monitor.
     * Returned within the anomaly list from {@code GET /api/v1/finops/anomalies/active}.
     *
     * @param sessionId           Session identifier of the anomalous agent execution.
     * @param agentId             Agent responsible for the session.
     * @param burnRateUsdPerHour  Observed burn rate in USD/hour for the current window.
     * @param baselineUsdPerHour  Registered normal baseline for this agent.
     * @param anomalyRatio        burnRate / baseline — how many times above normal.
     */
    public record ActiveAnomalyResponse(
        String sessionId,
        String agentId,
        double burnRateUsdPerHour,
        double baselineUsdPerHour,
        double anomalyRatio
    ) {}

    /**
     * Response DTO for the ROI statistics endpoint.
     * Aggregates Semantic Cache savings and Embedding compute costs from Micrometer meters.
     * Returned from {@code GET /api/v1/finops/roi-stats}.
     *
     * @param totalCacheSavingsUsd   Total estimated USD saved by semantic cache hits since process start.
     * @param totalEmbeddingCostUsd  Total USD spent on vector embedding operations since process start.
     * @param netRoiUsd              Net ROI: totalCacheSavingsUsd − totalEmbeddingCostUsd.
     */
    public record RoiStatsResponse(
        double totalCacheSavingsUsd,
        double totalEmbeddingCostUsd,
        double netRoiUsd
    ) {}
}
