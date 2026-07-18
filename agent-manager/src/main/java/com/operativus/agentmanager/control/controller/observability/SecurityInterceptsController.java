package com.operativus.agentmanager.control.controller.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: JSON aggregation endpoint exposing the Security Intercepts counters
 *   wired into {@code PIIAnonymizationAdvisor} and {@code PromptInjectionAdvisor}. Backs the
 *   dashboard's SecurityInterceptsWidget so operators can see daily intercept counts without
 *   parsing the Prometheus text format.
 * State: Stateless controller — meters live on the MeterRegistry singleton.
 *
 * <p>The dashboard widget polls this endpoint every 30s; values are cumulative since process
 * start (not windowed). For windowed views (e.g. last 24h), the production path is to scrape
 * /actuator/prometheus into Grafana and apply rate() — outside this surface.
 */
@RestController
@RequestMapping("/api/v1/observability/security-intercepts")
public class SecurityInterceptsController {

    private final MeterRegistry meterRegistry;

    public SecurityInterceptsController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public record SecurityInterceptsResponse(
            long piiScannedClean,
            long piiScannedRedacted,
            long piiRedactionEvents,
            long promptInjectionOk,
            long promptInjectionBlocked
    ) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SecurityInterceptsResponse> getCounts() {
        return ResponseEntity.ok(new SecurityInterceptsResponse(
                count("agm.security.pii.scanned", "outcome", "clean"),
                count("agm.security.pii.scanned", "outcome", "redacted"),
                count("agm.security.pii.redaction_events", null, null),
                count("agm.security.prompt_injection.scanned", "outcome", "ok"),
                count("agm.security.prompt_injection.scanned", "outcome", "blocked")));
    }

    /**
     * Reads a counter's cumulative value. Returns 0 when the meter hasn't registered yet
     * (e.g. fresh process start with no traffic) — consistent with Prometheus default.
     */
    private long count(String name, String tagKey, String tagValue) {
        Search search = meterRegistry.find(name);
        if (tagKey != null && tagValue != null) {
            search = search.tag(tagKey, tagValue);
        }
        Counter counter = search.counter();
        return counter == null ? 0L : (long) counter.count();
    }
}
