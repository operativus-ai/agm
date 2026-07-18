package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.Evaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for Agent Evaluation definitions.
 * State: Stateless
 */
@Repository
public interface EvaluationRepository extends JpaRepository<Evaluation, String> {
    
    /**
     * @summary Retrieves a list of evaluations targeted against a specific agent ID.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<Evaluation> findByAgentId(String agentId);
}
