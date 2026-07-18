package ai.operativus.agentmanager.control.repository;

import ai.operativus.agentmanager.core.entity.EvaluationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Domain Responsibility: Manages persistence and retrieval operations for the Results of individual Evaluation Runs.
 * State: Stateless
 */
@Repository
public interface EvaluationResultRepository extends JpaRepository<EvaluationResult, String> {
    
    /**
     * @summary Retrieves a list of evaluation results associated with a specific evaluation run.
     * @logic Derived query executed by Spring Data JPA framework.
     */
    List<EvaluationResult> findByRunId(String runId);
}
