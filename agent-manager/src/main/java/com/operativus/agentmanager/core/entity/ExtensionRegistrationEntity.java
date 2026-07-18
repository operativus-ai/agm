package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "extensions")
public class ExtensionRegistrationEntity {
    
    @Id
    @Column(name = "id")
    private String id;
    
    @Column(name = "name")
    private String name;
    
    @Column(name = "type")
    private String type;
    
    @Column(name = "url")
    private String url;
    
    @Column(name = "description")
    private String description;
    
    @Column(name = "active")
    private Boolean active;

    /**
     * Owning tenant. Bare org_id string (no FK), matching the repo-wide tenancy convention.
     * Stamped from the caller's org on registration; reads/updates/deletes are scoped by it
     * (cross-tenant → 404). The MCP connection pool keys discovered tools by this so an agent
     * only ever sees MCP tools from extensions in its own org (#1132). Legacy rows default to
     * {@code DEFAULT_SYSTEM_ORG} via changeset 105.
     */
    @Column(name = "org_id", nullable = false)
    private String orgId;

    /**
     * MCP transport — {@code SSE} (default) or {@code STREAMABLE_HTTP}. Stored as a VARCHAR
     * string of {@link com.operativus.agentmanager.core.model.McpTransport}. Only meaningful
     * for {@code type=MCP} rows; ignored for WEBHOOK / NATIVE_SPI.
     */
    @Column(name = "transport", nullable = false, columnDefinition = "varchar(32) default 'SSE'")
    private String transport = "SSE";

    /**
     * Opaque outbound auth secret applied as {@code Authorization: Bearer <auth>} on the MCP
     * transport. Encrypted at rest with the same AES-256-GCM converter as outbound peer / model
     * keys — never stored or logged in plaintext, never returned by the API (the controller emits
     * only a masked preview).
     */
    @Column(name = "auth_secret", length = 1024)
    @Convert(converter = OutboundApiKeyConverter.class)
    private String authSecret;

    @Column(name = "sandboxed", columnDefinition = "boolean default true")
    private Boolean sandboxed = true;

    @Column(name = "max_timeout_seconds")
    private Integer maxTimeoutSeconds = 30;

    @Column(name = "allowed_operations")
    private String allowedOperations; // Comma-separated: READ,WRITE,EXECUTE

    @Column(name = "marketplace_category")
    private String marketplaceCategory; // e.g., "data-source", "tool", "monitoring"

    @Column(name = "verified")
    private Boolean verified = false;

    /**
     * Optimistic-lock version. JPA increments on each save; concurrent PUTs through
     * {@code ExtensionController.updateExtension} that race on the same row produce one
     * 200 (winner) and one {@code ObjectOptimisticLockingFailureException} → 409 via
     * {@code GlobalExceptionHandler}. Defaults to 0 for rows pre-existing the column add.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ExtensionRegistrationEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getAuthSecret() { return authSecret; }
    public void setAuthSecret(String authSecret) { this.authSecret = authSecret; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getSandboxed() { return sandboxed; }
    public void setSandboxed(Boolean sandboxed) { this.sandboxed = sandboxed; }
    public Integer getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
    public void setMaxTimeoutSeconds(Integer maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
    public String getAllowedOperations() { return allowedOperations; }
    public void setAllowedOperations(String allowedOperations) { this.allowedOperations = allowedOperations; }
    public String getMarketplaceCategory() { return marketplaceCategory; }
    public void setMarketplaceCategory(String marketplaceCategory) { this.marketplaceCategory = marketplaceCategory; }
    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
