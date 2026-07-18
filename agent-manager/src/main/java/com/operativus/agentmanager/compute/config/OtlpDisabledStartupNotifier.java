package com.operativus.agentmanager.compute.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: Emits a startup WARN log line when OTLP span export is disabled,
 *   so operators in production deployments are loudly notified that distributed traces are
 *   dormant. Counterpart to {@link OtlpExportConfig}, which only fires its INFO logs when
 *   the property is explicitly {@code true}.
 * State: Stateless.
 *
 * <p><b>Activation:</b> Component is registered iff {@code agentmanager.otlp.enabled} is
 * {@code false} or unset ({@code matchIfMissing=true}). When OTLP is enabled, this bean is
 * skipped and {@code OtlpExportConfig}'s own INFO log fires instead — so exactly one of the
 * two log lines surfaces at boot.
 */
@Component
@ConditionalOnProperty(name = "agentmanager.otlp.enabled", havingValue = "false", matchIfMissing = true)
public class OtlpDisabledStartupNotifier {

    private static final Logger log = LoggerFactory.getLogger(OtlpDisabledStartupNotifier.class);

    @PostConstruct
    public void warnAtStartup() {
        log.warn("OTLP span export is DISABLED (agentmanager.otlp.enabled=false). Distributed traces will NOT be sent to Jaeger / Datadog / Splunk / Grafana Tempo. " +
                "To enable: set agentmanager.otlp.enabled=true and ensure agentmanager.otlp.endpoint points at a reachable OTLP gRPC collector " +
                "(default: http://localhost:4317 — matches the jaeger service in docker-compose.yml). For production, route to your APM vendor's OTLP gateway.");
    }
}
