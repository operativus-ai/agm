package ai.operativus.agentmanager.core.entity;

import ai.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: Per-(org, provider) default API key store. Replaces the prior
 * env-var / Spring property fallback chain in {@code AbstractDynamicModelProvider.resolveApiKey}.
 * {@code ModelEntity.apiKey} remains a per-model override that takes precedence when set;
 * otherwise the resolver looks up the row matching {@code (caller orgId, model.provider)}.
 *
 * State: Stateful (JPA entity, encrypted column).
 */
@Entity
@Table(name = "provider_credentials")
@EntityListeners(AuditingEntityListener.class)
public class ProviderCredential {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Convert(converter = OutboundApiKeyConverter.class)
    @Column(name = "api_key", nullable = false, length = 512)
    private String apiKey;

    @Column(name = "label")
    private String label;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
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

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public ProviderCredential() {}

    public ProviderCredential(String id, String orgId, String provider, String apiKey, String label) {
        this.id = id;
        this.orgId = orgId;
        this.provider = provider;
        this.apiKey = apiKey;
        this.label = label;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderCredential that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
