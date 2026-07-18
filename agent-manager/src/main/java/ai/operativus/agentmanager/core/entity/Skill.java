package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Domain Responsibility: Represents the database schema and domain model for Skill —
 *     a reusable, org-scoped bundle of (allowed-tool-name list + system-prompt-snippet)
 *     that admins attach to agents to augment behavior at run time. INCLUDES semantics:
 *     allowedTools references existing tools in {@code ToolConfig.globalToolProvider}
 *     by name; the skill does NOT own tool implementations.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "skills")
public class Skill {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false, columnDefinition = "TEXT")
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_prompt_snippet", columnDefinition = "TEXT")
    private String systemPromptSnippet;

    @Column(nullable = false)
    private Boolean active = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "skill_allowed_tools",
        joinColumns = @JoinColumn(name = "skill_id")
    )
    @Column(name = "tool_name", nullable = false)
    private Set<String> allowedTools = new HashSet<>();

    public Skill() {}

    public Skill(String id, String orgId, String name, String description, String systemPromptSnippet) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.description = description;
        this.systemPromptSnippet = systemPromptSnippet;
        this.active = Boolean.TRUE;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPromptSnippet() { return systemPromptSnippet; }
    public void setSystemPromptSnippet(String systemPromptSnippet) { this.systemPromptSnippet = systemPromptSnippet; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public Set<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(Set<String> allowedTools) { this.allowedTools = allowedTools; }
}
