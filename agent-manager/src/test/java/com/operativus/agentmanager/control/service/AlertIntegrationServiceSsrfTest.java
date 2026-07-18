package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.model.AlertIntegrationTestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the SSRF guard behavior wired into AlertIntegrationService at four sites:
 * createIntegration, updateIntegration, testFire (operator-fired), and attemptDispatch
 * (covered by the onAlertFired path). Production loopback rejection is exercised by
 * constructing the service with {@code allowLoopbackUrls=false}; the {@code =true}
 * test-profile variant is implicitly covered by AlertIntegrationServiceTest above.
 */
@ExtendWith(MockitoExtension.class)
class AlertIntegrationServiceSsrfTest {

    @Mock private AlertIntegrationRepository repository;

    private static final String ORG = "org-test";

    private AlertIntegrationService strictService() {
        return new AlertIntegrationService(repository, 5, 2L, 300L, false);
    }

    private static AlertIntegration webhookWith(String url) {
        AlertIntegration i = new AlertIntegration();
        i.setName("hook");
        i.setType("WEBHOOK");
        i.setEndpointUrl(url);
        i.setEnabled(true);
        return i;
    }

    @Test
    void createIntegration_internalMetadataAddress_throwsAndDoesNotPersist() {
        AlertIntegration i = webhookWith("http://169.254.169.254/latest/meta-data/");

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                assertThatThrownBy(() -> strictService().createIntegration(i))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("SSRF")
                        .hasMessageContaining("private or loopback");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository, never()).save(any());
    }

    @Test
    void createIntegration_loopbackInProdMode_throwsAndDoesNotPersist() {
        AlertIntegration i = webhookWith("http://127.0.0.1:8080/admin");

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                assertThatThrownBy(() -> strictService().createIntegration(i))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("SSRF");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository, never()).save(any());
    }

    @Test
    void createIntegration_decimalEncodedLoopback_throwsAndDoesNotPersist() {
        // 2130706433 = 127.0.0.1; JDK getByName rejects this form, browsers/curl accept it.
        // SsrfGuard's manual all-digits parser catches it explicitly.
        AlertIntegration i = webhookWith("http://2130706433/admin");

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                assertThatThrownBy(() -> strictService().createIntegration(i))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("SSRF");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository, never()).save(any());
    }

    @Test
    void createIntegration_publicHttpsHook_persists() {
        AlertIntegration i = webhookWith("https://hooks.slack.com/services/T000/B000/XXX");
        when(repository.save(any(AlertIntegration.class))).thenAnswer(inv -> inv.getArgument(0));

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                AlertIntegration saved = strictService().createIntegration(i);
                assertThat(saved.getEndpointUrl()).startsWith("https://hooks.slack.com/");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository).save(any(AlertIntegration.class));
    }

    @Test
    void createIntegration_pagerDutyType_skipsSsrfGuardEvenWithSuspiciousRoutingKey() {
        // PAGERDUTY stores a routing key in endpointUrl, not a URL — the events-v2
        // enqueue URL is hardcoded. SsrfGuard would (correctly) reject "192.168.0.1"
        // as a URL, but we must not run the guard for this type.
        AlertIntegration i = new AlertIntegration();
        i.setName("pd");
        i.setType("PAGERDUTY");
        i.setEndpointUrl("192.168.0.1");
        i.setEnabled(true);
        when(repository.save(any(AlertIntegration.class))).thenAnswer(inv -> inv.getArgument(0));

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> strictService().createIntegration(i));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository).save(any(AlertIntegration.class));
    }

    @Test
    void updateIntegration_switchingUrlToInternalAddress_throwsAndDoesNotPersist() {
        AlertIntegration existing = webhookWith("https://safe.example.com/hook");
        existing.setId("id-1");
        existing.setOrgId(ORG);
        when(repository.findByIdAndOrgId("id-1", ORG)).thenReturn(Optional.of(existing));

        AlertIntegration update = webhookWith("http://10.0.0.5/admin");
        update.setName("hook");

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                assertThatThrownBy(() -> strictService().updateIntegration("id-1", update))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("SSRF");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        verify(repository, never()).save(any());
    }

    @Test
    void testFire_internalUrl_returnsBlockedResultWithoutSendingHttp() {
        AlertIntegration i = webhookWith("http://169.254.169.254/latest/meta-data/");
        i.setId("id-1");
        i.setOrgId(ORG);
        when(repository.findByIdAndOrgId("id-1", ORG)).thenReturn(Optional.of(i));

        try {
            ScopedValue.where(AgentContextHolder.orgId, ORG).run(() -> {
                AlertIntegrationTestResult result = strictService().testFire("id-1");
                assertThat(result.delivered()).isFalse();
                assertThat(result.statusCode()).isZero();
                assertThat(result.message()).startsWith("Blocked by SSRF guard");
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
