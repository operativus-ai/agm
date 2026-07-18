package com.operativus.agentmanager.integration.finops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.AgentRunEventRepository;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.control.repository.AlertRuleRepository;
import com.operativus.agentmanager.core.entity.AgentRunEventEntity;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.entity.AlertRule;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Cross-tenant isolation pins for the budget-alerts surface that
 *     PRs #666, #667, #668, #669 closed at the unit-test level. These tests run against
 *     a real Postgres + the live Spring context, exercising the full
 *     {@code TenantContextFilter} → {@code AgentContextHolder} → controller → repository
 *     wire path so JWT-bound orgId propagation is verified end-to-end (not just stubbed
 *     via {@code ScopedValue.where} as in the unit tests).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>Probe set:
 * <ul>
 *   <li><b>B3</b> — {@code /api/observability/budget-exceeded-feed} returns only the
 *       caller-tenant's BUDGET_EXCEEDED events. Pre-#666 a regular tenant could spoof
 *       {@code X-Org-Id: <victim-org>} and read the feed; post-#666 the controller
 *       reads {@code AgentContextHolder.getOrgId()} which is JWT-bound, so an orgB
 *       caller sees only orgB rows even with the header set to "org-a".</li>
 *   <li><b>C7</b> — {@code AlertFiredEvent} for orgA dispatches only to orgA's
 *       integrations. Existing {@link com.operativus.agentmanager.integration.alerts.AlertsRuntimeTest}
 *       pins the single-org happy path; this test extends it with a second tenant's
 *       integration that must NOT receive the POST.</li>
 * </ul>
 *
 * <p><b>Why allow-loopback-urls=true</b>: PR #667 wired {@code SsrfGuard} into
 * {@code AlertIntegrationService.attemptDispatch} as defense in depth — the guard
 * runs at every POST. WireMock binds on {@code localhost}, which the production
 * SsrfGuard would reject. {@code @TestPropertySource} flips the allow-loopback flag
 * for this test class only. Production retains the strict (false) default.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
