package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Per-org configuration for the universal-dispatch entry point
 *     ({@code POST /api/runs}). Drives how a run with no explicit {@code agentId} is
 *     routed to a target agent. Three strategies compose in priority order:
 *     {@code default_router_agent_id} (designated team), {@code llm_classifier_enabled}
 *     (LLM picks from active agents), {@code rule_classifier_enabled} (tag/description
 *     match). If all strategies miss, {@code fallback_agent_id} receives the run.
 *     One row per org (DB-level unique on {@code org_id}).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "org_routing_config")
public class OrgRoutingConfig {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false, columnDefinition = "TEXT", unique = true)
    private String orgId;

    @Column(name = "default_router_agent_id")
    private String defaultRouterAgentId;

    @Column(name = "fallback_agent_id")
    private String fallbackAgentId;

    @Column(name = "llm_classifier_enabled", nullable = false)
    private Boolean llmClassifierEnabled = Boolean.FALSE;

    @Column(name = "rule_classifier_enabled", nullable = false)
    private Boolean ruleClassifierEnabled = Boolean.FALSE;

    @Column(name = "semantic_scoring_enabled", nullable = false)
    private Boolean semanticScoringEnabled = Boolean.FALSE;

    @Column(name = "default_router_cached_strategy")
    private String defaultRouterCachedStrategy;

    @Column(name = "classifier_model_id")
    private String classifierModelId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public OrgRoutingConfig() {}

    public OrgRoutingConfig(String id, String orgId) {
        this.id = id;
        this.orgId = orgId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getDefaultRouterAgentId() { return defaultRouterAgentId; }
    public void setDefaultRouterAgentId(String defaultRouterAgentId) { this.defaultRouterAgentId = defaultRouterAgentId; }

    public String getFallbackAgentId() { return fallbackAgentId; }
    public void setFallbackAgentId(String fallbackAgentId) { this.fallbackAgentId = fallbackAgentId; }

    public Boolean getLlmClassifierEnabled() { return llmClassifierEnabled; }
    public void setLlmClassifierEnabled(Boolean llmClassifierEnabled) { this.llmClassifierEnabled = llmClassifierEnabled; }

    public Boolean getRuleClassifierEnabled() { return ruleClassifierEnabled; }
    public void setRuleClassifierEnabled(Boolean ruleClassifierEnabled) { this.ruleClassifierEnabled = ruleClassifierEnabled; }

    public Boolean getSemanticScoringEnabled() { return semanticScoringEnabled; }
    public void setSemanticScoringEnabled(Boolean semanticScoringEnabled) { this.semanticScoringEnabled = semanticScoringEnabled; }

    public String getDefaultRouterCachedStrategy() { return defaultRouterCachedStrategy; }
    public void setDefaultRouterCachedStrategy(String defaultRouterCachedStrategy) { this.defaultRouterCachedStrategy = defaultRouterCachedStrategy; }

    public String getClassifierModelId() { return classifierModelId; }
    public void setClassifierModelId(String classifierModelId) { this.classifierModelId = classifierModelId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
