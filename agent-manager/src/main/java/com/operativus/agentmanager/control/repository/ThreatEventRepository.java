package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.ThreatEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ThreatEventRepository extends JpaRepository<ThreatEventEntity, String> {
}
