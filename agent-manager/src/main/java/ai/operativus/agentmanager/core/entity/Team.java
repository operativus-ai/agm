package ai.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;

/**
 * Domain Responsibility: Represents the database schema and domain model for Team (defining a group of agents collaborating under a specific operational mode).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "teams")
public class Team {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "team_mode", length = 50)
    private String teamMode;

    @Column(name = "leader_id")
    private String leaderId;

    @Column(name = "model_id")
    private String modelId;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "context_window_size")
    private Integer contextWindowSize;

    @Column(name = "memory_enabled", columnDefinition = "boolean default false")
    private Boolean memoryEnabled = false;

    @Column(name = "add_history_to_messages", columnDefinition = "boolean default true")
    private Boolean addHistoryToMessages = true;

    /** §9 MEM-2: when {@code true}, orchestrators derive a per-member conversationId so each
     *  team member's MessageChatMemoryAdvisor keeps its own message buffer. When {@code false}
     *  (default), every member shares the team's session memory and sees the running
     *  cross-member transcript — current behaviour. The flag has no effect on Swarm (which
     *  already derives per-branch session ids), but applies to Sequential, Router, and Planner. */
    @Column(name = "isolate_memory", columnDefinition = "boolean default false", nullable = false)
    private Boolean isolateMemory = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "team_tools",
            joinColumns = @JoinColumn(name = "team_id")
    )
    @Column(name = "tool_name")
    private List<String> tools;

    @Column(name = "human_lead")
    private String humanLead;

    @Column(name = "max_daily_spend")
    private Double maxDailySpend;

    @Column(name = "min_spending_authority")
    private Double minSpendingAuthority;

    @Column(name = "org_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String orgId;

    @Column(name = "archived", columnDefinition = "boolean default false")
    private Boolean archived = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Team() {
    }

    public Team(String id, String name, String description, String teamMode, String leaderId, String modelId, String instructions, Integer contextWindowSize, Boolean memoryEnabled, Boolean addHistoryToMessages, List<String> tools) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.teamMode = teamMode;
        this.leaderId = leaderId;
        this.modelId = modelId;
        this.instructions = instructions;
        this.contextWindowSize = contextWindowSize;
        this.memoryEnabled = memoryEnabled != null ? memoryEnabled : false;
        this.addHistoryToMessages = addHistoryToMessages != null ? addHistoryToMessages : true;
        this.tools = tools;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTeamMode() {
        return teamMode;
    }

    public void setTeamMode(String teamMode) {
        this.teamMode = teamMode;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Integer getContextWindowSize() {
        return contextWindowSize;
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

    public Boolean getIsolateMemory() {
        return isolateMemory;
    }

    public void setIsolateMemory(Boolean isolateMemory) {
        this.isolateMemory = isolateMemory != null ? isolateMemory : false;
    }

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public String getHumanLead() {
        return humanLead;
    }

    public void setHumanLead(String humanLead) {
        this.humanLead = humanLead;
    }

    public Double getMaxDailySpend() {
        return maxDailySpend;
    }

    public void setMaxDailySpend(Double maxDailySpend) {
        this.maxDailySpend = maxDailySpend;
    }

    public Double getMinSpendingAuthority() {
        return minSpendingAuthority;
    }

    public void setMinSpendingAuthority(Double minSpendingAuthority) {
        this.minSpendingAuthority = minSpendingAuthority;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
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
}
