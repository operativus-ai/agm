package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RET-T007 #4: proves the @EventListener wiring on AlertIntegrationService.onAlertFired —
 * publishing an AlertFiredEvent through the real ApplicationEventPublisher must reach the
 * (spied) listener. AlertIntegrationServiceTest already covers method behavior; this test
 * covers the publication-to-listener path that no other test exercises.
 *
 * Slice rationale: narrow @SpringBootTest with classes = SliceConfig.class (a
 * @SpringBootConfiguration with no auto-configuration) plus @EnableAsync. Boot is <2s on
 * a warm classpath because Liquibase / DynamicProviderInitializer / vector store config
 * are not loaded.
 */
@Tag("integration")
@SpringBootTest(classes = ApprovalSlaListenerWiringIntegrationTest.SliceConfig.class)
class ApprovalSlaListenerWiringIntegrationTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @MockitoSpyBean
    private AlertIntegrationService alertIntegrationService;

    @MockitoBean
    private AlertIntegrationRepository alertIntegrationRepository;

    private static final String ORG = "org-listener-wiring";

    @Test
    void publishingAlertFiredEvent_invokesOnAlertFiredListener() {
        when(alertIntegrationRepository.findByOrgIdAndEnabledTrue(ORG)).thenReturn(List.of());

        AlertFiredEvent event = new AlertFiredEvent(
                this, "APPROVAL_SLA_BREACH", "approval-xyz", "WARNING",
                "Pending approval approval-xyz has exceeded the 20-hour SLA threshold.", ORG);

        publisher.publishEvent(event);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(alertIntegrationService).onAlertFired(argThat(e ->
                        "APPROVAL_SLA_BREACH".equals(e.getRuleId())
                                && "approval-xyz".equals(e.getEventId())
                                && "WARNING".equals(e.getSeverity()))));
    }

    @Test
    void publishingAlertFiredEvent_listenerInvokedExactlyOnce() {
        when(alertIntegrationRepository.findByOrgIdAndEnabledTrue(ORG)).thenReturn(List.of());

        publisher.publishEvent(new AlertFiredEvent(
                this, "APPROVAL_SLA_BREACH", "single-shot", "WARNING", "msg", ORG));

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                verify(alertIntegrationService, org.mockito.Mockito.times(1))
                        .onAlertFired(any(AlertFiredEvent.class)));
    }

    @SpringBootConfiguration
    @EnableAsync
    static class SliceConfig {

        @Bean
        AlertIntegrationService alertIntegrationService(AlertIntegrationRepository repository) {
            // @Value defaults are bypassed — pass production-default ints/longs directly so
            // we don't need property-source resolution in this slice.
            return new AlertIntegrationService(repository, 5, 2L, 300L, true);
        }
    }
}
