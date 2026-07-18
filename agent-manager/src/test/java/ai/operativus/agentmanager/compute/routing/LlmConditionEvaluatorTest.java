package ai.operativus.agentmanager.compute.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure helpers of {@link LlmConditionEvaluator}: prompt construction
 * and boolean parsing. The live ChatClient call is exercised end-to-end via
 * the workflow CONDITION dispatcher in integration coverage (separate PR).
 */
class LlmConditionEvaluatorTest {

    @Test
    void parseBoolean_yes_isTrue() {
        assertTrue(LlmConditionEvaluator.parseBoolean("yes"));
        assertTrue(LlmConditionEvaluator.parseBoolean("Yes"));
        assertTrue(LlmConditionEvaluator.parseBoolean("YES"));
        assertTrue(LlmConditionEvaluator.parseBoolean("  yes  "));
        assertTrue(LlmConditionEvaluator.parseBoolean("yes."));
        assertTrue(LlmConditionEvaluator.parseBoolean("yes, definitely"));
    }

    @Test
    void parseBoolean_true_isTrue() {
        assertTrue(LlmConditionEvaluator.parseBoolean("true"));
        assertTrue(LlmConditionEvaluator.parseBoolean("True"));
    }

    @Test
    void parseBoolean_shortForms_areTrue() {
        assertTrue(LlmConditionEvaluator.parseBoolean("y"));
        assertTrue(LlmConditionEvaluator.parseBoolean("1"));
    }

    @Test
    void parseBoolean_no_isFalse() {
        assertFalse(LlmConditionEvaluator.parseBoolean("no"));
        assertFalse(LlmConditionEvaluator.parseBoolean("No"));
        assertFalse(LlmConditionEvaluator.parseBoolean("false"));
        assertFalse(LlmConditionEvaluator.parseBoolean("n"));
        assertFalse(LlmConditionEvaluator.parseBoolean("0"));
    }

    @Test
    void parseBoolean_unparseable_isFalse() {
        assertFalse(LlmConditionEvaluator.parseBoolean(""));
        assertFalse(LlmConditionEvaluator.parseBoolean("   "));
        assertFalse(LlmConditionEvaluator.parseBoolean("..."));
        assertFalse(LlmConditionEvaluator.parseBoolean(null));
    }

    @Test
    void parseBoolean_maybeOrUnknown_defaultsFalse() {
        // The contract is "yes/true/y/1 → true, everything else → false".
        // Authors who care about ambiguity should reword the question.
        assertFalse(LlmConditionEvaluator.parseBoolean("maybe"));
        assertFalse(LlmConditionEvaluator.parseBoolean("unknown"));
    }

    @Test
    void buildPrompt_includesQuestionContextAndOutputGuard() {
        String prompt = LlmConditionEvaluator.buildPrompt("Is the customer angry?", "I want a refund NOW");
        assertTrue(prompt.contains("Is the customer angry?"), "missing question");
        assertTrue(prompt.contains("I want a refund NOW"), "missing context");
        assertTrue(prompt.contains("Answer with exactly one word: yes or no"),
                "missing output guard");
    }

    @Test
    void buildPrompt_nullPriorOutput_omitsGracefully() {
        String prompt = LlmConditionEvaluator.buildPrompt("Is OK?", null);
        assertTrue(prompt.contains("Is OK?"));
        assertFalse(prompt.contains("Context:\nnull"),
                "literal 'null' must not leak into the prompt context");
    }
}
