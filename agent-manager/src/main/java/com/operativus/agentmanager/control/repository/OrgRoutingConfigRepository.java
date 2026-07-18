package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.OrgRoutingConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Domain Responsibility: Persistence for {@link OrgRoutingConfig}. One row per org;
 *     {@code findByOrgId} is the canonical entry point for the universal-dispatch
 *     resolver. All access is tenant-scoped by the {@code org_id} unique constraint.
 * State: Stateless
 */
@Repository
public interface OrgRoutingConfigRepository extends JpaRepository<OrgRoutingConfig, String> {

    Optional<OrgRoutingConfig> findByOrgId(String orgId);

    boolean existsByOrgId(String orgId);
}
