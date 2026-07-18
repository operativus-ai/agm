package com.operativus.agentmanager.integration.alerts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import com.operativus.agentmanager.core.model.AlertIntegrationTestResult;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box pins on the {@code AlertIntegrationService} dispatch
 *   surface — the {@code @Async @EventListener onAlertFired} path, the
 *   {@code attemptDispatch} HTTP-outcome matrix, and the integration filter logic.
 *   Drives the listener directly via {@link ApplicationEventPublisher} so tests isolate
 *   the dispatcher from the rule evaluator (already covered by
 *   {@code AlertRuleEvaluatorRuntimeTest}).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}
 *   plus {@code wiremock.resetAll()} in {@code @BeforeEach}).
 *
 * Plan: {@code .claude/plans/alert-integration-runtime-coverage-2026-05-16.md}.
 */
// Loopback alert-webhook URLs are allowed by the harness default
// (agentmanager.alerts.ssrf.allow-loopback-urls=true in application-test.properties); a per-class
// @TestPropertySource here would only fork a dedicated Spring context for no behavioral change.
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
public class AlertIntegrationDispatchRuntimeTest extends BaseIntegrationTest {

    private static WireMockServer wiremock;

    @Autowired private AlertIntegrationRepository integrationRepository;
    @Autowired private ApplicationEventPublisher publisher;
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

    // ─── A: onAlertFired listener edges ───

