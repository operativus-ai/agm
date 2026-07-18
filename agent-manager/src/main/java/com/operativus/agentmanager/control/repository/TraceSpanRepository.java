package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.TraceSpanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TraceSpanRepository extends JpaRepository<TraceSpanEntity, String> {
}
