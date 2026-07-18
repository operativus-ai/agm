package com.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Outbound notification channel that receives webhook payloads when an alert fires.
 * Retry state (retry_count, last_failure_at, next_retry_at, pending_payload,
 * pending_event_id, last_error) carries a single in-flight failed delivery so the
 * AlertIntegrationService sweep can redispatch it with exponential backoff.
 * Latest-wins: a fresh AlertFiredEvent overwrites a still-pending retry.
 */
@Entity
@Table(name = "alert_integrations")
public class AlertIntegration {

    @Id
    private String id;

    /** Tenant owner. Stamped at create time by AlertIntegrationService; never updated. */
    @Column(name = "org_id")
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 50)
    private String type; // WEBHOOK, SLACK, PAGERDUTY

    /**
     * For type=WEBHOOK / SLACK: full HTTPS URL to POST the alert payload to.
     * For type=PAGERDUTY: the 32-character integration / routing key from PagerDuty
     * (the service hardcodes the events-v2 enqueue URL — only the routing key varies).
     */
    @Column(name = "endpoint_url", nullable = false, columnDefinition = "TEXT")
    private String endpointUrl;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "pending_payload", columnDefinition = "TEXT")
    private String pendingPayload;

    @Column(name = "pending_event_id", length = 36)
    private String pendingEventId;

    /**
     * Optional HMAC-SHA256 signing secret for outbound webhook deliveries. When set,
     * dispatch adds {@code X-AGM-Signature} (sha256 hex of {@code timestamp + "." + body})
     * and {@code X-AGM-Timestamp} headers so the receiver can authenticate origin and
     * reject replays. Encrypted at rest by {@link OutboundApiKeyConverter}. Write-only
     * on JSON: clients can set or clear it but never read the cleartext back.
     */
    @Convert(converter = OutboundApiKeyConverter.class)
    @Column(name = "signing_secret", columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String signingSecret;

    public AlertIntegration() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(LocalDateTime lastFailureAt) { this.lastFailureAt = lastFailureAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getPendingPayload() { return pendingPayload; }
    public void setPendingPayload(String pendingPayload) { this.pendingPayload = pendingPayload; }
    public String getPendingEventId() { return pendingEventId; }
    public void setPendingEventId(String pendingEventId) { this.pendingEventId = pendingEventId; }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

    /** Read-only flag so the UI can show "secret configured" without exposing the cleartext. */
    @Transient
    @JsonProperty(value = "signingSecretSet", access = JsonProperty.Access.READ_ONLY)
    public boolean isSigningSecretSet() {
        return signingSecret != null && !signingSecret.isBlank();
    }
}
