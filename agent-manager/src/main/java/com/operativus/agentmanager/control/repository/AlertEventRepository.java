package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.AlertEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, String> {
    Page<AlertEvent> findByAcknowledgedFalseOrderByFiredAtDesc(Pageable pageable);
    List<AlertEvent> findByRuleIdOrderByFiredAtDesc(String ruleId);

    boolean existsByRuleIdAndFiredAtAfter(String ruleId, LocalDateTime cutoff);

    Page<AlertEvent> findByAcknowledgedFalseAndOrgIdOrderByFiredAtDesc(String orgId, Pageable pageable);

    Optional<AlertEvent> findByIdAndOrgId(String id, String orgId);
}
