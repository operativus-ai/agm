package ai.operativus.agentmanager.control.service.queue;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackgroundJobQueueDepthGaugeTest {

    @Mock private BackgroundJobRepository repository;

    private MeterRegistry meterRegistry;
    private BackgroundJobQueueDepthGauge gauge;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        gauge = new BackgroundJobQueueDepthGauge(repository, meterRegistry);
        gauge.registerGauges();
    }

    @Test
    void registerGauges_RegistersOnePerStatus() {
        for (JobStatus status : JobStatus.values()) {
            Gauge g = meterRegistry.find(BackgroundJobQueueDepthGauge.METRIC_NAME)
                    .tag("status", status.name())
                    .gauge();
            assertThat(g).as("gauge for %s", status).isNotNull();
            assertThat(g.value()).isEqualTo(0.0);
        }
    }

    @Test
    void refresh_PopulatesEachStatusFromCount() {
        when(repository.countByStatus(JobStatus.QUEUED)).thenReturn(7L);
        when(repository.countByStatus(JobStatus.PROCESSING)).thenReturn(2L);
        when(repository.countByStatus(JobStatus.PAUSED)).thenReturn(0L);
        when(repository.countByStatus(JobStatus.COMPLETED)).thenReturn(120L);
        when(repository.countByStatus(JobStatus.FAILED)).thenReturn(3L);
        when(repository.countByStatus(JobStatus.DLQ)).thenReturn(1L);

        gauge.refresh();

        assertThat(gauge.currentDepth(JobStatus.QUEUED)).isEqualTo(7L);
        assertThat(gauge.currentDepth(JobStatus.PROCESSING)).isEqualTo(2L);
        assertThat(gauge.currentDepth(JobStatus.PAUSED)).isEqualTo(0L);
        assertThat(gauge.currentDepth(JobStatus.COMPLETED)).isEqualTo(120L);
        assertThat(gauge.currentDepth(JobStatus.FAILED)).isEqualTo(3L);
        assertThat(gauge.currentDepth(JobStatus.DLQ)).isEqualTo(1L);

        // And the registered gauges reflect the new values via the AtomicLong indirection.
        assertThat(meterRegistry.find(BackgroundJobQueueDepthGauge.METRIC_NAME)
                .tag("status", "QUEUED").gauge().value()).isEqualTo(7.0);
        assertThat(meterRegistry.find(BackgroundJobQueueDepthGauge.METRIC_NAME)
                .tag("status", "FAILED").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void refresh_WhenRepositoryThrows_KeepsLastKnownValues() {
        when(repository.countByStatus(JobStatus.QUEUED)).thenReturn(5L);
        when(repository.countByStatus(JobStatus.PROCESSING)).thenReturn(1L);
        when(repository.countByStatus(JobStatus.PAUSED)).thenReturn(0L);
        when(repository.countByStatus(JobStatus.COMPLETED)).thenReturn(50L);
        when(repository.countByStatus(JobStatus.FAILED)).thenReturn(0L);
        when(repository.countByStatus(JobStatus.DLQ)).thenReturn(0L);
        gauge.refresh();
        assertThat(gauge.currentDepth(JobStatus.QUEUED)).isEqualTo(5L);

        // Subsequent refresh fails — value should hold steady, not drop to 0.
        when(repository.countByStatus(JobStatus.QUEUED)).thenThrow(new RuntimeException("db hiccup"));
        gauge.refresh();
        assertThat(gauge.currentDepth(JobStatus.QUEUED)).isEqualTo(5L);
    }
}
