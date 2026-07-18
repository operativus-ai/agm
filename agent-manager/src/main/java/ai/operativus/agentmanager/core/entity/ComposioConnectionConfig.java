package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: DB-backed per-org Composio connection ID — the bundled OAuth
 *   identifier that {@code ComposioToolCallback.call(...)} sends as {@code connectionId}
 *   on every action invocation. One row per org. Pairs with the existing
 *   {@code application.properties} key {@code agent.tools.composio.connection-ids.<orgId>=<id>};
 *   when DB row is missing, the {@code Environment.getProperty(...)} fallback path stays in
 *   effect (zero-config preserved for properties-file deploys).
 *
 * State: Persistent. UNIQUE constraint on {@code orgId} enforces "one connection per org"
 *   per the spec's current model. Optimistic-locked via {@code @Version}.
 */
@Entity
@Table(name = "composio_connection_config")
public class ComposioConnectionConfig {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false, unique = true)
    private String orgId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Version
    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ComposioConnectionConfig() {}

    public ComposioConnectionConfig(String id, String orgId, String connectionId) {
        this.id = id;
        this.orgId = orgId;
        this.connectionId = connectionId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
