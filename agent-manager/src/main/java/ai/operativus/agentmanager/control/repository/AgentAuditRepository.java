package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.AgentAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Agent Audit records.
 * State: Stateless
 *
 * <p><strong>Org-scoping (read vs. delete asymmetry):</strong> {@link #search} filters by
 * {@code orgId} via direct equality on the denormalized {@code agent_audits.org_id} column
 * (Fix C, Liquibase changeset 052), enforcing tenant isolation on every read.
 * {@link #deleteByCreatedAtBefore} operates across all tenants intentionally — it is invoked
 * only by the scheduled retention job which runs with system-level authority, not by any
 * user-facing API path. If a per-tenant retention policy is needed in future, add an
 * {@code orgId} predicate to that query at that time.
 *
 * <p><strong>Post-Fix-C:</strong> {@code org_id} is denormalized onto {@code agent_audits},
 * matching the {@code system_audits} schema. The listing happy path is backed by
 * {@code idx_agent_audits_org_created} on {@code (org_id, created_at DESC)} (index-scan plan).
 * Pre-Fix-C the predicate was an EXISTS subquery against {@code agents}; see Liquibase
 * changeset 052 for the migration history.
 */
@Repository
public interface AgentAuditRepository extends JpaRepository<AgentAuditEntity, String> {

    java.util.List<AgentAuditEntity> findByUsername(String username);

    /**
     * Tenant-scoped, multi-filter search. All optional filters use {@code null} to mean
     * "no restriction" — callers should convert blank strings to null before invoking.
     * {@code orgId} must never be null; the service layer enforces this with
     * {@code Objects.requireNonNull} before reaching here, so the JPQL does NOT carry a
     * {@code :orgId IS NULL} branch (would be unreachable optimizer noise).
     */
    /**
     * Tenant-scoped multi-filter search. Optional string filters use {@code null} to mean
     * "no restriction". Date-range filters use sentinel bounds — callers should pass
     * {@code LocalDateTime.MIN} for an unconstrained lower bound and
     * {@code LocalDateTime.MAX} for an unconstrained upper bound. The service layer
     * handles the null→sentinel conversion ({@code AuditLogService.listAuditLogs}). The
     * sentinel pattern keeps PG's parameter type inference happy without a CAST in the JPQL.
     */
    @Query("""
            SELECT a FROM AgentAuditEntity a
            WHERE a.orgId = :orgId
            AND (:agentId IS NULL OR a.agentId = :agentId)
            AND (:username IS NULL OR a.username = :username)
            AND (:action IS NULL OR a.action = :action)
            AND a.createdAt >= :createdAtFrom
            AND a.createdAt < :createdAtTo
            ORDER BY a.createdAt DESC
            """)
    Page<AgentAuditEntity> search(@Param("orgId") String orgId,
                                   @Param("agentId") String agentId,
                                   @Param("username") String username,
                                   @Param("action") String action,
                                   @Param("createdAtFrom") LocalDateTime createdAtFrom,
                                   @Param("createdAtTo") LocalDateTime createdAtTo,
                                   Pageable pageable);

    /**
     * Retention helper: deletes audit rows whose {@code createdAt} is strictly before
     * the cutoff. {@code agent_audits} has no child tables, so a single JPQL delete suffices.
     * Cross-tenant by design — see class-level Javadoc for the read/delete asymmetry rationale.
     */
    @Modifying
    @Query("DELETE FROM AgentAuditEntity a WHERE a.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
