package ai.operativus.agentmanager.control.service.queue;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Domain Responsibility: Periodic sampler that exposes background-job queue depth
 *     as Micrometer gauges (observability plan T036, resolves CR2). One gauge per
 *     {@link JobStatus} value, all under metric name {@code agm.observability.bgjob.queue_depth}
 *     with a {@code status} tag. Scraped via the existing Prometheus endpoint;
 *     historical time-series lives in Prometheus/Grafana, not in this database.
 *
 * <p>Why a periodic sampler instead of a poll-on-scrape callback: every
 * Prometheus scrape would otherwise trigger a {@code COUNT(*)} query per status
 * — a thundering herd if multiple scrapers are configured. The sampler runs at
 * a fixed cadence (default 30s) and the gauges read a cached {@link AtomicLong}
 * per status, so scrape latency is constant regardless of cadence.
 *
 * State: Stateful — holds an {@code EnumMap<JobStatus, AtomicLong>} of last
 *     observed depths. The map is populated at construction (zeroes) and
 *     refreshed by {@link #refresh()} on the configured cadence.
 */
@Component
public class BackgroundJobQueueDepthGauge {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobQueueDepthGauge.class);

    public static final String METRIC_NAME = "agm.observability.bgjob.queue_depth";

    private final BackgroundJobRepository repository;
    private final MeterRegistry meterRegistry;
    private final EnumMap<JobStatus, AtomicLong> depthByStatus = new EnumMap<>(JobStatus.class);

    public BackgroundJobQueueDepthGauge(
            BackgroundJobRepository repository,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        for (JobStatus status : JobStatus.values()) {
            depthByStatus.put(status, new AtomicLong(0L));
        }
    }

    /**
     * @summary Registers one gauge per {@link JobStatus} at startup. Each gauge
     *     reads from the cached {@link AtomicLong} populated by {@link #refresh()};
     *     gauge values are therefore eventually consistent within the refresh
     *     cadence (default 30s).
     */
    @PostConstruct
    void registerGauges() {
        for (Map.Entry<JobStatus, AtomicLong> entry : depthByStatus.entrySet()) {
            Gauge.builder(METRIC_NAME, entry.getValue(), AtomicLong::doubleValue)
                    .tag("status", entry.getKey().name())
                    .description("Number of background_jobs rows currently in this status, sampled every 30s.")
                    .register(meterRegistry);
        }
    }

    /**
     * @summary Refreshes the cached depth-per-status counters from the database.
     *     Runs every {@code agm.observability.bgjob.queue-depth.refresh-ms} ms
     *     (default 30000); on failure logs a warning and leaves last known values
     *     in place so a transient DB hiccup doesn't drop the metric to zero.
     */
    @Scheduled(fixedDelayString = "${agm.observability.bgjob.queue-depth.refresh-ms:30000}")
    public void refresh() {
        try {
            for (JobStatus status : JobStatus.values()) {
                long count = repository.countByStatus(status);
                depthByStatus.get(status).set(count);
            }
        } catch (RuntimeException ex) {
            log.warn("BackgroundJobQueueDepthGauge refresh failed; keeping last known values", ex);
        }
    }

    /** Test-only accessor for the cached depth map. */
    @SuppressWarnings("unused")
    long currentDepth(JobStatus status) {
        AtomicLong v = depthByStatus.get(status);
        return v == null ? 0L : v.get();
    }
}
