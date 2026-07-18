package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AlertIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertIntegrationRepository extends JpaRepository<AlertIntegration, String> {

    List<AlertIntegration> findByOrgId(String orgId);

    java.util.Optional<AlertIntegration> findByIdAndOrgId(String id, String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);

    List<AlertIntegration> findByOrgIdAndEnabledTrue(String orgId);

    @Query("SELECT a FROM AlertIntegration a WHERE a.enabled = true " +
           "AND a.retryCount > 0 AND a.retryCount < :maxAttempts " +
           "AND a.nextRetryAt IS NOT NULL AND a.nextRetryAt <= :now " +
           "AND a.pendingPayload IS NOT NULL")
    List<AlertIntegration> findPendingRetryCandidates(@Param("now") LocalDateTime now,
                                                      @Param("maxAttempts") int maxAttempts);
}
