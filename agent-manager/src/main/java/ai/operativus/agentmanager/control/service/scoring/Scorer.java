package ai.operativus.agentmanager.control.service.scoring;

import ai.operativus.agentmanager.core.entity.EvaluationCase;

/**
 * Domain Responsibility: Core interface for evaluation strategies that score Agent outputs against expected criteria.
 * State: Stateless
 * Dependencies: None
 */
public interface Scorer {
    
    /**
     * @summary Evaluates the actual output against the expected output/criteria defined in the test case.
     * @logic
     * - Implementation-specific scoring algorithm.
     * 
     */
    ScorerResult evaluate(EvaluationCase evaluationCase, String actualOutput);

    record ScorerResult(double score, boolean passed, String reasoning) {}
}
