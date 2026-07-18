package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Domain Responsibility: Persistence boundary for {@link RoutingDecisionEntity}. Append-only
 *     by trigger contract — callers must not invoke {@code save} on an existing row.
 * State: Stateless
 */
public interface RoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, String> {

    Page<RoutingDecisionEntity> findAllByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    Page<RoutingDecisionEntity> findAllByOrgIdAndStrategyUsedOrderByCreatedAtDesc(
            String orgId, RoutingDecisionEntity.StrategyUsed strategyUsed, Pageable pageable);

    Optional<RoutingDecisionEntity> findByIdAndOrgId(String id, String orgId);
}
