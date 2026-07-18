package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema for FinOps valuation rates (token-to-USD pricing).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "finops_valuation_rate")
public class FinOpsValuationRateEntity {

    @Id
    @Column(name = "model_id", nullable = false, unique = true)
    private String modelId;

    @Column(name = "input_rate_per_k_tokens", nullable = false)
    private Double inputRatePerKTokens;

    @Column(name = "output_rate_per_k_tokens", nullable = false)
    private Double outputRatePerKTokens;

    @Column(name = "cached_input_rate_per_k_tokens", nullable = false)
    private Double cachedInputRatePerKTokens = 0.0;

    @Column(name = "reasoning_rate_per_k_tokens", nullable = false)
    private Double reasoningRatePerKTokens = 0.0;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public FinOpsValuationRateEntity() {}

    public FinOpsValuationRateEntity(String modelId, Double inputRatePerKTokens, Double outputRatePerKTokens) {
        this.modelId = modelId;
        this.inputRatePerKTokens = inputRatePerKTokens;
        this.outputRatePerKTokens = outputRatePerKTokens;
        this.cachedInputRatePerKTokens = 0.0;
        this.reasoningRatePerKTokens = 0.0;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public Double getInputRatePerKTokens() {
        return inputRatePerKTokens;
    }

    public void setInputRatePerKTokens(Double inputRatePerKTokens) {
        this.inputRatePerKTokens = inputRatePerKTokens;
    }

    public Double getOutputRatePerKTokens() {
        return outputRatePerKTokens;
    }

    public void setOutputRatePerKTokens(Double outputRatePerKTokens) {
        this.outputRatePerKTokens = outputRatePerKTokens;
    }

    public Double getCachedInputRatePerKTokens() {
        return cachedInputRatePerKTokens != null ? cachedInputRatePerKTokens : 0.0;
    }

    public void setCachedInputRatePerKTokens(Double cachedInputRatePerKTokens) {
        this.cachedInputRatePerKTokens = cachedInputRatePerKTokens != null ? cachedInputRatePerKTokens : 0.0;
    }

    public Double getReasoningRatePerKTokens() {
        return reasoningRatePerKTokens != null ? reasoningRatePerKTokens : 0.0;
    }

    public void setReasoningRatePerKTokens(Double reasoningRatePerKTokens) {
        this.reasoningRatePerKTokens = reasoningRatePerKTokens != null ? reasoningRatePerKTokens : 0.0;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
