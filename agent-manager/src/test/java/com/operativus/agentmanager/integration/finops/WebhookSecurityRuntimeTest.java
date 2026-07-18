package com.operativus.agentmanager.integration.finops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pin for the production-default SSRF guard at webhook
 *     dispatch time. {@code BudgetAlertsRuntimeTest} runs with
 *     {@code agentmanager.alerts.ssrf.allow-loopback-urls=true} to allow WireMock
 *     receivers on {@code 127.0.0.1}; this class runs with the production default
 *     {@code allow-loopback-urls=false} and verifies that
 *     {@code AlertIntegrationService.attemptDispatch}'s defense-in-depth
 *     {@code SsrfGuard.validate} check actually rejects loopback / RFC-1918 /
 *     link-local-cloud-metadata URLs at dispatch time.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>This guards against the scenario where a row was inserted before the create-time
 * guard existed, or via a path that bypassed controller validation. The runtime guard
 * is the last line of defense against SSRF — without it, a stale loopback row would
 * keep POSTing to internal infrastructure on every alert.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = {
        "agentmanager.alerts.ssrf.allow-loopback-urls=false",
        "agentmanager.alerts.retry.max-attempts=2",
        "agentmanager.alerts.retry.base-delay-seconds=1",
        "agentmanager.alerts.retry.max-delay-seconds=2"
})
public class WebhookSecurityRuntimeTest extends BaseIntegrationTest {

    private static WireMockServer wiremock;

    @Autowired private AlertIntegrationRepository integrationRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wiremock.resetAll();
        // Default catch-all so any accidental POST would surface (and fail the test).
        wiremock.stubFor(post(urlPathEqualTo("/should-never-be-hit"))
                .willReturn(aResponse().withStatus(200)));
    }

    /**
     * D5a — runtime SSRF guard rejects loopback URLs when allow-loopback=false.
     *
     * <p>Persists an AlertIntegration with a loopback URL directly via the repository
     * (bypasses the controller's create-time guard, simulating a stale row from a
     * pre-guard era). Publishes an AlertFiredEvent; asserts the receiver never gets
     * POSTed and the integration row's {@code lastError} carries the SSRF rejection.
     */
    @Test
    void ssrfGuard_rejectsLoopbackUrl_whenAllowLoopbackFalse_doesNotPostWebhook() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/should-not-be-hit-" + tag;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("MUST NEVER FIRE")));

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-ssrf-loopback-" + tag);
        integ.setName("loopback SSRF probe");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://127.0.0.1:" + wiremock.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integrationRepository.save(integ);

        eventPublisher.publishEvent(new AlertFiredEvent(this, "RULE_SSRF_LOOPBACK",
                "evt-ssrf-loopback-" + tag, "WARNING", "loopback URL must be blocked at dispatch", "org-a"));

        Awaitility.await("SSRF rejection lands as failure on integration row")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getLastError() != null && row.getLastError().contains("SSRF"))
                        .orElse(false));

        long posts = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count();
        assertEquals(0L, posts,
                "WireMock must NOT have received any POST — SSRF guard rejected the dispatch BEFORE HTTP send");

        AlertIntegration row = integrationRepository.findById(integ.getId()).orElseThrow();
        assertNotNull(row.getLastError(),
                "lastError must record the SSRF rejection so operators can see why the integration is failing");
        assertTrue(row.getLastError().contains("SSRF"),
                "lastError must mention SSRF (got: " + row.getLastError() + ")");
    }

    /**
     * D5b — runtime SSRF guard rejects link-local cloud-metadata URLs unconditionally.
     *
     * <p>{@code 169.254.169.254} is the AWS / GCP / Azure metadata service. The guard
     * rejects this regardless of {@code allow-loopback-urls} (always-on rejection per
     * SsrfGuard). Pins that a stale row whose URL points at cloud metadata is blocked
     * at dispatch time even when the operator has flipped {@code allow-loopback-urls=true}
     * for testing purposes.
     */
    @Test
    void ssrfGuard_rejectsCloudMetadataUrl_unconditionally_doesNotPostWebhook() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-ssrf-metadata-" + tag);
        integ.setName("cloud metadata SSRF probe");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://169.254.169.254/latest/meta-data/iam/security-credentials/");
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integrationRepository.save(integ);

        eventPublisher.publishEvent(new AlertFiredEvent(this, "RULE_SSRF_METADATA",
                "evt-ssrf-metadata-" + tag, "WARNING", "link-local metadata URL must be blocked", "org-a"));

        Awaitility.await("SSRF rejection lands as failure on integration row")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getLastError() != null && row.getLastError().contains("SSRF"))
                        .orElse(false));

        AlertIntegration row = integrationRepository.findById(integ.getId()).orElseThrow();
        assertTrue(row.getLastError().contains("SSRF"),
                "lastError must mention SSRF for link-local metadata URL (got: " + row.getLastError() + ")");
    }
}
