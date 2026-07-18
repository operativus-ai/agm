package com.operativus.agentmanager.control.finops.service;

import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CacheImpactPoint;
import com.operativus.agentmanager.control.repository.RunRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bug #45 regression guard: {@code getCacheImpactSeries} previously queried
 * {@code gen_ai.client.token.usage} as {@code .counters()} — but that metric is
 * emitted by {@code GenAiMetricsAdvisor} and {@code FinOpsObservedEmbeddingModel}
 * as a {@code DistributionSummary}. The wrong-type query returned empty, so
 * {@code totalApiCalls} was always 0 and the cache-impact series collapsed to
 * a near-zero proxy.
 *
 * State: Stateless.
 */
class FinOpsAnalyticsServiceCacheImpactTest {

    @Test
    void cacheImpactSeries_ReadsTokenUsageAsDistributionSummary_NotCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // Emit gen_ai.client.token.usage as a DistributionSummary — exactly how the
        // production emitters do. A wrong-type query (.counters()) would not see this.
        DistributionSummary.builder("gen_ai.client.token.usage")
                .baseUnit("tokens")
                .tag("gen_ai.token.type", "input")
                .register(registry)
                .record(5_000);

        FinOpsAnalyticsService service = new FinOpsAnalyticsService(
                mock(RunRepository.class), registry, 0.08);

        List<CacheImpactPoint> series = service.getCacheImpactSeries(1);

        assertThat(series).hasSize(1);
        // 5,000 tokens / 1,000 (call-proxy divisor) = 5 estimated total prompts.
        // Pre-fix this was 0 → max(1, 0) → 1; the series would always report 1 prompt.
        assertThat(series.get(0).totalPrompts())
                .as("totalPrompts must reflect the recorded DistributionSummary totalAmount, not a counter miss")
                .isEqualTo(5L);
        assertThat(series.get(0).cacheHits()).isEqualTo(0L);
    }
}
