package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: DB-backed runtime config row for one Composio action. Operators edit
 *   these via the {@code /admin/composio} UI (planned in {@code agmui-tool-support-composio.md});
 *   {@code ComposioActionRegistry} consumes the rows at boot and on
 *   {@code ComposioConfigChangedEvent} to determine which {@code composio_*} tools register
 *   with the {@code globalToolProvider}. When the DB has zero rows for an action that is in
 *   the properties-file {@code agent.tools.composio.enabled-actions} list, the properties
 *   value wins (DB-empty falls back to boot-time {@code @Value}); when both are present, DB
 *   wins per the spec's "DB > properties-file" precedence rule.
 *
 * <p><b>Optimistic locking:</b> {@code @Version} guards against concurrent operator edits.
 *   Two operators flipping the same row's tier in the same second produce a 409 on the second
 *   submission; the spec's UI surfaces this with a "refresh and retry" prompt.
 *
 * State: Persistent (one row per action). {@code llmToolName} is the lowercase
 *   {@code composio_<actionName>} string emitted to the LLM; held as a column so
 *   {@code ComposioActionRegistry} doesn't recompute it on every read.
 */
@Entity
@Table(name = "composio_action_config")
public class ComposioActionConfig {

    @Id
    private String id;

    @Column(name = "action_name", nullable = false, unique = true)
    private String actionName;

    @Column(name = "llm_tool_name", nullable = false)
    private String llmToolName;

    @Column(nullable = false)
    private Integer tier;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    public ComposioActionConfig() {}

    public ComposioActionConfig(String id, String actionName, Integer tier) {
        this.id = id;
        this.actionName = actionName;
        this.llmToolName = "composio_" + actionName.toLowerCase();
        this.tier = tier;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getActionName() { return actionName; }
    public void setActionName(String actionName) {
        this.actionName = actionName;
        this.llmToolName = "composio_" + actionName.toLowerCase();
    }

    public String getLlmToolName() { return llmToolName; }
    public void setLlmToolName(String llmToolName) { this.llmToolName = llmToolName; }

    public Integer getTier() { return tier; }
    public void setTier(Integer tier) { this.tier = tier; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
