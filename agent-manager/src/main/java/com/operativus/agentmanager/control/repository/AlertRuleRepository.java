package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {
    /** Scheduler-only: returns enabled rules across ALL orgs. Metrics are global so the
     *  scheduler must evaluate every tenant's rules each tick. Do NOT use from controllers. */
    List<AlertRule> findByEnabledTrue();

    Optional<AlertRule> findByIdAndOrgId(String id, String orgId);

    List<AlertRule> findByOrgId(String orgId);

    boolean existsByIdAndOrgId(String id, String orgId);
}
