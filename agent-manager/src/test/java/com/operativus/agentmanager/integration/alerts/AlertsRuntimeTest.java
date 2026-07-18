package com.operativus.agentmanager.integration.alerts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.AlertEventRepository;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.control.repository.AlertRuleRepository;
import com.operativus.agentmanager.core.entity.AlertEvent;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.entity.AlertRule;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the alerting surface — the
 *   {@code alert_rules}, {@code alert_events}, and {@code alert_integrations} tables,
 *   the rule-evaluation path on
 *   {@link com.operativus.agentmanager.control.service.AlertingService#evaluateRules},
 *   the Spring-event bus hop from {@code AlertingService} →
 *   {@link com.operativus.agentmanager.core.event.AlertFiredEvent} →
 *   {@link com.operativus.agentmanager.control.service.AlertIntegrationService#onAlertFired},
 *   and the outbound webhook dispatch contract. Pins the five positive flows matrix §25
 *   currently implements and the two feature gaps (retry on dispatch failure, cooldown to
 *   prevent flapping) that are aspirational.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §25 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T047.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link com.operativus.agentmanager.control.service.AlertingService#evaluateRules} is
 *     {@code @Scheduled(fixedRateString = "${agentmanager.scheduler.alerting-ms:60000}")}.
 *     The test profile pushes the interval to 24h (see {@code application-test.properties})
 *     so the only way a tick fires is via {@link SchedulerTestSupport#tickAlerting()}, which
 *     invokes the method directly on the bean instance — per spec decision 4.4.
 *   - Rule-to-metric coupling: {@code evaluateRules} reads the live {@link MeterRegistry}
 *     via {@code meterRegistry.find(name).gauge()}. Seeding a gauge that the rule targets
 *     is the way to drive a breach synchronously in a test; we register an
 *     {@link AtomicInteger}-backed gauge, flip its value, and tick the scheduler.
 *   - {@link com.operativus.agentmanager.control.service.AlertIntegrationService#onAlertFired}
 *     is {@code @Async @EventListener}. With {@code @EnableAsync} wired on
 *     {@code AgentmanagerApplication}, dispatch runs on a background executor — so
 *     WireMock verification must poll via {@link Awaitility} rather than asserting
 *     immediately after the tick.
 *   - Webhook dispatch has exponential-backoff retry (§25.3): on failure the row persists
 *     {@code retry_count}, {@code last_failure_at}, {@code last_error}, {@code next_retry_at},
 *     and {@code pending_payload}. A {@code @Scheduled} sweep
 *     ({@link com.operativus.agentmanager.control.service.AlertIntegrationService#redispatchPendingFailures})
 *     walks rows whose {@code next_retry_at} has elapsed and re-POSTs; on success the retry
 *     state clears. The sync path asserts the full loop in
 *     {@link #integrationRetryOnInvalidWebhookUrl_persistsRetryStateAndRedispatches}.
 *   - Cooldown guard: {@link com.operativus.agentmanager.control.service.AlertingService#evaluateRules}
 *     consults {@code AlertRule.windowSeconds} before each fire — if any event exists for the
 *     rule within the window, the breach is suppressed. Covered by
 *     {@link #cooldownPreventsDuplicateAlertsWithinWindow}.
 *   - {@code alert_events.rule_id} is a FK to {@code alert_rules(id)} with default
 *     RESTRICT (009-agent-versioning-and-rbac.sql:30) — there is no
 *     {@code ON DELETE CASCADE} or {@code ON DELETE SET NULL} clause. Consequence: once
 *     an {@link AlertEvent} exists for a rule, deleting that rule via
 *     {@code DELETE /api/alerts/rules/{id}} triggers an FK constraint violation
 *     surfacing as a 500. Case (e) therefore exercises the matrix §25 case 5 subset that
 *     currently works: delete a rule BEFORE any event fires, then prove subsequent ticks
 *     do not produce events. The "historical events preserved after rule delete" half is
 *     not pinned as a separate test to avoid encoding the FK-500 accident as a
 *     contract — document-only here until the schema is softened to
 *     {@code ON DELETE CASCADE} or {@code SET NULL}.
 *   - Neither {@link com.operativus.agentmanager.control.controller.AlertingController}
 *     nor {@link com.operativus.agentmanager.control.controller.AlertIntegrationController}
 *     has {@code @PreAuthorize}. RBAC is not enumerated as a matrix §25 case and the
 *     broader RBAC gap is pinned in
 *     {@link com.operativus.agentmanager.integration.admin.UserAdminRuntimeTest}, so we do
 *     not duplicate it here.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
public class AlertsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremock;

    @Autowired private AlertRuleRepository ruleRepository;
    @Autowired private AlertEventRepository eventRepository;
    @Autowired private AlertIntegrationRepository integrationRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private SchedulerTestSupport scheduler;

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
    void resetFixtures() {
        wiremock.resetAll();
    }

    // §25 — Case 1: Rule CRUD round-trips through the REST surface and persists.
    // POST /api/alerts/rules creates a row (the controller returns 201 CREATED), GET
    // /api/alerts/rules returns a list including the newly-created rule, GET by id
    // returns the exact row.
    @Test
    void createAlertRuleViaRestPersistsAndListsAndGetsById() {
        HttpHeaders auth = authenticatedHeaders("alerts-crud-actor");

        // No client-supplied id — AlertRuleRequest has no slot for it, so any "id"
        // field in the POST body is dropped at deserialization. The server generates
        // the UUID in AlertingService.createRule and returns it in the response.
        // (See #1018 — mass-assignment fix prevents victim-row hijack via id-merge.)
        Map<String, Object> body = ruleBody(null, "Hot CPU", "system.cpu.usage", "GT", 0.90, true);

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "POST /api/alerts/rules must return 201 (AlertingController.createRule sets HttpStatus.CREATED)");
        String ruleId = (String) created.getBody().get("id");
        assertNotNull(ruleId,
                "response must carry the server-generated id");
        assertFalse(ruleId.isBlank(),
                "server-generated id must be non-blank");

        Long dbCount = jdbc.queryForObject(
                "SELECT count(*) FROM alert_rules WHERE id = ?", Long.class, ruleId);
        assertEquals(1L, dbCount, "alert_rules row must persist on POST");

        ResponseEntity<List<Map<String, Object>>> listing = rest.exchange(
                url("/api/alerts/rules"), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, listing.getStatusCode());
        assertTrue(listing.getBody().stream().anyMatch(r -> ruleId.equals(r.get("id"))),
                "GET /api/alerts/rules must surface the newly-created rule");

        ResponseEntity<Map<String, Object>> byId = rest.exchange(
                url("/api/alerts/rules/" + ruleId), HttpMethod.GET,
                new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, byId.getStatusCode());
        assertEquals("Hot CPU", byId.getBody().get("name"),
                "round-trip must preserve name");
        assertEquals("system.cpu.usage", byId.getBody().get("metricName"),
                "round-trip must preserve metricName");
        assertEquals("GT", byId.getBody().get("condition"));
        assertEquals(0.90, ((Number) byId.getBody().get("threshold")).doubleValue(), 0.0001);
        assertEquals(true, byId.getBody().get("enabled"));
    }

    // §25 — Case 2 (rule side): a rule whose metric breaches the threshold on the next
    // evaluation tick writes exactly one AlertEvent row. We register a gauge backed by an
    // AtomicInteger, create a rule that targets that metric with condition GT and threshold
    // 10, set the gauge to 42, tick the scheduler, and assert one AlertEvent row exists with
    // the rule_id, severity, and metric_value we expect.
    @Test
    void ruleBreachedOnSchedulerTick_writesExactlyOneAlertEventRow() {
        String metricName = "t047.rule.tick." + shortUuid();
        String ruleId = "rule-" + shortUuid();

        // Register a live gauge that reads from a mutable holder.
        AtomicInteger holder = new AtomicInteger(42);
        meterRegistry.gauge(metricName, holder);

        AlertRule rule = buildRule(ruleId, "Tick breach", metricName, "GT", 10.0, "CRITICAL", true);
        ruleRepository.save(rule);

        assertEquals(0, eventRepository.count(),
                "precondition: no alert_events row must exist before the tick");

        scheduler.tickAlerting();

        List<AlertEvent> events = eventRepository.findByRuleIdOrderByFiredAtDesc(ruleId);
        assertEquals(1, events.size(),
                "metric=42 against GT 10 must fire exactly one AlertEvent row on one tick");
        AlertEvent fired = events.get(0);
        assertEquals(42.0, fired.getMetricValue(), 0.001,
                "alert_events.metric_value must match the gauge reading at evaluation time");
        assertEquals("CRITICAL", fired.getSeverity(),
                "fired event must inherit severity from the rule");
        assertFalse(fired.isAcknowledged(),
                "new events are born unacknowledged");
        assertNotNull(fired.getMessage(),
                "AlertingService formats a human-readable message for every breach");
    }

    // §25 — Case 2 (dispatch side) + Case 6 (event-bus hop). AlertingService publishes an
    // AlertFiredEvent via ApplicationEventPublisher; AlertIntegrationService.onAlertFired is
    // an @Async @EventListener that POSTs the payload to every enabled integration.
    // Stub a WireMock endpoint, register it as an enabled integration, drive a breach,
    // tick the scheduler, and poll WireMock until the POST arrives (async dispatch).
    @Test
    void alertFiredEventDispatchedViaEventBus_postsPayloadToEnabledIntegration() {
        String hookPath = "/alerts/sink-" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        AlertIntegration integration = new AlertIntegration();
        integration.setId("int-" + shortUuid());
        integration.setName("T047 webhook sink");
        integration.setType("WEBHOOK");
        integration.setEndpointUrl("http://localhost:" + wiremock.port() + hookPath);
        integration.setEnabled(true);
        integration.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        integrationRepository.save(integration);

        String metricName = "t047.dispatch." + shortUuid();
        String ruleId = "rule-" + shortUuid();
        AtomicInteger holder = new AtomicInteger(99);
        meterRegistry.gauge(metricName, holder);
        AlertRule rule = buildRule(ruleId, "Dispatch breach", metricName, "GT", 10.0, "WARNING", true);
        ruleRepository.save(rule);

        scheduler.tickAlerting();

        // onAlertFired is async — the tick returns before dispatch completes.
        Awaitility.await("webhook POST visible on WireMock")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> wiremock.getAllServeEvents().stream()
                        .anyMatch(e -> hookPath.equals(e.getRequest().getUrl())));

        var events = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .toList();
        assertEquals(1, events.size(),
                "AlertIntegrationService.onAlertFired must POST exactly once to an enabled integration per AlertFiredEvent");

        String body = events.get(0).getRequest().getBodyAsString();
        assertTrue(body.contains("\"ruleId\":\"" + ruleId + "\""),
                "webhook payload must carry the ruleId; got: " + body);
        assertTrue(body.contains("\"severity\":\"WARNING\""),
                "webhook payload must carry the severity; got: " + body);
        assertTrue(body.contains("\"eventId\":\""),
                "webhook payload must carry the eventId; got: " + body);
    }

    // §25 — Case 4: Acknowledging an event flips alert_events.acknowledged to true and
    // removes the row from the active listing (GET /api/alerts/events, which uses
    // findByAcknowledgedFalseOrderByFiredAtDesc). Seed an unacknowledged event, POST to
    // /api/alerts/events/{id}/acknowledge, re-fetch via GET and via the repository.
    @Test
    void acknowledgeEvent_flipsAcknowledgedAndRemovesFromActiveListing() {
        HttpHeaders auth = authenticatedHeaders("alerts-ack-actor");

        String ruleId = "rule-" + shortUuid();
        AlertRule rule = buildRule(ruleId, "To be acked", "irrelevant.metric", "GT", 0, "INFO", true);
        ruleRepository.save(rule);

        String eventId = "evt-" + shortUuid();
        AlertEvent event = new AlertEvent(eventId, ruleId, 1.0, "pre-ack", "INFO");
        event.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        eventRepository.save(event);

        // Active listing sees the unacked event.
        ResponseEntity<Map<String, Object>> before = rest.exchange(
                url("/api/alerts/events"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, before.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentBefore = (List<Map<String, Object>>) before.getBody().get("content");
        assertTrue(contentBefore.stream().anyMatch(e -> eventId.equals(e.get("id"))),
                "unacknowledged event must appear in GET /api/alerts/events before acknowledge");

        ResponseEntity<Void> ack = rest.exchange(
                url("/api/alerts/events/" + eventId + "/acknowledge"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ack.getStatusCode(),
                "POST /api/alerts/events/{id}/acknowledge returns 204 (AlertingController returns ResponseEntity.noContent())");

        AlertEvent reloaded = eventRepository.findById(eventId).orElseThrow();
        assertTrue(reloaded.isAcknowledged(),
                "acknowledge must flip alert_events.acknowledged to true");

        ResponseEntity<Map<String, Object>> after = rest.exchange(
                url("/api/alerts/events"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentAfter = (List<Map<String, Object>>) after.getBody().get("content");
        assertFalse(contentAfter.stream().anyMatch(e -> eventId.equals(e.get("id"))),
                "acknowledged event must drop out of the active listing (findByAcknowledgedFalseOrderByFiredAtDesc)");
    }

    // §25 — Case 5 (subset that works today): deleting a rule before any event fires
    // succeeds, and subsequent scheduler ticks produce no AlertEvent rows (because
    // evaluateRules() only walks findByEnabledTrue() and the row is gone).
    //
    // The matrix also asks for "historical events remain after rule delete". That half
    // is NOT a separate assertion here because alert_events.rule_id has a FK to
    // alert_rules(id) with default RESTRICT — deleting a rule with surviving events
    // currently triggers an FK violation surfacing as 500, which is an incidental
    // behavior rather than a stable contract. See class Javadoc.
    @Test
    void deleteRuleBeforeAnyEvents_futureTicksProduceNoEvents() {
        HttpHeaders auth = authenticatedHeaders("alerts-delete-actor");

        String metricName = "t047.delete." + shortUuid();
        String ruleId = "rule-" + shortUuid();
        AtomicInteger holder = new AtomicInteger(50);
        meterRegistry.gauge(metricName, holder);
        AlertRule rule = buildRule(ruleId, "To be deleted", metricName, "GT", 10.0, "WARNING", true);
        ruleRepository.save(rule);

        ResponseEntity<Void> del = rest.exchange(
                url("/api/alerts/rules/" + ruleId), HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode(),
                "DELETE /api/alerts/rules/{id} returns 204 when no alert_events reference the rule");
        assertTrue(ruleRepository.findById(ruleId).isEmpty(),
                "delete must remove the alert_rules row");

        scheduler.tickAlerting();

        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc(ruleId).size(),
                "after the rule is gone, a tick — even with the metric still breaching — must not produce events for the deleted rule (evaluateRules walks findByEnabledTrue only)");
    }

    // §25 — Case 3: an integration whose webhook fails is retried with exponential backoff.
    // Failure persists retry_count, last_failure_at, last_error, next_retry_at, and
    // pending_payload on the alert_integrations row. The scheduled sweep
    // (AlertIntegrationService.redispatchPendingFailures) re-POSTs once next_retry_at has
    // elapsed; on success the retry state clears. Max-attempts is bounded by
    // agentmanager.alerts.retry.max-attempts.
    @Test
    void integrationRetryOnInvalidWebhookUrl_persistsRetryStateAndRedispatches() {
        String hookPath = "/alerts/flaky-" + shortUuid();
        // First dispatch fails with 500; second (on retry sweep) succeeds with 200.
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        String integrationId = "int-" + shortUuid();
        AlertIntegration integration = new AlertIntegration();
        integration.setId(integrationId);
        integration.setName("T047 flaky webhook");
        integration.setType("WEBHOOK");
        integration.setEndpointUrl("http://localhost:" + wiremock.port() + hookPath);
        integration.setEnabled(true);
        integration.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        integrationRepository.save(integration);

        String metricName = "t047.retry." + shortUuid();
        String ruleId = "rule-" + shortUuid();
        AtomicInteger holder = new AtomicInteger(99);
        meterRegistry.gauge(metricName, holder);
        AlertRule rule = buildRule(ruleId, "Retry breach", metricName, "GT", 10.0, "WARNING", true);
        ruleRepository.save(rule);

        scheduler.tickAlerting();

        // First dispatch is async — wait until retry state appears on the row.
        Awaitility.await("retry_count persists after first failure")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    AlertIntegration reloaded = integrationRepository.findById(integrationId).orElseThrow();
                    return reloaded.getRetryCount() >= 1
                            && reloaded.getPendingPayload() != null
                            && reloaded.getLastFailureAt() != null;
                });

        AlertIntegration afterFailure = integrationRepository.findById(integrationId).orElseThrow();
        assertEquals(1, afterFailure.getRetryCount(),
                "first failure on a fresh event must set retry_count=1");
        assertNotNull(afterFailure.getLastError(),
                "last_error must carry the failure reason (HTTP code or exception)");
        assertNotNull(afterFailure.getNextRetryAt(),
                "next_retry_at must be scheduled while retry_count < max_attempts");
        assertTrue(afterFailure.getPendingPayload().contains("\"ruleId\":\"" + ruleId + "\""),
                "pending_payload must carry the failed event's JSON payload for replay");

        // WireMock saw exactly one POST so far — the initial failed attempt.
        assertEquals(1, wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count(),
                "initial dispatch must POST exactly once before retry");

        // Swap the stub to succeed and force the sweep to consider the row due by
        // bumping next_retry_at into the past (avoids sleeping through the backoff window).
        wiremock.resetAll();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        jdbc.update("UPDATE alert_integrations SET next_retry_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
                integrationId);

        scheduler.tickAlertRetry();

        // Sweep replays synchronously (direct bean invocation); retry state must clear.
        Awaitility.await("retry state clears after successful redispatch")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    AlertIntegration reloaded = integrationRepository.findById(integrationId).orElseThrow();
                    return reloaded.getRetryCount() == 0
                            && reloaded.getPendingPayload() == null
                            && reloaded.getNextRetryAt() == null;
                });

        AlertIntegration afterSuccess = integrationRepository.findById(integrationId).orElseThrow();
        assertEquals(0, afterSuccess.getRetryCount(),
                "successful redispatch must reset retry_count to 0");
        assertNull(afterSuccess.getLastError(),
                "successful redispatch must clear last_error");
        assertNull(afterSuccess.getPendingPayload(),
                "successful redispatch must clear pending_payload");

        // WireMock saw exactly one POST on the retry sweep (after resetAll).
        assertEquals(1, wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count(),
                "retry sweep must POST exactly once on redispatch");
    }

    // §25 — Case 7: once an alert fires for a rule, subsequent breaches within the rule's
    // windowSeconds cooldown do NOT produce additional events. AlertingService.evaluateRules
    // calls AlertEventRepository.existsByRuleIdAndFiredAtAfter(ruleId, now - windowSeconds)
    // before saving a new event. After the window lapses (simulated here by backdating the
    // first event's fired_at via JDBC), the next tick fires normally.
    @Test
    void cooldownPreventsDuplicateAlertsWithinWindow() {
        String metricName = "t047.cooldown." + shortUuid();
        String ruleId = "rule-" + shortUuid();

        AtomicInteger holder = new AtomicInteger(42);
        meterRegistry.gauge(metricName, holder);

        AlertRule rule = buildRule(ruleId, "Cooldown breach", metricName, "GT", 10.0, "WARNING", true);
        rule.setWindowSeconds(60);
        ruleRepository.save(rule);

        // First tick: metric breaches, window is empty → one AlertEvent written.
        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc(ruleId).size(),
                "first breach must fire one event (window is empty on first tick)");

        // Second tick: metric still breaches, but the just-written event sits inside the
        // 60s cooldown window → no new event.
        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc(ruleId).size(),
                "second tick inside the cooldown window must NOT produce a new event (§25.7 cooldown gate)");

        // Push the first event's fired_at past the cooldown (to -120s) so the next tick
        // finds the window empty again.
        int updated = jdbc.update(
                "UPDATE alert_events SET fired_at = ? WHERE rule_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(120)),
                ruleId);
        assertEquals(1, updated, "precondition: exactly one event row must be backdated");

        // Third tick: cooldown has lapsed → a second event fires.
        scheduler.tickAlerting();
        assertEquals(2, eventRepository.findByRuleIdOrderByFiredAtDesc(ruleId).size(),
                "breach after the cooldown window has lapsed must produce a fresh event");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        // AlertingController rule writes (create/update/delete) + acknowledge are hasRole('ADMIN')
        // since #1018/#1020. A ROLE_USER caller gets 403, which cascades into the dispatch/retry
        // assertions (no rule/integration ever gets created). Grant ROLE_ADMIN to exercise the contract.
        return authenticateAs(username, username + "@test.local", "pass-alerts-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private AlertRule buildRule(String id, String name, String metricName, String condition,
                                 double threshold, String severity, boolean enabled) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName(name);
        rule.setMetricName(metricName);
        rule.setCondition(condition);
        rule.setThreshold(threshold);
        rule.setWindowSeconds(60);
        rule.setSeverity(severity);
        rule.setEnabled(enabled);
        // Tests that bypass AlertingService.createRule (direct repository.save) must stamp
        // orgId themselves; otherwise org-scoped reads from the service will miss the row.
        // Caller's effective orgId is DEFAULT_SYSTEM_ORG since authenticateAs registers
        // users with org_id=null which the service-side callerOrgId() helper falls back to.
        rule.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        return rule;
    }

    private Map<String, Object> ruleBody(String id, String name, String metricName,
                                          String condition, double threshold, boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        // null id ⇒ omit the field entirely so the request matches the production
        // contract on AlertRuleRequest (no id slot). When id is non-null the helper
        // still includes it — used by the mass-assignment regression test that
        // verifies the body's id is dropped at deserialization.
        if (id != null) {
            body.put("id", id);
        }
        body.put("name", name);
        body.put("metricName", metricName);
        body.put("condition", condition);
        body.put("threshold", threshold);
        body.put("windowSeconds", 60);
        body.put("severity", "WARNING");
        body.put("enabled", enabled);
        return body;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
