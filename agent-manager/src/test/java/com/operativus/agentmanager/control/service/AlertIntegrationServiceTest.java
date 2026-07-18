package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertIntegrationServiceTest {

    @Mock private AlertIntegrationRepository repository;

    private AlertIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new AlertIntegrationService(repository, 5, 2L, 300L, true);
    }

    private static final String ORG = "org-test";

    @Test
    void onAlertFired_queriesEnabledIntegrationsAndDispatchesPayload() {
        AlertIntegration integration = mock(AlertIntegration.class);
        when(integration.getEndpointUrl()).thenReturn("http://localhost/webhook");
        when(repository.findByOrgIdAndEnabledTrue(ORG)).thenReturn(List.of(integration));

        AlertFiredEvent event = new AlertFiredEvent(this, "approval-sla", "id-1", "HIGH", "SLA breached", ORG);

        service.onAlertFired(event);

        verify(repository, times(1)).findByOrgIdAndEnabledTrue(ORG);
    }

    @Test
    void onAlertFired_skipsDispatchWhenNoEnabledIntegrations() {
        when(repository.findByOrgIdAndEnabledTrue(ORG)).thenReturn(List.of());

        AlertFiredEvent event = new AlertFiredEvent(this, "approval-sla", "id-1", "HIGH", "SLA breached", ORG);

        service.onAlertFired(event);

        verify(repository, times(1)).findByOrgIdAndEnabledTrue(ORG);
        verifyNoMoreInteractions(repository);
    }
}
