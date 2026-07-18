package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.SystemAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: JPA access to {@code system_audits} — the cross-cutting audit log for
 *   non-agent HTTP mutations and authentication events. Read queries scope by {@code orgId} so
 *   admins of org A never see rows from org B. {@code orgId == null} is treated as "cross-tenant
 *   super-admin view" and returns all rows; the calling service layer is responsible for that
 *   RBAC gate.
 * State: Stateless (Spring Data interface)
 */
@Repository
public interface SystemAuditRepository extends JpaRepository<SystemAuditEntity, String> {

    /**
     * Org-scoped listing with optional equality filters. {@code orgId} is a strict match:
     * passing {@code null} limits the result to rows whose {@code org_id} is null (e.g. pre-auth
     * LOGIN_FAILURE events). Non-null values match exactly. Never returns cross-tenant rows —
     * admin of org A cannot read rows from org B (matrix §24 case 6).
     *
     * <p>{@code username}, {@code action}, {@code resourceType}, {@code resourceId} are
     * optional — {@code null} means "any".</p>
     */
    @Query("""
            SELECT s FROM SystemAuditEntity s
             WHERE ((:orgId IS NULL AND s.orgId IS NULL) OR (:orgId IS NOT NULL AND s.orgId = :orgId))
               AND (:username IS NULL OR s.username = :username)
               AND (:action IS NULL OR s.action = :action)
               AND (:resourceType IS NULL OR s.resourceType = :resourceType)
               AND (:resourceId IS NULL OR s.resourceId = :resourceId)
             ORDER BY s.createdAt DESC
            """)
    Page<SystemAuditEntity> search(@Param("orgId") String orgId,
                                   @Param("username") String username,
                                   @Param("action") String action,
                                   @Param("resourceType") String resourceType,
                                   @Param("resourceId") String resourceId,
                                   Pageable pageable);

    int deleteByCreatedAtBefore(LocalDateTime cutoff);
}