    // A1 — Null orgId on the event must short-circuit before any HTTP call. This is the
    //      cross-tenant safety net at AlertIntegrationService:200-205.
    @Test
    void listenerWithNullOrgId_skipsDispatchEntirely() {
        String hookPath = "/ai1.a1.null-org/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200)));
        integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-a1", "evt-a1-" + shortUuid(), "WARNING", "null-org test",
                /* orgId = */ null));

        sleepShort(); // Give the @Async listener a beat to (not) act.
        assertEquals(0, countPostsTo(hookPath),
                "null orgId must short-circuit onAlertFired before any HTTP call");
    }

    // A2 — Event for an org with no enabled integrations: empty-loop branch at line 207-208.
    @Test
    void listenerWithNoEnabledIntegrationsForOrg_noDispatch() {
        // Deliberately seed nothing for "lonely-org".
        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-a2", "evt-a2-" + shortUuid(), "INFO", "lonely org",
                "lonely-org-a2"));

        sleepShort();
        assertEquals(0, wiremock.getAllServeEvents().size(),
                "no enabled integrations for the event's org → no HTTP traffic at all");
    }

    // A3 — Three enabled integrations in the same org, single event → three POSTs (one per
    //      integration). Pins the for-loop at lines 208-212.
    @Test
    void listenerFansOutToAllEnabledIntegrationsInOrg() {
        String orgId = "org-a3-" + shortUuid();
        String hook1 = "/ai1.a3.fan/" + shortUuid();
        String hook2 = "/ai1.a3.fan/" + shortUuid();
        String hook3 = "/ai1.a3.fan/" + shortUuid();
        for (String p : new String[]{hook1, hook2, hook3}) {
            wiremock.stubFor(post(urlPathEqualTo(p)).willReturn(aResponse().withStatus(200)));
        }
        integrationRepository.save(integration(orgId, hook1, true));
        integrationRepository.save(integration(orgId, hook2, true));
        integrationRepository.save(integration(orgId, hook3, true));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-a3", "evt-a3-" + shortUuid(), "WARNING", "fan-out", orgId));

        Awaitility.await("3 fan-out POSTs visible on WireMock")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> countPostsTo(hook1) >= 1
                        && countPostsTo(hook2) >= 1
                        && countPostsTo(hook3) >= 1);

        assertEquals(1, countPostsTo(hook1), "integration 1 must receive exactly one POST");
        assertEquals(1, countPostsTo(hook2), "integration 2 must receive exactly one POST");
        assertEquals(1, countPostsTo(hook3), "integration 3 must receive exactly one POST");
    }

    // ─── B: attemptDispatch outcome matrix ───

    // B1 — 4xx response → markFailure with "HTTP 422" and enters retry loop. Documents that
    //      the current implementation retries on ALL non-2xx including 4xx.
    @Test
    void dispatch4xx_marksFailureAndEntersRetryLoop() {
        String hookPath = "/ai1.b1.4xx/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(422).withBody("unprocessable")));

        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-b1", "evt-b1-" + shortUuid(), "WARNING", "4xx test",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        AlertIntegration reloaded = awaitFailureRecorded(integration.getId());
        assertEquals(1, reloaded.getRetryCount(),
                "first 4xx failure must set retry_count=1");
        assertTrue(reloaded.getLastError() != null && reloaded.getLastError().contains("HTTP 422"),
                "lastError must capture the HTTP status; got: " + reloaded.getLastError());
        assertNotNull(reloaded.getNextRetryAt(),
                "4xx must schedule a next retry (current behavior retries any non-2xx)");
    }

    // B2 — Network failure (unreachable port) → markFailure with exception class name in
    //      error message. Tests the try/catch at AlertIntegrationService:255-258.
    @Test
    void dispatchNetworkFailure_marksFailureWithExceptionClassName() {
        // Port 1 is the TCP echo port — nothing binds in our test container.
        // The dispatch attempt will throw ConnectException/HttpConnectTimeoutException.
        AlertIntegration integration = integrationRepository.save(integrationAt(
                TenantConstants.DEFAULT_SYSTEM_ORG, "http://127.0.0.1:1/never", true));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-b2", "evt-b2-" + shortUuid(), "WARNING", "net fail",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        AlertIntegration reloaded = awaitFailureRecorded(integration.getId());
        assertEquals(1, reloaded.getRetryCount(),
                "first network failure must set retry_count=1");
        String err = reloaded.getLastError();
        assertNotNull(err, "lastError must be populated");
        // The exception type depends on JDK + OS specifics; ConnectException is the most
        // common on Linux/macOS for a connection-refused, HttpConnectTimeoutException
        // shows up under JDK's HttpClient timeout path. Accept either.
        assertTrue(err.startsWith("ConnectException")
                        || err.startsWith("HttpConnectTimeoutException")
                        || err.startsWith("IOException"),
                "lastError must start with the exception class name; got: " + err);
    }

    // B3 — 3xx redirect → markFailure (HTTP client does NOT auto-follow because the
    //      service uses HttpResponse.BodyHandlers.discarding() and JDK HttpClient's
    //      default redirect policy is NORMAL, which follows GETs but not POSTs).
    @Test
    void dispatch3xxRedirect_marksFailureAsNonSuccess() {
        String hookPath = "/ai1.b3.3xx/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(301)
                        .withHeader("Location", "http://nowhere.invalid/")));

        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-b3", "evt-b3-" + shortUuid(), "WARNING", "3xx test",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        AlertIntegration reloaded = awaitFailureRecorded(integration.getId());
        assertEquals(1, reloaded.getRetryCount(),
                "3xx redirect must be treated as failure (POST is not auto-followed)");
        assertTrue(reloaded.getLastError() != null && reloaded.getLastError().contains("HTTP 301"),
                "lastError must contain the redirect status code; got: " + reloaded.getLastError());
    }

    // ─── E: disabled-integration resilience ───

    // E1 — Disabled integration is NOT dispatched even when AlertFiredEvent fires for its
    //      org. Proves findByOrgIdAndEnabledTrue filter at line 209.
    @Test
    void disabledIntegration_isNotDispatchedOnAlertFiredEvent() {
        String hookPath = "/ai1.e1.disabled/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath)).willReturn(aResponse().withStatus(200)));
        integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, /* enabled = */ false));

        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-e1", "evt-e1-" + shortUuid(), "WARNING", "disabled test",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        sleepShort();
        assertEquals(0, countPostsTo(hookPath),
                "disabled integration must not receive POSTs — findByOrgIdAndEnabledTrue filter");
    }

    // E2 — Disabled integration mid-retry: even though the row has retry_count > 0 and
    //      a past next_retry_at and pending_payload, the sweep ignores it because
    //      AlertIntegrationRepository.findPendingRetryCandidates filters enabled = true.
    @Test
    void disabledIntegrationMidRetry_sweepIgnoresIt() {
        String hookPath = "/ai1.e2.disabled-retry/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath)).willReturn(aResponse().withStatus(200)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, /* enabled = */ false);
        integration.setRetryCount(2);
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusSeconds(60));
        integration.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        integration.setPendingPayload("{\"eventId\":\"prior\"}");
        integration.setPendingEventId("prior");
        integrationRepository.save(integration);

        scheduler.tickAlertRetry();

        sleepShort();
        assertEquals(0, countPostsTo(hookPath),
                "disabled integration with pending retry state must be skipped by the sweep");
        AlertIntegration reloaded = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(2, reloaded.getRetryCount(),
                "retry_count must be unchanged — the sweep never touched the row");
        assertNotNull(reloaded.getNextRetryAt(),
                "next_retry_at must be unchanged — sweep skipped the row entirely");
    }

    // ─── C: retry state machine (markFailure + computeBackoff) ───

    // C1 — Backoff math: with config base=2s, max=300s, exponential doubling means
    //      attempt 1 -> 2s, attempt 2 -> 4s. We assert the gap between lastFailureAt and
    //      nextRetryAt after the first failure (~2s) and after the first retry (~4s).
    @Test
    void backoffMath_exponentialDoublingFromBaseDelay() {
        String hookPath = "/ai2.c1.backoff/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500)));

        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        // First failure (isRetry=false, nextCount=1) → backoff = base * 2^0 = 2s.
        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-c1", "evt-c1-" + shortUuid(), "WARNING", "backoff fresh",
                TenantConstants.DEFAULT_SYSTEM_ORG));
        AlertIntegration after1 = awaitRetryCount(integration.getId(), 1);
        long gap1Sec = Duration.between(after1.getLastFailureAt(), after1.getNextRetryAt())
                .getSeconds();
        assertTrue(gap1Sec >= 1 && gap1Sec <= 3,
                "attempt 1 backoff must be ~2s (base * 2^0); got " + gap1Sec + "s");

        // Backdate nextRetryAt to past so the sweep picks it up.
        jdbc.update("UPDATE alert_integrations SET next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)),
                integration.getId());

        // First retry (isRetry=true, nextCount=2) → backoff = base * 2^1 = 4s.
        scheduler.tickAlertRetry();
        AlertIntegration after2 = awaitRetryCount(integration.getId(), 2);
        long gap2Sec = Duration.between(after2.getLastFailureAt(), after2.getNextRetryAt())
                .getSeconds();
        assertTrue(gap2Sec >= 3 && gap2Sec <= 5,
                "attempt 2 backoff must be ~4s (base * 2^1); got " + gap2Sec + "s");
    }

    // C2 — Fresh AlertFiredEvent on integration with stale pending state from a prior event
    //      resets retry_count to 1 and overwrites pending_payload + pending_event_id. Pins
    //      the `else { nextCount = 1; }` branch at AlertIntegrationService:390-391.
    @Test
    void freshAlertFiredEvent_overwritesStalePendingState() {
        String hookPath = "/ai2.c2.overwrite/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true);
        integration.setRetryCount(3);
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusMinutes(5));
        integration.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        integration.setPendingPayload("{\"eventId\":\"OLD_EVENT\",\"ruleId\":\"OLD_RULE\"}");
        integration.setPendingEventId("OLD_EVENT");
        integrationRepository.save(integration);

        String newEventId = "evt-c2-new-" + shortUuid();
        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-c2-new", newEventId, "WARNING", "fresh event",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        // Wait for the fresh dispatch to fail and overwrite state.
        Awaitility.await("fresh event overwrites stale state")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integration.getId())
                        .map(r -> newEventId.equals(r.getPendingEventId()))
                        .orElse(false));

        AlertIntegration reloaded = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(1, reloaded.getRetryCount(),
                "fresh AlertFiredEvent must RESET retry_count to 1, not increment from 3");
        assertEquals(newEventId, reloaded.getPendingEventId(),
                "pending_event_id must be overwritten with the new event");
        assertTrue(reloaded.getPendingPayload() != null
                        && !reloaded.getPendingPayload().contains("OLD_EVENT"),
                "pending_payload must be overwritten; got: " + reloaded.getPendingPayload());
    }

    // C3 — At max attempts: retry_count = maxAttempts (5 by default), next_retry_at = null,
    //      pending_payload preserved. Pins the terminal-state branch in markFailure:399-403.
    @Test
    void retryReachesMaxAttempts_terminalStateRecorded() {
        String hookPath = "/ai2.c3.max/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500)));

        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        // Fresh failure → retry_count = 1.
        publisher.publishEvent(new AlertFiredEvent(this,
                "rule-c3", "evt-c3-" + shortUuid(), "CRITICAL", "max-attempts test",
                TenantConstants.DEFAULT_SYSTEM_ORG));
        awaitRetryCount(integration.getId(), 1);

        // Drive 4 sweep ticks; backdate next_retry_at each time so we don't wait for backoff.
        for (int expectedCount = 2; expectedCount <= 5; expectedCount++) {
            jdbc.update("UPDATE alert_integrations SET next_retry_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)),
                    integration.getId());
            scheduler.tickAlertRetry();
            awaitRetryCount(integration.getId(), expectedCount);
        }

        AlertIntegration terminal = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(5, terminal.getRetryCount(),
                "retry_count must reach maxAttempts (5)");
        assertNull(terminal.getNextRetryAt(),
                "at maxAttempts, next_retry_at must be null — give-up signal");
        assertNotNull(terminal.getPendingPayload(),
                "pending_payload is preserved at terminal state (forensic; no DLQ today)");
    }

    // ─── D: redispatchPendingFailures sweep edges ───

    // D1 — Row with next_retry_at in the future is NOT picked up by the sweep. Pins the
    //      `next_retry_at <= :now` clause in findPendingRetryCandidates.
    @Test
    void sweepIgnoresRowsWithFutureNextRetryAt() {
        String hookPath = "/ai2.d1.future/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true);
        integration.setRetryCount(2);
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusSeconds(30));
        integration.setNextRetryAt(LocalDateTime.now().plusHours(1));
        integration.setPendingPayload("{\"eventId\":\"future\"}");
        integration.setPendingEventId("future");
        integrationRepository.save(integration);

        scheduler.tickAlertRetry();

        sleepShort();
        assertEquals(0, countPostsTo(hookPath),
                "row with future next_retry_at must NOT be re-dispatched");
        AlertIntegration reloaded = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(2, reloaded.getRetryCount(),
                "row state must be untouched — sweep skipped it");
    }

    // D2 — Row with pending_payload IS NULL is skipped by the sweep. Pins the
    //      `pending_payload IS NOT NULL` clause in findPendingRetryCandidates.
    @Test
    void sweepIgnoresRowsWithNullPendingPayload() {
        String hookPath = "/ai2.d2.null-payload/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true);
        integration.setRetryCount(2);
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusSeconds(60));
        integration.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        integration.setPendingPayload(null);
        integration.setPendingEventId(null);
        integrationRepository.save(integration);

        scheduler.tickAlertRetry();

        sleepShort();
        assertEquals(0, countPostsTo(hookPath),
                "row with null pending_payload must NOT be re-dispatched");
    }

    // D3 — Terminal row (next_retry_at = null) at maxAttempts is ignored by the sweep.
    //      Pins the `nextRetryAt IS NOT NULL` clause in findPendingRetryCandidates.
    @Test
    void sweepIgnoresRowsAtMaxAttempts() {
        String hookPath = "/ai2.d3.terminal/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true);
        integration.setRetryCount(5); // matches default maxAttempts
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusMinutes(10));
        integration.setNextRetryAt(null);
        integration.setPendingPayload("{\"eventId\":\"terminal\"}");
        integration.setPendingEventId("terminal");
        integrationRepository.save(integration);

        scheduler.tickAlertRetry();

        sleepShort();
        assertEquals(0, countPostsTo(hookPath),
                "terminal row (retry_count=maxAttempts, next_retry_at=null) must NOT be re-dispatched");
    }

    // D4 — Two consecutive ticks on the same eligible row each dispatch exactly once.
    //      After the first tick, next_retry_at is advanced into the future; only after
    //      backdating it again does the second tick fire. Proves the sweep is not idempotent
    //      within a single backoff window.
    @Test
    void sweepDispatchesExactlyOncePerEligibleWindow() {
        String hookPath = "/ai2.d4.exact-once/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(500)));

        AlertIntegration integration = integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true);
        integration.setRetryCount(1);
        integration.setLastError("HTTP 500");
        integration.setLastFailureAt(LocalDateTime.now().minusSeconds(30));
        integration.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        integration.setPendingPayload("{\"eventId\":\"window-test\"}");
        integration.setPendingEventId("window-test");
        integrationRepository.save(integration);

        scheduler.tickAlertRetry();
        Awaitility.await("first sweep dispatches")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> countPostsTo(hookPath) == 1);
        AlertIntegration after1 = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(2, after1.getRetryCount(), "first tick incremented retry_count");
        assertTrue(after1.getNextRetryAt().isAfter(LocalDateTime.now()),
                "first tick advanced next_retry_at into the future");

        // Second immediate tick: row is no longer eligible (future next_retry_at).
        scheduler.tickAlertRetry();
        sleepShort();
        assertEquals(1, countPostsTo(hookPath),
                "second immediate tick must NOT re-dispatch — row is in backoff window");

        // Backdate so the next sweep picks it up again.
        jdbc.update("UPDATE alert_integrations SET next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)),
                integration.getId());
        scheduler.tickAlertRetry();
        Awaitility.await("third sweep dispatches after backdating")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> countPostsTo(hookPath) == 2);
        AlertIntegration after3 = integrationRepository.findById(integration.getId()).orElseThrow();
        assertEquals(3, after3.getRetryCount(),
                "after backdating + tick, retry_count must increment to 3");
    }

    // ─── F: testFire operator surface (POST /api/alerts/integrations/{id}/test) ───

    // F1 — testFire on valid integration with 2xx upstream → delivered=true, statusCode=200,
    //      payload arrives at WireMock carrying the "test":true marker.
    @Test
    void testFire_happyPath_deliveredTrueAndPayloadCarriesTestMarker() {
        HttpHeaders auth = adminHeaders("ai3-f1-actor");

        String hookPath = "/ai3.f1.happy/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        ResponseEntity<AlertIntegrationTestResult> resp = rest.exchange(
                url("/api/alerts/integrations/" + integration.getId() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), AlertIntegrationTestResult.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "controller always returns 200");
        AlertIntegrationTestResult body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.delivered(), "delivered must be true for upstream 2xx");
        assertEquals(200, body.statusCode());
        assertTrue(body.message() != null && body.message().contains("HTTP 200"),
                "message must convey HTTP status; got: " + body.message());

        // The synthetic payload must have arrived at WireMock with the test marker.
        Awaitility.await("test-fire payload at WireMock")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> countPostsTo(hookPath) == 1);
        String payload = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow().getRequest().getBodyAsString();
        assertTrue(payload.contains("\"test\":true"),
                "test-fire payload must carry \"test\":true; got: " + payload);
        assertTrue(payload.contains("\"ruleId\":\"agm.test-fire\""),
                "test-fire payload must carry the synthetic ruleId; got: " + payload);
    }

    // F2 — testFire with upstream 5xx → delivered=false, statusCode echoes 502.
    @Test
    void testFire_upstream5xx_deliveredFalseAndStatusEchoed() {
        HttpHeaders auth = adminHeaders("ai3-f2-actor");

        String hookPath = "/ai3.f2.5xx/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(502)));
        AlertIntegration integration = integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        ResponseEntity<AlertIntegrationTestResult> resp = rest.exchange(
                url("/api/alerts/integrations/" + integration.getId() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), AlertIntegrationTestResult.class);

        AlertIntegrationTestResult body = resp.getBody();
        assertNotNull(body);
        assertEquals(false, body.delivered(), "upstream 5xx must report delivered=false");
        assertEquals(502, body.statusCode(), "statusCode must echo the upstream code");
        assertTrue(body.message() != null && body.message().contains("HTTP 502"),
                "message must contain the HTTP status; got: " + body.message());
    }

    // F3 — testFire with SSRF-violating URL (cloud metadata is rejected regardless of the
    //      allowLoopbackUrls override on this test class) → delivered=false, statusCode=0,
    //      message starts with "Blocked by SSRF guard", and NO HTTP call leaves the JVM.
    @Test
    void testFire_ssrfBlocked_failClosedWithNoHttpAttempt() {
        HttpHeaders auth = adminHeaders("ai3-f3-actor");

        // Bypass createIntegration's pre-flight SSRF guard by writing directly to the repo.
        AlertIntegration integration = integrationAt(TenantConstants.DEFAULT_SYSTEM_ORG,
                "http://169.254.169.254/latest/meta-data/", true);
        integrationRepository.save(integration);

        int postsBefore = wiremock.getAllServeEvents().size();

        ResponseEntity<AlertIntegrationTestResult> resp = rest.exchange(
                url("/api/alerts/integrations/" + integration.getId() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), AlertIntegrationTestResult.class);

        AlertIntegrationTestResult body = resp.getBody();
        assertNotNull(body);
        assertEquals(false, body.delivered(), "SSRF rejection must report delivered=false");
        assertEquals(0, body.statusCode(),
                "statusCode=0 indicates the request never reached the network");
        assertTrue(body.message() != null && body.message().startsWith("Blocked by SSRF guard"),
                "message must begin with the SSRF reason; got: " + body.message());

        // No HTTP call should have left the JVM.
        assertEquals(postsBefore, wiremock.getAllServeEvents().size(),
                "fail-closed must mean zero outbound HTTP requests");
    }

    // F4 — testFire with network failure → delivered=false, statusCode=0, message carries
    //      the exception class name + detail.
    @Test
    void testFire_networkFailure_deliveredFalseWithExceptionClass() {
        HttpHeaders auth = adminHeaders("ai3-f4-actor");

        AlertIntegration integration = integrationRepository.save(integrationAt(
                TenantConstants.DEFAULT_SYSTEM_ORG, "http://127.0.0.1:1/never", true));

        ResponseEntity<AlertIntegrationTestResult> resp = rest.exchange(
                url("/api/alerts/integrations/" + integration.getId() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), AlertIntegrationTestResult.class);

        AlertIntegrationTestResult body = resp.getBody();
        assertNotNull(body);
        assertEquals(false, body.delivered(), "network failure must report delivered=false");
        assertEquals(0, body.statusCode(),
                "statusCode=0 indicates the request never reached a response");
        assertNotNull(body.message());
        assertTrue(body.message().startsWith("ConnectException")
                        || body.message().startsWith("HttpConnectTimeoutException")
                        || body.message().startsWith("IOException"),
                "message must begin with the exception class name; got: " + body.message());
    }

    // F5 — testFire on missing integration id → 404. The service's getIntegration throws
    //      ResourceNotFoundException, which the global handler maps to 404.
    @Test
    void testFire_missingIntegration_returns404() {
        HttpHeaders auth = adminHeaders("ai3-f5-actor");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/alerts/integrations/does-not-exist-" + shortUuid() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "missing integration id must surface as 404");
    }

    // ─── G: end-to-end webhook payload contract via real dispatch ───

    // G1 — Real (non-test) AlertFiredEvent dispatch produces a flat-shape webhook payload
    //      {eventId, ruleId, severity, message} with NO "test":true marker. This is the
    //      generic webhook contract surface that downstream consumers parse.
    //
    // G2 (PagerDuty payload via real dispatch) is NOT pinned here: PagerDuty integrations
    // resolve to the hard-coded events.pagerduty.com URL via endpointUriFor, which
    // bypasses WireMock entirely. PagerDuty payload shape + routing are already covered
    // by the unit-level AlertIntegrationPagerDutyTest, so a real-dispatch end-to-end
    // would add infrastructure (DNS mocking) without new contract coverage.
    @Test
    void realAlertFiredEvent_emitsFlatWebhookPayloadWithoutTestMarker() {
        String hookPath = "/ai3.g1.real/" + shortUuid();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200)));
        integrationRepository.save(integration(
                TenantConstants.DEFAULT_SYSTEM_ORG, hookPath, true));

        String eventId = "evt-g1-" + shortUuid();
        String ruleId = "rule-g1-" + shortUuid();
        publisher.publishEvent(new AlertFiredEvent(this,
                ruleId, eventId, "CRITICAL", "production outage detected",
                TenantConstants.DEFAULT_SYSTEM_ORG));

        Awaitility.await("real webhook payload arrives")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> countPostsTo(hookPath) == 1);

        String payload = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow().getRequest().getBodyAsString();

        assertTrue(payload.contains("\"eventId\":\"" + eventId + "\""),
                "real payload must carry the eventId; got: " + payload);
        assertTrue(payload.contains("\"ruleId\":\"" + ruleId + "\""),
                "real payload must carry the ruleId; got: " + payload);
        assertTrue(payload.contains("\"severity\":\"CRITICAL\""),
                "real payload must carry the severity; got: " + payload);
        assertTrue(payload.contains("\"message\":\"production outage detected\""),
                "real payload must carry the message; got: " + payload);
        assertTrue(!payload.contains("\"test\":true"),
                "real (non-test) payload MUST NOT carry the test marker; got: " + payload);
    }

    // ─── helpers ───

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-ai3-1234",
                java.util.List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private AlertIntegration integration(String orgId, String hookPath, boolean enabled) {
        return integrationAt(orgId, "http://localhost:" + wiremock.port() + hookPath, enabled);
    }

    private AlertIntegration integrationAt(String orgId, String endpointUrl, boolean enabled) {
        AlertIntegration i = new AlertIntegration();
        i.setId("int-" + shortUuid());
        i.setName("ai1-" + i.getId());
        i.setType("WEBHOOK");
        i.setEndpointUrl(endpointUrl);
        i.setEnabled(enabled);
        i.setOrgId(orgId);
        return i;
    }

    private long countPostsTo(String path) {
        return wiremock.getAllServeEvents().stream()
                .filter(e -> path.equals(e.getRequest().getUrl()))
                .count();
    }

    private AlertIntegration awaitFailureRecorded(String integrationId) {
        return awaitRetryCount(integrationId, 1);
    }

    private AlertIntegration awaitRetryCount(String integrationId, int expected) {
        Awaitility.await("integration retry_count = " + expected)
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> integrationRepository.findById(integrationId)
                        .map(r -> r.getRetryCount() == expected)
                        .orElse(false));
        return integrationRepository.findById(integrationId).orElseThrow();
    }

    private static void sleepShort() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
