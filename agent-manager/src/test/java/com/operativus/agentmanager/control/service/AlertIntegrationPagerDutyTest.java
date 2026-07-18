package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AlertIntegration;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the PagerDuty Events API v2 wire format produced by
 * {@link AlertIntegrationService#buildPayload}. PagerDuty rejects any payload that
 * doesn't match its schema, and once an operator wires AGM into PagerDuty we can't
 * silently drift the body shape — these tests are the contract.
 */
class AlertIntegrationPagerDutyTest {

    @Test
    void buildPayload_pagerduty_realEvent_emitsEventsV2TriggerShape() {
        AlertIntegration pd = pagerDuty("R0UTING-KEY-1234567890123456789012");

        String body = AlertIntegrationService.buildPayload(
                pd, "evt-42", "approval-sla", "CRITICAL", "Approval queue exceeded SLA", false);

        // routing_key is the endpoint_url field on the integration row.
        assertThat(body).contains("\"routing_key\":\"R0UTING-KEY-1234567890123456789012\"");
        assertThat(body).contains("\"event_action\":\"trigger\"");
        // dedup_key = eventId so PagerDuty collapses retries onto the same incident.
        assertThat(body).contains("\"dedup_key\":\"evt-42\"");
        assertThat(body).contains("\"summary\":\"Approval queue exceeded SLA\"");
        assertThat(body).contains("\"source\":\"agent-manager\"");
        assertThat(body).contains("\"severity\":\"critical\"");
        assertThat(body).contains("\"class\":\"approval-sla\"");
        assertThat(body).contains("\"custom_details\":{\"eventId\":\"evt-42\",\"ruleId\":\"approval-sla\"}");
        assertThat(body).doesNotContain("\"test\"");
    }

    @Test
    void buildPayload_pagerduty_testFire_carriesTestMarker() {
        AlertIntegration pd = pagerDuty("RK");

        String body = AlertIntegrationService.buildPayload(
                pd, "test-1", "agm.test-fire", "INFO", "operator probe", true);

        assertThat(body).contains("\"severity\":\"info\"");
        assertThat(body).contains("\"custom_details\":{\"eventId\":\"test-1\",\"ruleId\":\"agm.test-fire\",\"test\":true}");
    }

    @Test
    void buildPayload_pagerduty_escapesJsonControlCharsInMessage() {
        AlertIntegration pd = pagerDuty("RK");

        String body = AlertIntegrationService.buildPayload(
                pd, "evt", "rule", "WARNING", "She said \"hello\"\\nworld", false);

        // The summary should be a valid JSON string — quotes and backslashes escaped.
        assertThat(body).contains("\"summary\":\"She said \\\"hello\\\"\\\\nworld\"");
    }

    @Test
    void buildPayload_webhook_isUnchanged_flatNativeShape() {
        AlertIntegration webhook = new AlertIntegration();
        webhook.setType("WEBHOOK");
        webhook.setEndpointUrl("https://example.invalid/hook");

        String body = AlertIntegrationService.buildPayload(
                webhook, "evt-7", "rule-x", "WARNING", "msg", false);

        assertThat(body).isEqualTo(
                "{\"eventId\":\"evt-7\",\"ruleId\":\"rule-x\",\"severity\":\"WARNING\",\"message\":\"msg\"}");
    }

    @Test
    void endpointUriFor_pagerduty_returnsEnqueueConstant_notTheRoutingKey() {
        AlertIntegration pd = pagerDuty("RK-123");
        URI uri = AlertIntegrationService.endpointUriFor(pd);
        assertThat(uri).isEqualTo(URI.create(AlertIntegrationService.PAGERDUTY_ENQUEUE_URL));
        assertThat(uri.toString()).isEqualTo("https://events.pagerduty.com/v2/enqueue");
    }

    @Test
    void endpointUriFor_webhook_returnsStoredUrl() {
        AlertIntegration webhook = new AlertIntegration();
        webhook.setType("WEBHOOK");
        webhook.setEndpointUrl("https://example.invalid/hook");
        assertThat(AlertIntegrationService.endpointUriFor(webhook))
                .isEqualTo(URI.create("https://example.invalid/hook"));
    }

    @Test
    void mapSeverityToPagerDuty_coversAllAgmValuesAndFallsBackToWarning() {
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("INFO")).isEqualTo("info");
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("WARNING")).isEqualTo("warning");
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("CRITICAL")).isEqualTo("critical");
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("ERROR")).isEqualTo("error");
        // Case + whitespace tolerated.
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("  critical  ")).isEqualTo("critical");
        // Unknown values fall back rather than dropping the alert.
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("HOT")).isEqualTo("warning");
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty("")).isEqualTo("warning");
        assertThat(AlertIntegrationService.mapSeverityToPagerDuty(null)).isEqualTo("warning");
    }

    private static AlertIntegration pagerDuty(String routingKey) {
        AlertIntegration pd = new AlertIntegration();
        pd.setType("PAGERDUTY");
        pd.setEndpointUrl(routingKey);
        return pd;
    }
}
