package com.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records a fired alert event when a metric breaches an AlertRule threshold.
 */
@Entity
@Table(name = "alert_events")
public class AlertEvent {

    @Id
    private String id;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, length = 50)
    private String severity;

    @Column(nullable = false)
    private boolean acknowledged = false;

    @Column(name = "fired_at", nullable = false)
    private LocalDateTime firedAt = LocalDateTime.now();

    /**
     * Tenant scope for this event. Stamped from the parent rule's orgId when the event
     * is fired by AlertingService.evaluateRules(); body-injected values are ignored.
     */
    @Column(name = "org_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String orgId;

    public AlertEvent() {}

    public AlertEvent(String id, String ruleId, double metricValue, String message, String severity) {
        this.id = id;
        this.ruleId = ruleId;
        this.metricValue = metricValue;
        this.message = message;
        this.severity = severity;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public double getMetricValue() { return metricValue; }
    public void setMetricValue(double metricValue) { this.metricValue = metricValue; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
    public LocalDateTime getFiredAt() { return firedAt; }
    public void setFiredAt(LocalDateTime firedAt) { this.firedAt = firedAt; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
}
