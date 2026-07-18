package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.SpotBatchJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpotBatchJobRepository extends JpaRepository<SpotBatchJobEntity, String> {

    /**
     * @summary Tenant-scoped list for {@code GET /api/v1/schedules/batches}.
     * @logic Replaces the previous {@code findAll()} dump which leaked cross-tenant
     *        spot-batch rows to any ROLE_ADMIN. F5.
     */
    List<SpotBatchJobEntity> findAllByOrgId(String orgId);
}
