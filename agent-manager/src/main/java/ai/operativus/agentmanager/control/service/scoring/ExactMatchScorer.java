package ai.operativus.agentmanager.control.service.scoring;

import ai.operativus.agentmanager.core.entity.EvaluationCase;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Domain Responsibility: Evaluates if an Agent's output exactly matches a predefined expected string.
 * State: Stateless
 * Dependencies: None
 */
@Component
public class ExactMatchScorer implements Scorer {

    /**
     * @summary Scores an evaluation case based on strict string equality.
     * @logic
     * - Validates that expected and actual outputs are non-empty.
     * - Performs a case-insensitive trim comparison.
     * - Assigns a score of 1.0 for a match, 0.0 otherwise.
     */
    @Override
    public ScorerResult evaluate(EvaluationCase evaluationCase, String actualOutput) {
        if (!StringUtils.hasText(evaluationCase.getExpectedOutput())) {
            return new ScorerResult(0.0, false, "Expected output is empty.");
        }

        if (!StringUtils.hasText(actualOutput)) {
            return new ScorerResult(0.0, false, "Actual output is empty.");
        }

        boolean passed = actualOutput.trim().equalsIgnoreCase(evaluationCase.getExpectedOutput().trim());
        double score = passed ? 1.0 : 0.0;
        String reasoning = passed ? "Exact match." : "Output did not exactly match expected criteria.";

        return new ScorerResult(score, passed, reasoning);
    }
}
