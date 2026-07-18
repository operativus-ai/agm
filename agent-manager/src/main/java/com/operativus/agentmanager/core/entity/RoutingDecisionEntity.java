package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: One row per {@code RoutingResolver.resolveAgentId} invocation
 *     (universal dispatch). Captures which strategy resolved (or that all missed),
 *     the resolved agent id (nullable), optional classifier confidence, candidate
 *     count, latency, and a SHA-256 hash of the message for analytics without
 *     storing the message itself. Append-only via {@code trg_routing_decisions_immutable}
 *     — see changeset 089. Erasure/retention paths must set
 *     {@code SET LOCAL agm.audit_immutability_bypass='true'} inside their txn.
 * State: Stateful (data carrier / JPA entity)
 */
@Entity
@Table(name = "routing_decisions")
public class RoutingDecisionEntity {

    public enum ResolutionStatus { RESOLVED, UNRESOLVED, ERROR }

    public enum StrategyUsed { DEFAULT_ROUTER, LLM_CLASSIFIER, RULE_SUBSTRING, SEMANTIC_SCORING, FALLBACK, NONE }

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "message_hash", nullable = false, length = 64)
    private String messageHash;

    @Column(name = "message_length")
    private Integer messageLength;

    @Column(name = "resolved_agent_id")
    private String resolvedAgentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 32)
    private ResolutionStatus resolutionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_used", nullable = false, length = 32)
    private StrategyUsed strategyUsed;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "candidate_count")
    private Integer candidateCount;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RoutingDecisionEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessageHash() { return messageHash; }
    public void setMessageHash(String messageHash) { this.messageHash = messageHash; }

    public Integer getMessageLength() { return messageLength; }
    public void setMessageLength(Integer messageLength) { this.messageLength = messageLength; }

    public String getResolvedAgentId() { return resolvedAgentId; }
    public void setResolvedAgentId(String resolvedAgentId) { this.resolvedAgentId = resolvedAgentId; }

    public ResolutionStatus getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(ResolutionStatus resolutionStatus) { this.resolutionStatus = resolutionStatus; }

    public StrategyUsed getStrategyUsed() { return strategyUsed; }
    public void setStrategyUsed(StrategyUsed strategyUsed) { this.strategyUsed = strategyUsed; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public Integer getCandidateCount() { return candidateCount; }
    public void setCandidateCount(Integer candidateCount) { this.candidateCount = candidateCount; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
