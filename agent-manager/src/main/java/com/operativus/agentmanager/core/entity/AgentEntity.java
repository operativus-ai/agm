package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.core.model.HumanReview;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;

/**
 * Domain Responsibility: Represents the database schema and domain model for AgentEntity (defining core AI agent configurations, tools, models, and states).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "agents")
@EntityListeners(AuditingEntityListener.class)
public class AgentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "capabilities", columnDefinition = "TEXT[]", nullable = false)
    private String[] capabilities = new String[0];

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "context_window_size")
    private Integer contextWindowSize;

    @Column(name = "memory_enabled", columnDefinition = "boolean default false")
    private Boolean memoryEnabled = false;

    @Column(name = "add_history_to_messages", columnDefinition = "boolean default true")
    private Boolean addHistoryToMessages = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools", columnDefinition = "jsonb")
    private List<String> tools;

    @Column(name = "is_reasoning_enabled", columnDefinition = "boolean default false")
    private Boolean reasoningEnabled = false;

    @Column(name = "is_team", columnDefinition = "boolean default false")
    private Boolean team = false;

    @Column(name = "team_mode")
    private String teamMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "members", columnDefinition = "jsonb")
    private List<String> members;

    /** gap #8 — ordered list of fallback model ids tried by
     *  {@code AgentClientFactory.buildChatClientForFallback} when the primary
     *  {@link #modelId} is rate-limited or quota-exceeded at call time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fallback_model_ids", columnDefinition = "jsonb")
    private List<String> fallbackModelIds;

    @Column(name = "member_resolver_type", nullable = false)
    private String memberResolverType = "STATIC";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_roles", columnDefinition = "jsonb")
    private List<String> allowedRoles;

    /**
     * REQ-HR-1 — unified HumanReview attachable at the agent level. Today's
     * tool-side HITL ({@code HitlAdvisor}) reads tool tier; REQ-HR-4 will have
     * it consult this field for per-agent HumanReview policy. PR-1 is data-shape
     * only — the field is durable but inert until REQ-HR-4 wires it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "human_review", columnDefinition = "jsonb")
    private HumanReview humanReview;

    @Column(name = "markdown_docs", columnDefinition = "TEXT")
    private String markdownDocs;

    @Column(name = "support_channel")
    private String supportChannel;

    @Column(name = "primary_owner")
    private String primaryOwner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_locales", columnDefinition = "jsonb")
    private List<String> supportedLocales;

    @Column(name = "accessibility_compatibility")
    private String accessibilityCompatibility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "training_datasets", columnDefinition = "jsonb")
    private List<String> trainingDatasets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_base_ids", columnDefinition = "jsonb")
    private List<String> knowledgeBaseIds;

    @Column(name = "requires_pii_redaction", columnDefinition = "boolean default false")
    private Boolean requiresPiiRedaction = false;

    @Column(name = "approved_for_production", columnDefinition = "boolean default false")
    private Boolean approvedForProduction = false;

    @Column(name = "maintenance_mode", columnDefinition = "boolean default false")
    private Boolean maintenanceMode = false;

    @Column(name = "active", nullable = false, columnDefinition = "boolean default true")
    private Boolean active = true;

    @Column(name = "canary_percentage")
    private Integer canaryPercentage; // 0-100: percentage of traffic routed to this agent variant

    @Column(name = "canary_base_agent_id")
    private String canaryBaseAgentId; // The "production" agent this canary is testing against

    @Column(name = "enforce_json_output", columnDefinition = "boolean default false")
    private Boolean enforceJsonOutput = false;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "top_p")
    private Double topP;

    @Column(name = "frequency_penalty")
    private Double frequencyPenalty;

    @Column(name = "system_prompt_mode", length = 50)
    private String systemPromptMode;

    @Column(name = "max_concurrent_executions")
    private Integer maxConcurrentExecutions;

    @Column(name = "fin_ops_token_budget")
    private Long finOpsTokenBudget;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "fin_ops_risk_tier", length = 30)
    private FinOpsRiskTier finOpsRiskTier;

    @Column(name = "compression_threshold")
    private Integer compressionThreshold;

    @Column(name = "summarization_threshold")
    private Integer summarizationThreshold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pre_hooks", columnDefinition = "jsonb")
    private List<String> preHooks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "post_hooks", columnDefinition = "jsonb")
    private List<String> postHooks;

    @Column(name = "optimization_model_id")
    private String optimizationModelId;

    @Column(name = "security_tier", nullable = false, columnDefinition = "int default 1")
    private Integer securityTier = 1;

    // @Convert (not @Enumerated) so the column's varchar→enum hydration routes through
    // ComplianceTier.fromString, which safe-falls to TIER_1_STANDARD on unknown legacy
    // values instead of throwing IllegalArgumentException and 500-ing the entire list
    // query. Symmetrical to the @JsonCreator behavior on inbound DTOs.
    @jakarta.persistence.Convert(converter = ComplianceTierConverter.class)
    @Column(name = "compliance_tier", nullable = false, columnDefinition = "varchar(50) default 'TIER_1_STANDARD'")
    private ComplianceTier complianceTier = ComplianceTier.TIER_1_STANDARD;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", columnDefinition = "jsonb")
    private java.util.Map<String, Object> configuration;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

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

    // Constructors
    public AgentEntity() {
    }

    public AgentEntity(String id, String name, String description, String instructions, String modelId, Integer contextWindowSize, Boolean memoryEnabled, Boolean addHistoryToMessages, List<String> tools, Boolean reasoningEnabled, Boolean team, String teamMode, List<String> members, List<String> allowedRoles, String markdownDocs, String supportChannel, String primaryOwner, List<String> supportedLocales, String accessibilityCompatibility, List<String> trainingDatasets, Boolean requiresPiiRedaction, Boolean approvedForProduction, Boolean maintenanceMode, Boolean active, Boolean enforceJsonOutput, java.util.Map<String, Object> configuration, Integer compressionThreshold, Integer summarizationThreshold, String optimizationModelId) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.modelId = modelId;
        this.contextWindowSize = contextWindowSize;
        this.memoryEnabled = memoryEnabled != null ? memoryEnabled : false;
        this.addHistoryToMessages = addHistoryToMessages != null ? addHistoryToMessages : true;
        this.tools = tools;
        this.reasoningEnabled = reasoningEnabled;
        this.team = team;
        this.teamMode = teamMode;
        this.members = members;
        this.allowedRoles = allowedRoles;
        this.markdownDocs = markdownDocs;
        this.supportChannel = supportChannel;
        this.primaryOwner = primaryOwner;
        this.supportedLocales = supportedLocales;
        this.accessibilityCompatibility = accessibilityCompatibility;
        this.trainingDatasets = trainingDatasets;
        this.requiresPiiRedaction = requiresPiiRedaction;
        this.approvedForProduction = approvedForProduction;
        this.maintenanceMode = maintenanceMode;
        this.active = active;
        this.enforceJsonOutput = enforceJsonOutput;
        this.configuration = configuration;
        this.compressionThreshold = compressionThreshold;
        this.summarizationThreshold = summarizationThreshold;
        this.optimizationModelId = optimizationModelId;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public Integer getCanaryPercentage() { return canaryPercentage; }
    public void setCanaryPercentage(Integer canaryPercentage) { this.canaryPercentage = canaryPercentage; }
    public String getCanaryBaseAgentId() { return canaryBaseAgentId; }
    public void setCanaryBaseAgentId(String canaryBaseAgentId) { this.canaryBaseAgentId = canaryBaseAgentId; }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String[] getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String[] capabilities) {
        this.capabilities = capabilities;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public String getOptimizationModelId() {
        return optimizationModelId;
    }

    public void setOptimizationModelId(String optimizationModelId) {
        this.optimizationModelId = optimizationModelId;
    }

    public void setContextWindowSize(Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    public Boolean getMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(Boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public Boolean getAddHistoryToMessages() {
        return addHistoryToMessages;
    }

    public void setAddHistoryToMessages(Boolean addHistoryToMessages) {
        this.addHistoryToMessages = addHistoryToMessages;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public Boolean isReasoningEnabled() {
        return reasoningEnabled;
    }

    public void setReasoningEnabled(Boolean reasoningEnabled) {
        this.reasoningEnabled = reasoningEnabled;
    }

    public Boolean isTeam() {
        return team;
    }

    public void setTeam(Boolean team) {
        this.team = team;
    }

    public String getTeamMode() {
        return teamMode;
    }

    public void setTeamMode(String teamMode) {
        this.teamMode = teamMode;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members;
    }

    public List<String> getFallbackModelIds() {
        return fallbackModelIds;
    }

    public void setFallbackModelIds(List<String> fallbackModelIds) {
        this.fallbackModelIds = fallbackModelIds;
    }

    public String getMemberResolverType() {
        return memberResolverType;
    }

    public void setMemberResolverType(String memberResolverType) {
        this.memberResolverType = memberResolverType;
    }

    public List<String> getAllowedRoles() {
        return allowedRoles;
    }

    public void setAllowedRoles(List<String> allowedRoles) {
        this.allowedRoles = allowedRoles;
    }

    public HumanReview getHumanReview() {
        return humanReview;
    }

    public void setHumanReview(HumanReview humanReview) {
        this.humanReview = humanReview;
    }

    public String getMarkdownDocs() {
        return markdownDocs;
    }

    public void setMarkdownDocs(String markdownDocs) {
        this.markdownDocs = markdownDocs;
    }

    public String getSupportChannel() {
        return supportChannel;
    }

    public void setSupportChannel(String supportChannel) {
        this.supportChannel = supportChannel;
    }

    public String getPrimaryOwner() {
        return primaryOwner;
    }

    public void setPrimaryOwner(String primaryOwner) {
        this.primaryOwner = primaryOwner;
    }

    public List<String> getSupportedLocales() {
        return supportedLocales;
    }

    public void setSupportedLocales(List<String> supportedLocales) {
        this.supportedLocales = supportedLocales;
    }

    public String getAccessibilityCompatibility() {
        return accessibilityCompatibility;
    }

    public void setAccessibilityCompatibility(String accessibilityCompatibility) {
        this.accessibilityCompatibility = accessibilityCompatibility;
    }

    public List<String> getTrainingDatasets() {
        return trainingDatasets;
    }

    public void setTrainingDatasets(List<String> trainingDatasets) {
        this.trainingDatasets = trainingDatasets;
    }

    public List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds;
    }

    public Boolean isRequiresPiiRedaction() {
        return requiresPiiRedaction;
    }

    public void setRequiresPiiRedaction(Boolean requiresPiiRedaction) {
        this.requiresPiiRedaction = requiresPiiRedaction;
    }

    public Boolean isApprovedForProduction() {
        return approvedForProduction;
    }

    public void setApprovedForProduction(Boolean approvedForProduction) {
        this.approvedForProduction = approvedForProduction;
    }

    public Boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public void setMaintenanceMode(Boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    public Boolean isActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean isEnforceJsonOutput() {
        return enforceJsonOutput;
    }

    public void setEnforceJsonOutput(Boolean enforceJsonOutput) {
        this.enforceJsonOutput = enforceJsonOutput;
    }

    public Integer getSecurityTier() {
        return securityTier != null ? securityTier : 1;
    }

    public void setSecurityTier(Integer securityTier) {
        this.securityTier = securityTier;
    }

    public ComplianceTier getComplianceTier() {
        return complianceTier != null ? complianceTier : ComplianceTier.TIER_1_STANDARD;
    }

    public void setComplianceTier(ComplianceTier complianceTier) {
        this.complianceTier = complianceTier;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public String getSystemPromptMode() {
        return systemPromptMode;
    }

    public void setSystemPromptMode(String systemPromptMode) {
        this.systemPromptMode = systemPromptMode;
    }

    public Integer getMaxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    public void setMaxConcurrentExecutions(Integer maxConcurrentExecutions) {
        this.maxConcurrentExecutions = maxConcurrentExecutions;
    }

    public Long getFinOpsTokenBudget() {
        return finOpsTokenBudget;
    }

    public void setFinOpsTokenBudget(Long finOpsTokenBudget) {
        this.finOpsTokenBudget = finOpsTokenBudget;
    }

    public FinOpsRiskTier getFinOpsRiskTier() {
        return finOpsRiskTier;
    }

    public void setFinOpsRiskTier(FinOpsRiskTier finOpsRiskTier) {
        this.finOpsRiskTier = finOpsRiskTier;
    }

    public Integer getCompressionThreshold() {
        return compressionThreshold;
    }

    public void setCompressionThreshold(Integer compressionThreshold) {
        this.compressionThreshold = compressionThreshold;
    }

    public Integer getSummarizationThreshold() {
        return summarizationThreshold;
    }

    public void setSummarizationThreshold(Integer summarizationThreshold) {
        this.summarizationThreshold = summarizationThreshold;
    }

    public List<String> getPreHooks() {
        return preHooks;
    }

    public void setPreHooks(List<String> preHooks) {
        this.preHooks = preHooks;
    }

    public List<String> getPostHooks() {
        return postHooks;
    }

    public void setPostHooks(List<String> postHooks) {
        this.postHooks = postHooks;
    }

    public java.util.Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(java.util.Map<String, Object> configuration) {
        this.configuration = configuration;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentEntity that = (AgentEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
