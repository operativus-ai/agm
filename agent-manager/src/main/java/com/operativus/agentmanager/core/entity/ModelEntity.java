package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: Represents the database schema and domain model for ModelEntity (defining specific LLM models, their capabilities, token limits, and provider integrations).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "models")
@EntityListeners(AuditingEntityListener.class)
public class ModelEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "base_url")
    private String baseUrl;

    @Convert(converter = OutboundApiKeyConverter.class)
    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "supports_tools", nullable = false)
    private Boolean supportsTools = true;

    @Column(name = "supports_vision", nullable = false)
    private Boolean supportsVision = false;

    @Column(name = "supports_system_instructions", nullable = false)
    private Boolean supportsSystemInstructions = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false)
    private ModelType modelType = ModelType.CHAT;

    @Column(name = "max_context_tokens")
    private Integer maxContextTokens;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "thinking_budget_tokens")
    private Integer thinkingBudgetTokens;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    /** §7 Model Pinger: nullable BOOLEAN reflecting the most recent liveness probe outcome from
     *  {@code ModelAvailabilityPoller}. {@code null} means never pinged (e.g. fresh row). {@code true}
     *  means the last ping succeeded; {@code false} means it failed. The column is informational —
     *  the agent-run path does NOT gate on it (a stale {@code false} must not block a request that
     *  the provider has since recovered for). UI surfaces the value as a badge. */
    @Column(name = "available")
    private Boolean available;

    /** Wall-clock instant of the last ping that produced {@code available}. Useful for staleness
     *  badges (e.g. "checked 12m ago") and to suppress badge churn during transient blips. */
    @Column(name = "last_pinged_at")
    private LocalDateTime lastPingedAt;

    /** §6 M-12: optional per-model rate limit, in requests per minute. {@code null} means no
     *  per-model gate (the global per-user RateLimitingFilter still applies). When set, Phase 2
     *  installs a Resilience4j {@code RateLimiter} keyed by {@code "model:" + id} that gates
     *  every LLM call routed through this model. Useful when a downstream provider's quota is
     *  much tighter than the global per-user cap (e.g. a partner-shared key with a 60-RPM quota). */
    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    public ModelEntity() {
    }

    public ModelEntity(String id, String name, String provider, String baseUrl, String apiKey, String modelName) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Boolean getSupportsTools() {
        return supportsTools;
    }

    public void setSupportsTools(Boolean supportsTools) {
        this.supportsTools = supportsTools;
    }

    public Boolean getSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(Boolean supportsVision) {
        this.supportsVision = supportsVision;
    }

    public Boolean getSupportsSystemInstructions() {
        return supportsSystemInstructions;
    }

    public void setSupportsSystemInstructions(Boolean supportsSystemInstructions) {
        this.supportsSystemInstructions = supportsSystemInstructions;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public void setModelType(ModelType modelType) {
        this.modelType = modelType;
    }

    public Integer getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(Integer maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) {
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public LocalDateTime getLastPingedAt() {
        return lastPingedAt;
    }

    public void setLastPingedAt(LocalDateTime lastPingedAt) {
        this.lastPingedAt = lastPingedAt;
    }

    public Integer getRateLimitRpm() {
        return rateLimitRpm;
    }

    public void setRateLimitRpm(Integer rateLimitRpm) {
        this.rateLimitRpm = rateLimitRpm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelEntity that = (ModelEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
