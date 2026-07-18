package com.operativus.agentmanager.control.finops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost forecasting using linear regression over historical daily spend data.
 * Projects future costs for 7, 14, and 30 days based on trailing trend.
 */
@Service
public class CostForecastService {

    private static final Logger log = LoggerFactory.getLogger(CostForecastService.class);

    private final FinOpsAnalyticsService analyticsService;

    public CostForecastService(FinOpsAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Generates a cost forecast based on trailing historical data.
     * Uses simple linear regression (least squares) on daily USD spend.
     *
     * @param trailingDays Number of historical days to use for regression (default: 14)
     * @return Forecast map with projected spend at 7, 14, and 30 day horizons
     */
    public Map<String, Object> forecastCosts(int trailingDays) {
        var trends = analyticsService.getHistoricalTrends(trailingDays, null);

        // Extract daily USD values for regression
        double[] dailyUsd = trends.stream()
                .mapToDouble(t -> ((Number) t.estimatedUsd()).doubleValue())
                .toArray();

        // Defensive: linear regression needs at least 2 points to compute a slope without
        // a divide-by-zero. In current production, FinOpsAnalyticsService.getHistoricalTrends
        // zero-fills the requested window so dailyUsd.length always == trailingDays. If that
        // contract ever changes (e.g. an upstream optimization that drops zero days), this
        // guard returns the same zero-shape forecast the regression would produce on all-zeros.
        if (dailyUsd.length < 2) {
            return zeroShapeForecast(trailingDays);
        }

        // Simple linear regression: y = mx + b
        int n = dailyUsd.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += dailyUsd[i];
            sumXY += i * dailyUsd[i];
            sumXX += (double) i * i;
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;

        // Calculate current daily average
        double avgDaily = sumY / n;

        // Project future costs
        Map<String, Object> forecast = new LinkedHashMap<>();
        forecast.put("trailing_days", trailingDays);
        forecast.put("avg_daily_usd", Math.round(avgDaily * 100.0) / 100.0);
        forecast.put("trend_slope_usd_per_day", Math.round(slope * 10000.0) / 10000.0);
        forecast.put("trend_direction", slope > 0.001 ? "INCREASING" : slope < -0.001 ? "DECREASING" : "STABLE");

        // Projected cumulative spend from today
        for (int horizon : List.of(7, 14, 30)) {
            double projected = 0;
            for (int d = 1; d <= horizon; d++) {
                double dayEstimate = Math.max(0, intercept + slope * (n + d));
                projected += dayEstimate;
            }
            forecast.put("projected_" + horizon + "d_usd", Math.round(projected * 100.0) / 100.0);
        }

        // Anomaly detection: flag if slope exceeds 2x the average daily
        if (slope > avgDaily * 2) {
            forecast.put("anomaly", "COST_ACCELERATION_DETECTED");
            forecast.put("anomaly_detail", String.format("Daily cost is growing at $%.4f/day, which exceeds 2x the average daily spend of $%.2f", slope, avgDaily));
        }

        log.info("Cost forecast generated: trailing={}d, avgDaily=${}, slope=${}/day", trailingDays, forecast.get("avg_daily_usd"), forecast.get("trend_slope_usd_per_day"));
        return forecast;
    }

    private Map<String, Object> zeroShapeForecast(int trailingDays) {
        Map<String, Object> forecast = new LinkedHashMap<>();
        forecast.put("trailing_days", trailingDays);
        forecast.put("avg_daily_usd", 0.0);
        forecast.put("trend_slope_usd_per_day", 0.0);
        forecast.put("trend_direction", "STABLE");
        for (int horizon : List.of(7, 14, 30)) {
            forecast.put("projected_" + horizon + "d_usd", 0.0);
        }
        return forecast;
    }
}
