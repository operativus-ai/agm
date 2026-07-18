package ai.operativus.agentmanager.compute.config;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Domain Responsibility: Configures the OTLP gRPC span exporter for sending agent telemetry
 * to external observability platforms (Jaeger, Datadog, Splunk, Grafana Tempo).
 *
 * <p>Activation: Only active when {@code agentmanager.otlp.enabled=true}. When disabled,
 * no OTLP beans are created and the advisor pipeline is unaffected.</p>
 *
 * <p>Architecture: Uses a {@link BatchSpanProcessor} to buffer spans and export asynchronously,
 * ensuring zero impact on agent inference latency.</p>
 *
 * State: Stateless (Configuration)
 */
@Configuration
@ConditionalOnProperty(name = "agentmanager.otlp.enabled", havingValue = "true", matchIfMissing = false)
public class OtlpExportConfig {

    private static final Logger log = LoggerFactory.getLogger(OtlpExportConfig.class);

    @Value("${agentmanager.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Value("${agentmanager.otlp.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${agentmanager.otlp.batch-size:512}")
    private int batchSize;

    @Value("${agentmanager.otlp.export-interval-ms:5000}")
    private long exportIntervalMs;

    @Bean
    public OtlpGrpcSpanExporter otlpGrpcSpanExporter() {
        log.info("Initializing OTLP gRPC span exporter targeting endpoint: {}", otlpEndpoint);
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .setTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Bean
    public SpanProcessor otlpBatchSpanProcessor(OtlpGrpcSpanExporter exporter) {
        log.info("Configuring BatchSpanProcessor: batchSize={}, exportInterval={}ms", batchSize, exportIntervalMs);
        return BatchSpanProcessor.builder(exporter)
                .setMaxExportBatchSize(batchSize)
                .setScheduleDelay(Duration.ofMillis(exportIntervalMs))
                .build();
    }
}
