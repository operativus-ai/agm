package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExtensionRegistrationRepository extends JpaRepository<ExtensionRegistrationEntity, String> {

    /** Tenant-scoped lookup — cross-org id resolves to empty (caller returns 404, no existence leak). */
    Optional<ExtensionRegistrationEntity> findByIdAndOrgId(String id, String orgId);

    /** All extensions owned by one org (admin list surface). */
    List<ExtensionRegistrationEntity> findByOrgId(String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);

    /**
     * Note: inherited {@code findAll()} stays cross-org by design — it is used only by
     * {@link ai.operativus.agentmanager.compute.mcp.McpConnectionPool#reconcileOnStartup()},
     * which has no auth context at {@code ApplicationReadyEvent} time and must connect every
     * tenant's MCP servers into the shared pool. Per-org isolation is enforced at tool-resolution
     * time via the pool's {@code extensionId → orgId} map, not at startup.
     */
}
