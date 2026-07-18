package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Domain Responsibility: Represents the database schema and domain model for AgentRun (tracking the state and asynchronous execution of an agent LLM request).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agent_runs")
@EntityListeners(AuditingEntityListener.class)
public class AgentRun {

    @Id
    private String id;
    
    private String agentId;
    private String sessionId;
    private String userId;
    private String orgId;
    
    @Column(name = "parent_run_id")
    private String parentRunId;
    
    @Column(columnDefinition = "TEXT")
    private String input;
    
    @Column(columnDefinition = "TEXT")
    private String output;
    
    @Enumerated(EnumType.STRING)
    private RunStatus status; // QUEUED, RUNNING, COMPLETED, FAILED
    
    @CreatedDate
    @Column(name = "created_at", updatable = false)
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

    @Column(columnDefinition = "TEXT")
    private String requiredAction;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "model")
    private String model;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "reasoning_tokens")
    private Long reasoningTokens;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "total_cost_usd", precision = 16, scale = 6)
    private BigDecimal totalCostUsd;

    @Column(name = "error_type", length = 50)
    private String errorType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "safety_risk_score", precision = 4, scale = 3)
    private BigDecimal safetyRiskScore;

    @Column(name = "orchestration_strategy", length = 50)
    private String orchestrationStrategy;

    public AgentRun() {
        this.id = UUID.randomUUID().toString();
        this.status = RunStatus.QUEUED;
    }

    public AgentRun(String agentId, String sessionId, String input, String userId, String orgId) {
        this();
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.input = input;
        this.userId = userId;
        this.orgId = orgId;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getParentRunId() { return parentRunId; }
    public void setParentRunId(String parentRunId) { this.parentRunId = parentRunId; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public String getRequiredAction() { return requiredAction; }
    public void setRequiredAction(String requiredAction) { this.requiredAction = requiredAction; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }

    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }

    public Long getReasoningTokens() { return reasoningTokens; }
    public void setReasoningTokens(Long reasoningTokens) { this.reasoningTokens = reasoningTokens; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal totalCostUsd) { this.totalCostUsd = totalCostUsd; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public BigDecimal getSafetyRiskScore() { return safetyRiskScore; }
    public void setSafetyRiskScore(BigDecimal safetyRiskScore) { this.safetyRiskScore = safetyRiskScore; }

    public String getOrchestrationStrategy() { return orchestrationStrategy; }
    public void setOrchestrationStrategy(String orchestrationStrategy) { this.orchestrationStrategy = orchestrationStrategy; }
}
