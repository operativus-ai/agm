package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AlertIntegration;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the §4 P5 merge-conflict resolution between PR #232 (HMAC outbound
 * webhook signing) and PR #233 (PagerDuty Events API v2 dispatch). The merge-time fix unified
 * dispatch through {@code AlertIntegrationService.buildSignedRequest(integration, payload)}
 * which calls {@link AlertIntegrationService#endpointUriFor} for URI resolution and then layers
 * HMAC headers on top — both features must compose without one cancelling the other.
 *
 * <p>Without this regression test, a future refactor could re-fork the dispatch path (HMAC
 * for WEBHOOK/SLACK, separate PagerDuty path) and lose the property that an operator who wants
 * BOTH PagerDuty delivery AND HMAC verification (e.g. via a forwarding proxy that signs on
 * AGM's behalf) gets both.
 */
class HmacPagerDutyComposeTest {

    @Test
    void buildSignedRequest_pagerDutyType_routesToEnqueueUrl() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration pd = pagerDuty("RK", null);

        HttpRequest req = service.buildSignedRequest(pd,
                "{\"routing_key\":\"RK\",\"event_action\":\"trigger\",\"payload\":{}}");

        assertThat(req.uri().toString()).isEqualTo(AlertIntegrationService.PAGERDUTY_ENQUEUE_URL);
        // No signing secret → no HMAC headers, but the PD URL still wins.
        assertThat(req.headers().firstValue("X-AGM-Signature")).isEmpty();
        assertThat(req.headers().firstValue("X-AGM-Timestamp")).isEmpty();
    }

    @Test
    void buildSignedRequest_pagerDutyTypeWithSigningSecret_emitsBothPagerDutyUriAndHmacHeaders() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration pd = pagerDuty("RK", "shhh");

        String body = "{\"routing_key\":\"RK\",\"event_action\":\"trigger\",\"payload\":{}}";
        HttpRequest req = service.buildSignedRequest(pd, body);

        // PagerDuty wire format wins on URI.
        assertThat(req.uri().toString()).isEqualTo(AlertIntegrationService.PAGERDUTY_ENQUEUE_URL);

        // HMAC layered on top — receivers (or a forwarding proxy) can verify origin.
        Optional<String> ts = req.headers().firstValue("X-AGM-Timestamp");
        Optional<String> sig = req.headers().firstValue("X-AGM-Signature");
        assertThat(ts).isPresent();
        assertThat(sig).isPresent();
        assertThat(sig.get()).startsWith("sha256=");

        // Signature must equal HMAC over (timestamp + "." + body) — same canonical string the
        // webhook path uses, regardless of whether the URI happens to be a PagerDuty URL.
        String expected = "sha256=" + AlertIntegrationService.computeHmacSha256(
                "shhh", ts.get() + "." + body);
        assertThat(sig.get()).isEqualTo(expected);
    }

    @Test
    void buildSignedRequest_webhookTypeWithSigningSecret_emitsStoredUrlAndHmacHeaders() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration webhook = new AlertIntegration();
        webhook.setType("WEBHOOK");
        webhook.setEndpointUrl("https://example.invalid/hook");
        webhook.setSigningSecret("shhh");

        HttpRequest req = service.buildSignedRequest(webhook, "{\"hello\":\"world\"}");

        // WEBHOOK keeps its stored URL — PD logic must not bleed into the WEBHOOK path.
        assertThat(req.uri().toString()).isEqualTo("https://example.invalid/hook");
        // HMAC headers ride along regardless of integration type.
        assertThat(req.headers().firstValue("X-AGM-Timestamp")).isPresent();
        assertThat(req.headers().firstValue("X-AGM-Signature")).isPresent();
    }

    private static AlertIntegration pagerDuty(String routingKey, String signingSecret) {
        AlertIntegration pd = new AlertIntegration();
        pd.setType("PAGERDUTY");
        pd.setEndpointUrl(routingKey);
        pd.setSigningSecret(signingSecret);
        return pd;
    }
}
