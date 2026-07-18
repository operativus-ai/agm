package ai.operativus.agentmanager.compute.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Data access for PII audit log entries.
 * Supports compliance inquiries and NHI traceability reporting.
 * State: Stateless (Spring Data Interface)
 */
@Repository
public interface PiiAuditLogRepository extends JpaRepository<PiiAuditLogEntity, UUID> {

    /**
     * @summary Tenant-scoped audit entries for the admin viewer, newest first. The {@code org_id}
     *     filter is the tenant-isolation boundary — the REST endpoint MUST use this (never
     *     {@code findAll}) so one org cannot read another's PII-scrub history.
     */
    List<PiiAuditLogEntity> findByOrgIdOrderByCreatedAtDesc(String orgId);

    /**
     * @summary Tenant-scoped + agent-filtered audit entries. Keeping {@code orgId} in the query means
     *     a foreign {@code agentId} yields zero rows (no cross-tenant existence leak / IDOR).
     */
    List<PiiAuditLogEntity> findByOrgIdAndAgentIdOrderByCreatedAtDesc(String orgId, String agentId);
}
