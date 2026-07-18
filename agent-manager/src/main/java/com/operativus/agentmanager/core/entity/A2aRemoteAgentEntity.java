package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: JPA entity backing the {@code a2a_remote_agents} table.
 * Represents a registered external A2A peer agent that this AGM instance can
 * delegate tasks to via the A2ACardResolver discovery layer.
 *
 * L-2 Fix: The {@code outboundApiKey} field is annotated with {@link Convert}
 * pointing to {@link OutboundApiKeyConverter}, which applies AES-256-GCM
 * encryption before writing to the database and decrypts transparently on read.
 * The plaintext key is never stored in the {@code a2a_remote_agents} table.
 *
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "a2a_remote_agents")
public class A2aRemoteAgentEntity {

    @Id
    @Column(name = "id", nullable = false, length = 255)
    private String id;

    @Column(name = "remote_agent_id", nullable = false, length = 255)
    private String remoteAgentId;

    @Column(name = "base_url", nullable = false, length = 1024)
    private String baseUrl;

    @Column(name = "alias", nullable = false, length = 255)
    private String alias;

    @Column(name = "org_id", length = 255)
    private String orgId;

    /**
     * Outbound API key sent when calling the remote peer.
     * Encrypted at rest via AES-256-GCM by {@link OutboundApiKeyConverter}.
     * Column widened to 500 chars to accommodate Base64-encoded ciphertext.
     */
    @Convert(converter = OutboundApiKeyConverter.class)
    @Column(name = "outbound_api_key", length = 500)
    private String outboundApiKey;

    /**
     * Key version used to encrypt the {@code outbound_api_key} value currently stored.
     * §22.6: the scheduled {@code OutboundApiKeyMigrationService} re-encrypts rows whose
     * version does not match the active version. Defaults to {@code 1} for legacy rows
     * inserted before versioning was introduced (migration 026).
     */
    @Column(name = "key_version", nullable = false)
    private Integer keyVersion = 1;

    @Column(name = "data_zone", length = 255)
    private String dataZone;

    @Column(name = "security_tier", nullable = false)
    private Integer securityTier = 1;

    @Column(name = "trusted", nullable = false)
    private Boolean trusted = true;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cached_card", columnDefinition = "JSONB")
    private String cachedCard;

    @Column(name = "registered_by", length = 255)
    private String registeredBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public A2aRemoteAgentEntity() {}

    public String getId()                       { return id; }
    public void setId(String id)                { this.id = id; }

    public String getRemoteAgentId()            { return remoteAgentId; }
    public void setRemoteAgentId(String v)      { this.remoteAgentId = v; }

    public String getBaseUrl()                  { return baseUrl; }
    public void setBaseUrl(String v)            { this.baseUrl = v; }

    public String getAlias()                    { return alias; }
    public void setAlias(String v)              { this.alias = v; }

    public String getOrgId()                    { return orgId; }
    public void setOrgId(String v)              { this.orgId = v; }

    /** Returns the decrypted outbound API key. Never the raw ciphertext. */
    public String getOutboundApiKey()           { return outboundApiKey; }
    public void setOutboundApiKey(String v)     { this.outboundApiKey = v; }

    public Integer getKeyVersion()              { return keyVersion; }
    public void setKeyVersion(Integer v)        { this.keyVersion = v; }

    public String getDataZone()                 { return dataZone; }
    public void setDataZone(String v)           { this.dataZone = v; }

    public Integer getSecurityTier()            { return securityTier; }
    public void setSecurityTier(Integer v)      { this.securityTier = v; }

    public Boolean getTrusted()                 { return trusted; }
    public void setTrusted(Boolean v)           { this.trusted = v; }

    public LocalDateTime getLastVerifiedAt()    { return lastVerifiedAt; }
    public void setLastVerifiedAt(LocalDateTime v) { this.lastVerifiedAt = v; }

    public String getCachedCard()               { return cachedCard; }
    public void setCachedCard(String v)         { this.cachedCard = v; }

    public String getRegisteredBy()             { return registeredBy; }
    public void setRegisteredBy(String v)       { this.registeredBy = v; }

    public LocalDateTime getCreatedAt()         { return createdAt; }
    public LocalDateTime getUpdatedAt()         { return updatedAt; }
}