@TestPropertySource(properties = {
        "agentmanager.alerts.ssrf.allow-loopback-urls=true",
        "agentmanager.alerts.retry.max-attempts=2",
        "agentmanager.alerts.retry.base-delay-seconds=1",
        "agentmanager.alerts.retry.max-delay-seconds=2"
})
public class BudgetAlertsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremockA;
    private static WireMockServer wiremockB;

    @Autowired private AgentRunEventRepository runEventRepository;
    @Autowired private AlertRuleRepository ruleRepository;
    @Autowired private AlertIntegrationRepository integrationRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private SchedulerTestSupport scheduler;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @BeforeAll
    static void startWireMocks() {
        wiremockA = new WireMockServer(options().dynamicPort());
        wiremockA.start();
        wiremockB = new WireMockServer(options().dynamicPort());
        wiremockB.start();
    }

    @AfterAll
    static void stopWireMocks() {
        if (wiremockA != null) wiremockA.stop();
        if (wiremockB != null) wiremockB.stop();
    }

    @BeforeEach
    void resetWireMocks() {
        wiremockA.resetAll();
        wiremockB.resetAll();
    }

    /**
     * B3 — cross-tenant feed isolation.
     *
     * <p>Seeds three {@code BUDGET_EXCEEDED} rows into {@code agent_run_events} under
     * {@code org_id="org-a"}. Logs in as a fresh user bound to {@code org-a} and confirms
     * the feed returns all three. Logs in as a user bound to {@code org-b} (with an
     * {@code X-Org-Id: org-a} header still set — the pre-#666 spoof) and confirms the
     * feed returns ZERO, because the controller now reads the JWT-bound orgId from
     * {@code AgentContextHolder} (= "org-b") instead of trusting the header.
     */
    @Test
    void budgetExceededFeed_orgB_jwtCannotReadOrgAEvents_evenWithSpoofedHeader() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Instant t0 = Instant.now().minus(Duration.ofMinutes(10));

        for (int i = 0; i < 3; i++) {
            AgentRunEventEntity e = new AgentRunEventEntity();
            e.setEventType(AgentRunEventType.BUDGET_EXCEEDED);
            e.setRunId("run-orgA-" + tag + "-" + i);
            e.setAgentId("agent-orgA-" + tag);
            e.setOrgId("org-a");
            Map<String, Object> payload = new HashMap<>();
            payload.put("limit", "1.00");
            payload.put("actual", "1.50");
            e.setPayload(payload);
            e.setEventTs(t0.plus(Duration.ofMinutes(i)));
            runEventRepository.save(e);
        }

        Instant since = t0.minus(Duration.ofMinutes(1));
        String sinceParam = "?since=" + since.toString() + "&limit=50";

        // orgA principal: feed surfaces all 3 events.
        HttpHeaders orgAHeaders = registerLoginWithOrg("orgA-feed-reader-" + tag, "org-a");
        ResponseEntity<Map<String, Object>> orgAResp = rest.exchange(
                url("/api/observability/budget-exceeded-feed" + sinceParam),
                HttpMethod.GET, new HttpEntity<>(orgAHeaders), JSON_MAP);
        assertEquals(HttpStatus.OK, orgAResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgAEvents = (List<Map<String, Object>>) orgAResp.getBody().get("events");
        assertEquals(3, orgAEvents.size(),
                "orgA principal must see all 3 BUDGET_EXCEEDED rows seeded under org-a");

        // orgB principal WITH a spoofed X-Org-Id=org-a header: feed must return ZERO.
        // Pre-#666 the controller honored the header and surfaced orgA's rows; post-#666
        // it reads AgentContextHolder.getOrgId() which TenantContextFilter binds from the
        // JWT org_id claim (= "org-b") regardless of the header value.
        HttpHeaders orgBHeaders = registerLoginWithOrg("orgB-spoof-attacker-" + tag, "org-b");
        orgBHeaders.set("X-Org-Id", "org-a");
        ResponseEntity<Map<String, Object>> orgBResp = rest.exchange(
                url("/api/observability/budget-exceeded-feed" + sinceParam),
                HttpMethod.GET, new HttpEntity<>(orgBHeaders), JSON_MAP);
        assertEquals(HttpStatus.OK, orgBResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgBEvents = (List<Map<String, Object>>) orgBResp.getBody().get("events");
        assertEquals(0, orgBEvents.size(),
                "orgB JWT with spoofed X-Org-Id=org-a header must NOT see orgA's events — "
                        + "post-#666 the controller ignores the header and reads the JWT-bound orgId");
    }

    /**
     * C7 — cross-tenant dispatch isolation.
     *
     * <p>Two enabled integrations: orgA → wiremockA, orgB → wiremockB. One AlertRule
     * seeded under {@code org_id="org-a"} that breaches a gauge under {@code org-a}.
     * Tick the alerting scheduler. Within 10 seconds, wiremockA must receive exactly one
     * POST; wiremockB must receive zero. Pins that
     * {@link com.operativus.agentmanager.control.service.AlertIntegrationService#onAlertFired}
     * scopes dispatch to {@code findByOrgIdAndEnabledTrue(event.getOrgId())} — an event
     * for orgA never reaches orgB's webhook even when both integrations are enabled.
     */
    @Test
    void alertFiredEventForOrgA_dispatchesOnlyToOrgAIntegration_notOrgB() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPathA = "/alerts/sink-A-" + tag;
        String hookPathB = "/alerts/sink-B-" + tag;
        wiremockA.stubFor(post(urlPathEqualTo(hookPathA))
                .willReturn(aResponse().withStatus(200).withBody("ok-A")));
        wiremockB.stubFor(post(urlPathEqualTo(hookPathB))
                .willReturn(aResponse().withStatus(200).withBody("ok-B")));

        // orgA integration → wiremockA
        AlertIntegration integA = new AlertIntegration();
        integA.setId("int-A-" + tag);
        integA.setName("orgA webhook");
        integA.setType("WEBHOOK");
        integA.setEndpointUrl("http://localhost:" + wiremockA.port() + hookPathA);
        integA.setEnabled(true);
        integA.setOrgId("org-a");
        integrationRepository.save(integA);

        // orgB integration → wiremockB
        AlertIntegration integB = new AlertIntegration();
        integB.setId("int-B-" + tag);
        integB.setName("orgB webhook");
        integB.setType("WEBHOOK");
        integB.setEndpointUrl("http://localhost:" + wiremockB.port() + hookPathB);
        integB.setEnabled(true);
        integB.setOrgId("org-b");
        integrationRepository.save(integB);

        // orgA rule that breaches a gauge.
        String metricName = "budget.alerts.crosstenant." + tag;
        AtomicInteger holder = new AtomicInteger(99);
        meterRegistry.gauge(metricName, holder);
        String ruleId = "rule-orgA-" + tag;
        AlertRule rule = new AlertRule();
        rule.setId(ruleId);
        rule.setName("orgA breach");
        rule.setMetricName(metricName);
        rule.setCondition("GT");
        rule.setThreshold(10.0);
        rule.setSeverity("WARNING");
        rule.setEnabled(true);
        rule.setWindowSeconds(60);
        rule.setOrgId("org-a");
        ruleRepository.save(rule);

        scheduler.tickAlerting();

        // onAlertFired is @Async — wait for the orgA POST to land.
        Awaitility.await("orgA webhook receives the AlertFiredEvent POST")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> wiremockA.getAllServeEvents().stream()
                        .anyMatch(e -> hookPathA.equals(e.getRequest().getUrl())));

        long orgAPosts = wiremockA.getAllServeEvents().stream()
                .filter(e -> hookPathA.equals(e.getRequest().getUrl()))
                .count();
        assertEquals(1L, orgAPosts,
                "orgA integration must receive exactly one AlertFiredEvent POST for an org-a rule breach");

        long orgBPosts = wiremockB.getAllServeEvents().stream()
                .filter(e -> hookPathB.equals(e.getRequest().getUrl()))
                .count();
        assertEquals(0L, orgBPosts,
                "orgB integration must receive ZERO POSTs — AlertIntegrationService.onAlertFired "
                        + "scopes dispatch to findByOrgIdAndEnabledTrue(event.getOrgId())");

        // Sanity: the rule actually fired (and the event carried org-a).
        String firedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM alert_events WHERE rule_id = ?",
                String.class, ruleId);
        assertEquals("org-a", firedOrgId,
                "the AlertEvent row must inherit the rule's org_id so dispatch can scope correctly");
        assertFalse(orgAPosts == orgBPosts,
                "if both sides receive the same count, cross-tenant scoping is broken");
    }

    /**
     * C2 — HMAC signature header pin.
     *
     * <p>When an {@link AlertIntegration} has {@code signingSecret} populated,
     * {@link com.operativus.agentmanager.control.service.AlertIntegrationService#buildSignedRequest}
     * adds two headers to every outbound POST:
     * <ul>
     *   <li>{@code X-AGM-Timestamp}: epoch milliseconds at sign time</li>
     *   <li>{@code X-AGM-Signature}: {@code sha256=<lowercase-hex>} of HMAC-SHA256(secret, ts + "." + body)</li>
     * </ul>
     * Pins both headers are present, well-formed, and the signature is verifiable.
     */
    @Test
    void alertFiredEventWithSigningSecret_emitsHmacHeadersOnWebhookPost() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/hmac-sink-" + tag;
        wiremockA.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok-hmac")));

        String secret = "test-shared-secret-" + tag;
        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-hmac-" + tag);
        integ.setName("hmac webhook");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://localhost:" + wiremockA.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integ.setSigningSecret(secret);
        integrationRepository.save(integ);

        String metricName = "budget.alerts.hmac." + tag;
        AtomicInteger holder = new AtomicInteger(99);
        meterRegistry.gauge(metricName, holder);
        String ruleId = "rule-hmac-" + tag;
        AlertRule rule = new AlertRule();
        rule.setId(ruleId);
        rule.setName("hmac breach");
        rule.setMetricName(metricName);
        rule.setCondition("GT");
        rule.setThreshold(10.0);
        rule.setSeverity("WARNING");
        rule.setEnabled(true);
        rule.setWindowSeconds(60);
        rule.setOrgId("org-a");
        ruleRepository.save(rule);

        scheduler.tickAlerting();

        Awaitility.await("hmac webhook POST visible")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> wiremockA.getAllServeEvents().stream()
                        .anyMatch(e -> hookPath.equals(e.getRequest().getUrl())));

        var serveEvent = wiremockA.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow();

        String timestampHeader = serveEvent.getRequest().getHeader("X-AGM-Timestamp");
        String signatureHeader = serveEvent.getRequest().getHeader("X-AGM-Signature");
        assertTrue(timestampHeader != null && !timestampHeader.isBlank(),
                "X-AGM-Timestamp must be present when signingSecret is configured");
        assertTrue(signatureHeader != null && signatureHeader.startsWith("sha256="),
                "X-AGM-Signature must start with 'sha256='; got: " + signatureHeader);

        // Reconstruct canonical string and verify HMAC matches.
        String body = serveEvent.getRequest().getBodyAsString();
        String canonical = timestampHeader + "." + body;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + java.util.HexFormat.of().formatHex(
                mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, signatureHeader,
                "receiver must be able to recompute the HMAC from (timestamp + '.' + body) with the shared secret");
    }

    /**
     * C6 — SSRF rejection at the controller layer.
     *
     * <p>{@code POST /api/alerts/integrations} with an {@code endpointUrl} pointing to
     * the cloud-metadata service (or any RFC-1918 / loopback address — depending on
     * the {@code allowLoopbackUrls} flag) must be rejected with 400 before the row
     * lands in the database. Pins the G1 wiring at the create-time site.
     *
     * <p>Note: this test class overrides {@code allow-loopback-urls=true}, so 127.0.0.1
     * and RFC-1918 are accepted. The always-on rejections (link-local 169.254/16 for
     * cloud metadata and 0.0.0.0) still fire — that's what we test here.
     */
    @Test
    void createIntegration_endpointUrlAtCloudMetadataAddress_isRejectedWith400() {
        HttpHeaders auth = registerLoginWithOrg(
                "ssrf-attacker-" + UUID.randomUUID().toString().substring(0, 8), "org-a");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "ssrf probe");
        body.put("type", "WEBHOOK");
        body.put("endpointUrl", "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
        body.put("enabled", true);

        long beforeCount = jdbc.queryForObject(
                "SELECT count(*) FROM alert_integrations WHERE endpoint_url LIKE '%169.254.169.254%'",
                Long.class);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/alerts/integrations"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "SsrfGuard must reject endpointUrl pointing at cloud-metadata 169.254/16 with 400 "
                        + "BEFORE the row is persisted (production AlertIntegrationService.createIntegration)");

        long afterCount = jdbc.queryForObject(
                "SELECT count(*) FROM alert_integrations WHERE endpoint_url LIKE '%169.254.169.254%'",
                Long.class);
        assertEquals(beforeCount, afterCount,
                "rejected request must NOT touch alert_integrations — rejection precedes repository.save");
    }

    /**
     * D2 — webhook retry happy path.
     *
     * <p>{@code AlertIntegrationService.attemptDispatch} is single-shot — on failure it
     * persists retry state on the {@link AlertIntegration} row ({@code retryCount},
     * {@code pendingPayload}, {@code nextRetryAt}) and exits. The {@code tickAlertRetry()}
     * scheduler later calls {@code redispatchPendingFailures()} which re-fires the POST.
     *
     * <p>This test scripts WireMock with a Scenario state machine: first POST returns 500,
     * subsequent POSTs return 200. Assertions: initial fail leaves retry state on the row,
     * scheduler tick clears it (markSuccess), webhook saw exactly 2 POSTs (initial + retry).
     */
    @Test
    void webhookRetry_500ThenSuccess_succeedsOnRetry() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/retry-success-" + tag;
        wiremockA.stubFor(post(urlPathEqualTo(hookPath))
                .inScenario("retry-success-" + tag)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("flake"))
                .willSetStateTo("fail-once"));
        wiremockA.stubFor(post(urlPathEqualTo(hookPath))
                .inScenario("retry-success-" + tag)
                .whenScenarioStateIs("fail-once")
                .willReturn(aResponse().withStatus(200).withBody("ok-retried")));

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-retry-success-" + tag);
        integ.setName("retry-success webhook");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://localhost:" + wiremockA.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integrationRepository.save(integ);

        String eventId = "evt-retry-success-" + tag;
        eventPublisher.publishEvent(new AlertFiredEvent(this, "RULE_RETRY_SUCCESS",
                eventId, "WARNING", "first attempt will 500, retry will 200", "org-a"));

        // After the initial @Async dispatch lands a 500, the row must carry retry state.
        Awaitility.await("initial 500 lands retry state on integration row")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getRetryCount() == 1 && row.getNextRetryAt() != null)
                        .orElse(false));

        // Spin the backoff clock forward and tick the retry scheduler.
        jdbc.update("UPDATE alert_integrations SET next_retry_at = now() - interval '1 second' WHERE id = ?",
                integ.getId());
        scheduler.tickAlertRetry();

        // After the retry: markSuccess clears retry state and a second POST is visible.
        Awaitility.await("retry POST succeeds and clears retry state")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getRetryCount() == 0 && row.getNextRetryAt() == null
                                && row.getLastError() == null && row.getPendingPayload() == null)
                        .orElse(false));

        long totalPosts = wiremockA.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count();
        assertEquals(2L, totalPosts,
                "webhook must receive exactly 2 POSTs: the initial 500 + the retry 200");
    }

    /**
     * D3 — webhook retry give-up after max attempts.
     *
     * <p>With {@code agentmanager.alerts.retry.max-attempts=2} (set on this class's
     * {@code @TestPropertySource}), a persistent-500 receiver must exhaust retries after
     * 2 total attempts: the initial dispatch + 1 retry. The integration row then holds
     * {@code retryCount=2}, {@code nextRetryAt=null}, and {@code lastError} populated —
     * the "max retry attempts" log fires (not asserted, but the state machine pins the
     * give-up condition).
     *
     * <p><b>Gap:</b> No DLQ / outbox exists for permanently failed dispatches. The pending
     * payload sits on the row indefinitely until the next AlertFiredEvent overwrites it.
     * If that becomes a problem, a separate {@code alert_dispatch_failures} table or a
     * "give up" terminal flag would close the gap.
     */
    @Test
    void webhookRetry_persistent500_givesUpAfterMaxAttempts() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/retry-giveup-" + tag;
        wiremockA.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500).withBody("permanent")));

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-retry-giveup-" + tag);
        integ.setName("retry-giveup webhook");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://localhost:" + wiremockA.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integrationRepository.save(integ);

        eventPublisher.publishEvent(new AlertFiredEvent(this, "RULE_RETRY_GIVEUP",
                "evt-giveup-" + tag, "WARNING", "this receiver is broken", "org-a"));

        // Initial dispatch fails → retryCount=1.
        Awaitility.await("initial 500 lands retry state on integration row")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getRetryCount() == 1 && row.getNextRetryAt() != null)
                        .orElse(false));

        // Force the retry scheduler to act now; the retry will also 500, hitting max-attempts=2.
        jdbc.update("UPDATE alert_integrations SET next_retry_at = now() - interval '1 second' WHERE id = ?",
                integ.getId());
        scheduler.tickAlertRetry();

        Awaitility.await("retry exhaustion: retryCount=2, nextRetryAt=null")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integ.getId())
                        .map(row -> row.getRetryCount() == 2 && row.getNextRetryAt() == null
                                && row.getLastError() != null)
                        .orElse(false));

        long totalPosts = wiremockA.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count();
        assertEquals(2L, totalPosts,
                "webhook must receive exactly max-attempts=2 POSTs total — no further retries after give-up");

        AlertIntegration row = integrationRepository.findById(integ.getId()).orElseThrow();
        assertTrue(row.getLastError().contains("HTTP 500") || row.getLastError().contains("500"),
                "lastError must record the receiver's 500 (got: " + row.getLastError() + ")");
        assertNotNull(row.getPendingPayload(),
                "pendingPayload remains on the row after give-up — GAP: no DLQ / outbox table for permanently failed dispatches");
    }

    /**
     * D4 — Approval SLA breach → AlertFiredEvent → webhook dispatch.
     *
     * <p>{@code ApprovalService.checkApprovalSla} is a {@code @Scheduled} job that
     * finds {@code PENDING} approvals older than 20 hours and publishes
     * {@code AlertFiredEvent(ruleId="APPROVAL_SLA_BREACH", severity="WARNING")} per row.
     * This test seeds an approval with {@code created_at = now() - 21 hours}, ticks the
     * scheduler, and asserts a WireMock POST contains the rule id and approval id.
     */
    @Test
    void approvalSlaBreach_emitsAlertFiredEvent_andDispatchesWebhook() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/sla-breach-" + tag;
        wiremockA.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok-sla")));

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-sla-" + tag);
        integ.setName("sla webhook");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://localhost:" + wiremockA.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId("org-a");
        integrationRepository.save(integ);

        // FK chain: approval → agent_session → agent → model. Seed bottom-up.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentId = "agent-sla-" + tag;
        String sessionId = "session-sla-" + tag;
        String approvalId = "approval-sla-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, 'SLA probe agent', 'gpt-4o-mini', true, now(), now())
                """, agentId);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, "sla-user", "org-a", agentId);
        jdbc.update("""
                INSERT INTO approvals (id, run_id, session_id, agent_id, status, tool_name,
                                       tool_arguments, requested_by, contextual_message,
                                       decision_tier, org_id, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'PENDING', 'sla-probe-tool',
                        ?::jsonb, ?, ?, 'TIER_3_DESTRUCTIVE', ?,
                        now() - interval '21 hours', now() - interval '21 hours', 0)
                """,
                approvalId, "run-sla-" + tag, sessionId, agentId,
                "{}", "sla-requester", "21h-old pending approval", "org-a");

        scheduler.tickApprovalSlaCheck();

        Awaitility.await("SLA breach webhook POST visible")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> wiremockA.getAllServeEvents().stream()
                        .anyMatch(e -> hookPath.equals(e.getRequest().getUrl())));

        var serveEvent = wiremockA.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow();
        String body = serveEvent.getRequest().getBodyAsString();
        assertTrue(body.contains("APPROVAL_SLA_BREACH"),
                "webhook body must carry the SLA-breach ruleId; got: " + body);
        assertTrue(body.contains(approvalId),
                "webhook body must carry the breached approval id as eventId (for correlation); got: " + body);
    }
}
