package com.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Configurable alerting rule that monitors a named metric and fires when a threshold is breached.
 */
@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "condition", nullable = false, length = 50)
    private String condition; // GT, GTE, LT, LTE, EQ

    @Column(nullable = false)
    private double threshold;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds = 60;

    @Column(nullable = false, length = 50)
    private String severity = "WARNING"; // INFO, WARNING, CRITICAL

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "notification_channel")
    private String notificationChannel;

    /**
     * Tenant scope for this rule. Stamped from {@code AgentContextHolder.getOrgId()} at create
     * time; body-injected values are ignored via {@link JsonProperty.Access#READ_ONLY}.
     */
    @Column(name = "org_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String orgId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AlertRule() {}

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNotificationChannel() { return notificationChannel; }
    public void setNotificationChannel(String notificationChannel) { this.notificationChannel = notificationChannel; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
