package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.AlertIntegrationTestResult;
import com.operativus.agentmanager.core.security.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: CRUD for AlertIntegration configs and webhook dispatch on AlertFiredEvent,
 * with exponential-backoff retry state persisted on the integration row and a scheduled sweep
 * that redispatches pending failures until max-attempts is reached.
 * State: Stateless (all retry state lives in alert_integrations).
 */
@Service
public class AlertIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(AlertIntegrationService.class);

    private final AlertIntegrationRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int maxAttempts;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final boolean allowLoopbackUrls;

    public AlertIntegrationService(
            AlertIntegrationRepository repository,
            @Value("${agentmanager.alerts.retry.max-attempts:5}") int maxAttempts,
            @Value("${agentmanager.alerts.retry.base-delay-seconds:2}") long baseDelaySeconds,
            @Value("${agentmanager.alerts.retry.max-delay-seconds:300}") long maxDelaySeconds,
            @Value("${agentmanager.alerts.ssrf.allow-loopback-urls:false}") boolean allowLoopbackUrls) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.allowLoopbackUrls = allowLoopbackUrls;
    }

    /**
     * Reject operator-supplied webhook URLs that target internal infrastructure
     * (cloud metadata, RFC-1918, loopback) before persisting or POSTing.
     * <p>PAGERDUTY integrations store a routing key in {@code endpointUrl}, NOT a URL —
     * the events-v2 enqueue URL is hardcoded. So we only run the guard against
     * WEBHOOK and SLACK types. The loopback flag is property-driven so test
     * profiles can run WireMock on localhost.
     */
    private void rejectIfSsrf(AlertIntegration integration) {
        if ("PAGERDUTY".equalsIgnoreCase(integration.getType())) {
            return;
        }
        String error = SsrfGuard.validate(integration.getEndpointUrl(), allowLoopbackUrls);
        if (error != null) {
            log.warn("AlertIntegration endpoint rejected — SSRF guard: {} (url={})",
                    error, integration.getEndpointUrl());
            throw new IllegalArgumentException("AlertIntegration endpoint rejected by SSRF guard: " + error);
        }
    }

    public List<AlertIntegration> listIntegrations() {
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("listIntegrations called without resolvable orgId — returning empty list");
            return List.of();
        }
        return repository.findByOrgId(orgId);
    }

    public AlertIntegration getIntegration(String id) {
        String orgId = resolveCallerOrgId();
        if (orgId == null) {
            log.warn("getIntegration called without resolvable orgId — returning 404");
            throw new ResourceNotFoundException("AlertIntegration", id);
        }
        return repository.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("AlertIntegration", id));
    }

    @Transactional
    public AlertIntegration createIntegration(AlertIntegration integration) {
        if (integration.getId() == null || integration.getId().isBlank()) {
            integration.setId(UUID.randomUUID().toString());
        }
        String orgId = resolveCallerOrgId();
        if (orgId != null) {
            integration.setOrgId(orgId);
        }
        rejectIfSsrf(integration);
        return repository.save(integration);
    }

    @Transactional
    public AlertIntegration updateIntegration(String id, AlertIntegration update) {
        AlertIntegration existing = getIntegration(id);
        existing.setName(update.getName());
        existing.setType(update.getType());
        existing.setEndpointUrl(update.getEndpointUrl());
        existing.setEnabled(update.isEnabled());
        rejectIfSsrf(existing);
        // Signing-secret update semantics: a null inbound value means "do not touch"
        // (caller did not send the field; the read path never exposes it, so absent is
        // the common case). An empty string clears the secret. Any non-empty value
        // replaces it.
        if (update.getSigningSecret() != null) {
            existing.setSigningSecret(update.getSigningSecret().isEmpty() ? null : update.getSigningSecret());
        }
        return repository.save(existing);
    }

    @Transactional
    public void deleteIntegration(String id) {
        String orgId = resolveCallerOrgId();
        if (orgId == null || !repository.existsByIdAndOrgId(id, orgId)) {
            log.warn("deleteIntegration refused: integration {} not owned by caller org or orgId unresolvable", id);
            return;
        }
        repository.deleteById(id);
    }

    /**
     * @summary §4 P5 T040 — synchronous test-fire of a single integration. Operator-driven
     *     diagnostic that posts a synthetic AlertFiredEvent payload to the integration's
     *     endpoint and returns the outcome inline so the UI can render success/error without
     *     waiting for the next real alert.
     * @logic Loads the integration (404 if missing), builds a stable test-marker payload
     *     ({@code "test": true}, {@code ruleId: "agm.test-fire"}, {@code severity: "INFO"}),
     *     and POSTs synchronously. Does NOT use {@link #attemptDispatch} because that would
     *     mutate the integration's retry/success counters — operator tests should never
     *     pollute the production stats. Failures return a structured result rather than
     *     throwing so the controller can return 200 with a body the UI can read.
     */
    public AlertIntegrationTestResult testFire(String integrationId) {
        AlertIntegration integration = getIntegration(integrationId);

        // Operator-fired test path — fail closed before sending so the UI surfaces the
        // SSRF rejection inline rather than a generic HTTP error.
        if (!"PAGERDUTY".equalsIgnoreCase(integration.getType())) {
            String ssrfError = SsrfGuard.validate(integration.getEndpointUrl(), allowLoopbackUrls);
            if (ssrfError != null) {
                log.warn("AlertIntegration test-fire blocked — SSRF guard: {} (id={} url={})",
                        ssrfError, integrationId, integration.getEndpointUrl());
                return new AlertIntegrationTestResult(integrationId, false, 0,
                        "Blocked by SSRF guard: " + ssrfError);
            }
        }

        String testEventId = "test-" + UUID.randomUUID();
        String payload = buildPayload(integration, testEventId, "agm.test-fire", "INFO",
                "Test alert from AGM. If you see this, your integration is working.", true);

        try {
            HttpRequest request = buildSignedRequest(integration, payload);
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            boolean ok = code >= 200 && code < 300;
            log.info("AlertIntegration test-fire: id={} type={} status={}", integrationId,
                    integration.getType(), code);
            return new AlertIntegrationTestResult(integrationId, ok, code,
                    ok ? "Delivered (HTTP " + code + ")" : "Endpoint responded HTTP " + code);
        } catch (Exception e) {
            log.warn("AlertIntegration test-fire failed: id={} error={}", integrationId, e.getMessage());
            return new AlertIntegrationTestResult(integrationId, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onAlertFired(AlertFiredEvent event) {
        String orgId = event.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            log.warn("onAlertFired received event with no orgId — skipping dispatch to prevent cross-tenant delivery. ruleId={}", event.getRuleId());
            return;
        }
        List<AlertIntegration> targets = repository.findByOrgIdAndEnabledTrue(orgId);
        if (targets.isEmpty()) return;

        for (AlertIntegration integration : targets) {
            String payload = buildPayload(integration, event.getEventId(), event.getRuleId(),
                    event.getSeverity(), event.getMessage(), false);
            attemptDispatch(integration, payload, event.getEventId(), false);
        }
    }

    /**
     * Scheduled sweep for webhooks whose previous dispatch failed. Walks integrations whose
     * next_retry_at has elapsed and whose retry_count is below max_attempts, and re-POSTs
     * the stored pending_payload. Run interval defaults to 10s; pushed to 24h under the
     * test profile so tests drive it via SchedulerTestSupport#tickAlertRetry.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.alerts-retry-ms:10000}")
    public void redispatchPendingFailures() {
        List<AlertIntegration> pending = repository.findPendingRetryCandidates(
                LocalDateTime.now(), maxAttempts);
        for (AlertIntegration integration : pending) {
            String payload = integration.getPendingPayload();
            if (payload == null) continue;
            attemptDispatch(integration, payload, integration.getPendingEventId(), true);
        }
    }

    private void attemptDispatch(AlertIntegration integration, String payload, String eventId, boolean isRetry) {
        // Defense in depth: a row created before this guard existed, or one whose URL was
        // somehow persisted via a path that bypassed create/updateIntegration, would still
        // POST to internal infrastructure. Re-check at dispatch time. Soft-fail (don't throw
        // out of the event-bus / scheduler) — record the rejection as a regular failure so
        // the row hits max-attempts and stops retrying.
        if (!"PAGERDUTY".equalsIgnoreCase(integration.getType())) {
            String ssrfError = SsrfGuard.validate(integration.getEndpointUrl(), allowLoopbackUrls);
            if (ssrfError != null) {
                log.warn("AlertIntegration dispatch blocked — SSRF guard: {} (id={} url={} isRetry={})",
                        ssrfError, integration.getId(), integration.getEndpointUrl(), isRetry);
                markFailure(integration, payload, eventId, "SSRF: " + ssrfError, isRetry);
                return;
            }
        }
        try {
            HttpRequest request = buildSignedRequest(integration, payload);
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                markSuccess(integration);
            } else {
                markFailure(integration, payload, eventId, "HTTP " + response.statusCode(), isRetry);
            }
        } catch (Exception e) {
            markFailure(integration, payload, eventId,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), isRetry);
        }
    }

    /** PagerDuty Events API v2 enqueue endpoint. Same URL for every PagerDuty integration; the
     *  routing key (which is the per-service identity) lives in the JSON body. */
    static final String PAGERDUTY_ENQUEUE_URL = "https://events.pagerduty.com/v2/enqueue";

    /**
     * @summary Resolve the HTTP endpoint URI to POST to for a given integration.
     * @logic For PAGERDUTY: returns the constant events-v2 enqueue URL — the integration's
     *     {@code endpoint_url} stores the routing key, not the URL. For WEBHOOK / SLACK
     *     (and any other type defaulting through): the endpoint URL is the integration's
     *     stored URL.
     */
    static URI endpointUriFor(AlertIntegration integration) {
        if ("PAGERDUTY".equalsIgnoreCase(integration.getType())) {
            return URI.create(PAGERDUTY_ENQUEUE_URL);
        }
        return URI.create(integration.getEndpointUrl());
    }

    /**
     * @summary Build the JSON body posted to the integration, in whichever shape that integration
     *     understands.
     * @logic For PAGERDUTY: emits an Events API v2 trigger payload (routing_key + event_action +
     *     payload{summary,source,severity,class,custom_details}). The integration's
     *     {@code endpoint_url} value is the routing key. AGM severity (INFO/WARNING/CRITICAL)
     *     maps onto PagerDuty's vocabulary (info/warning/critical). For everything else: emits
     *     the same flat AGM-native shape that webhook + Slack receivers already consume.
     */
    static String buildPayload(AlertIntegration integration, String eventId, String ruleId,
                               String severity, String message, boolean isTest) {
        if ("PAGERDUTY".equalsIgnoreCase(integration.getType())) {
            String routingKey = integration.getEndpointUrl();
            String pdSeverity = mapSeverityToPagerDuty(severity);
            String testMarker = isTest ? ",\"test\":true" : "";
            return String.format(
                    "{\"routing_key\":%s,\"event_action\":\"trigger\",\"dedup_key\":%s,"
                            + "\"payload\":{\"summary\":%s,\"source\":\"agent-manager\","
                            + "\"severity\":\"%s\",\"class\":%s,"
                            + "\"custom_details\":{\"eventId\":%s,\"ruleId\":%s%s}}}",
                    jsonString(routingKey), jsonString(eventId),
                    jsonString(message), pdSeverity, jsonString(ruleId),
                    jsonString(eventId), jsonString(ruleId), testMarker);
        }
        String testMarker = isTest ? ",\"test\":true" : "";
        return String.format(
                "{\"eventId\":\"%s\",\"ruleId\":\"%s\",\"severity\":\"%s\",\"message\":%s%s}",
                eventId, ruleId, severity, jsonString(message), testMarker);
    }

    /**
     * AGM severity vocabulary (INFO / WARNING / CRITICAL) → PagerDuty Events API v2 vocabulary
     * (info / warning / critical). Anything unrecognised falls back to {@code warning} so
     * a malformed AGM event still gets routed rather than being silently dropped.
     */
    static String mapSeverityToPagerDuty(String agmSeverity) {
        if (agmSeverity == null) return "warning";
        return switch (agmSeverity.trim().toUpperCase()) {
            case "INFO"     -> "info";
            case "CRITICAL" -> "critical";
            case "ERROR"    -> "error";
            default         -> "warning";
        };
    }

    /**
     * @summary §4 P5 — build the outbound POST, optionally HMAC-signed.
     * @logic Resolves the endpoint URI via {@link #endpointUriFor} so PagerDuty integrations
     *     get the events-v2 enqueue URL while others use their stored URL. If
     *     {@code integration.signingSecret} is set, computes
     *     {@code HMAC-SHA256(secret, timestamp + "." + payload)} and adds:
     *     <ul>
     *       <li>{@code X-AGM-Timestamp}: epoch milliseconds at sign time</li>
     *       <li>{@code X-AGM-Signature}: {@code sha256=<hex>} (lowercase hex)</li>
     *     </ul>
     *     Receivers should reconstruct the canonical string from the same headers
     *     plus the raw body, recompute the HMAC, and compare in constant time.
     *     Reject requests whose timestamp is too old (recommended: ±5 minutes) to
     *     prevent replay. PagerDuty does not authenticate via HMAC — it authenticates
     *     via the routing_key in the JSON body — so the signing-secret is normally
     *     left blank for PAGERDUTY rows. When set, the headers ride along anyway, which
     *     PagerDuty ignores and a transparent forwarding proxy can still verify.
     */
    HttpRequest buildSignedRequest(AlertIntegration integration, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpointUriFor(integration))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(5));
        String secret = integration.getSigningSecret();
        if (secret != null && !secret.isBlank()) {
            String timestamp = Long.toString(Instant.now().toEpochMilli());
            String signature = computeHmacSha256(secret, timestamp + "." + payload);
            builder.header("X-AGM-Timestamp", timestamp)
                   .header("X-AGM-Signature", "sha256=" + signature);
        }
        return builder.build();
    }

    /**
     * @summary HMAC-SHA256 of {@code message} keyed by {@code secret}, rendered as lowercase hex.
     * @logic Package-private + static so unit tests can verify against fixed vectors without
     *     needing to spin up the service.
     */
    static String computeHmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private void markSuccess(AlertIntegration integration) {
        integration.setRetryCount(0);
        integration.setLastFailureAt(null);
        integration.setLastError(null);
        integration.setNextRetryAt(null);
        integration.setPendingPayload(null);
        integration.setPendingEventId(null);
        repository.save(integration);
    }

    private void markFailure(AlertIntegration integration, String payload, String eventId,
                             String errorMessage, boolean isRetry) {
        int nextCount;
        if (isRetry) {
            nextCount = integration.getRetryCount() + 1;
        } else {
            // Fresh AlertFiredEvent — overwrite any stale pending state from a prior event.
            nextCount = 1;
        }
        integration.setRetryCount(nextCount);
        integration.setLastFailureAt(LocalDateTime.now());
        integration.setLastError(truncate(errorMessage, 1000));
        integration.setPendingPayload(payload);
        integration.setPendingEventId(eventId);
        if (nextCount < maxAttempts) {
            integration.setNextRetryAt(LocalDateTime.now().plus(computeBackoff(nextCount)));
        } else {
            integration.setNextRetryAt(null);
            log.warn("Alert integration '{}' reached max retry attempts ({}) for event {}: {}",
                    integration.getName(), maxAttempts, eventId, errorMessage);
        }
        repository.save(integration);
    }

    private Duration computeBackoff(int attempt) {
        long seconds = Math.min(maxDelaySeconds,
                baseDelaySeconds * (long) Math.pow(2, Math.max(0, attempt - 1)));
        return Duration.ofSeconds(seconds);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Resolves the caller's orgId from {@link AgentContextHolder} (agent-run ScopedValue path),
     * falling back to {@code SecurityContextHolder → UserDetailsImpl} (HTTP path). Returns
     * {@code null} when neither context is bound — callers must treat that as a hard refusal
     * (return empty / no-op), never as "match all orgs".
     */
    private String resolveCallerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null && !orgId.isBlank()) return orgId;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getOrgId();
        }
        return null;
    }
}
