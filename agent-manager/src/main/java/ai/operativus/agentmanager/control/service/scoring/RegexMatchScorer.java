package ai.operativus.agentmanager.control.service.scoring;

import ai.operativus.agentmanager.core.entity.EvaluationCase;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Domain Responsibility: Evaluates if an Agent's output satisfies a predefined Regular Expression.
 * State: Stateless
 * Dependencies: None
 */
@Component
public class RegexMatchScorer implements Scorer {

    /**
     * @summary Scores an evaluation case by applying a Regular Expression against the output.
     * @logic
     * - Validates that the expected output (pattern) and actual output are non-empty.
     * - Compiles the expected output as a Regex Pattern.
     * - Uses a Matcher to find any occurrence of the pattern in the actual output.
     * - Assigns a score of 1.0 if a match is found, 0.0 otherwise or if regex compilation fails.
     */
    @Override
    public ScorerResult evaluate(EvaluationCase evaluationCase, String actualOutput) {
        if (!StringUtils.hasText(evaluationCase.getExpectedOutput())) {
            return new ScorerResult(0.0, false, "Expected regex pattern is empty.");
        }

        if (!StringUtils.hasText(actualOutput)) {
            return new ScorerResult(0.0, false, "Actual output is empty.");
        }

        try {
            Pattern pattern = Pattern.compile(evaluationCase.getExpectedOutput());
            Matcher matcher = pattern.matcher(actualOutput);
            boolean passed = matcher.find();
            double score = passed ? 1.0 : 0.0;
            String reasoning = passed ? "Regex match found." : "Output did not match expected regex pattern.";

            return new ScorerResult(score, passed, reasoning);
        } catch (Exception e) {
            return new ScorerResult(0.0, false, "Invalid regex pattern provided in expected constraints.");
        }
    }
}
