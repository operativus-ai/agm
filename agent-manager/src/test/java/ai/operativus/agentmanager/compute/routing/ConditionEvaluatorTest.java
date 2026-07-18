package ai.operativus.agentmanager.compute.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared workflow CONDITION / LOOP-{@code until:} grammar in {@link ConditionEvaluator},
 * with emphasis on the DAG-4d fail-closed contract: a recognized-but-unparseable expression or an
 * unrecognized prefix must evaluate to {@code false} (previously {@code true}, which silently
 * activated a branch the author never intended). The {@code llm:} branch is passed a null evaluator
 * here so its no-key fail-closed path is covered too; the live classifier is exercised in
 * integration coverage.
 */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator(null);

    // --- DAG-4d fail-closed contract ---------------------------------------

    @Test
    void unknownPrefix_isFalse() {
        assertFalse(evaluator.evaluate("definitely_not_a_prefix:done", "done"));
        assertFalse(evaluator.evaluate("== done", "done"));
        assertFalse(evaluator.evaluate("cel:output == 'done'", "done"));
    }

    @Test
    void unparseableLengthGreater_isFalse() {
        assertFalse(evaluator.evaluate("length>abc", "any input"));
        assertFalse(evaluator.evaluate("length>", "any input"));
    }

    @Test
    void unparseableLengthLess_isFalse() {
        assertFalse(evaluator.evaluate("length<xyz", "any input"));
        assertFalse(evaluator.evaluate("length<", "any input"));
    }

    @Test
    void llmPrefix_withoutEvaluator_isFalse() {
        assertFalse(evaluator.evaluate("llm:is the customer angry?", "I want a refund"));
    }

    // --- Null contract preserved (absent gate / absent data passes through) --

    @Test
    void nullExpression_isTrue() {
        assertTrue(evaluator.evaluate(null, "some input"));
    }

    @Test
    void nullInput_isTrue() {
        assertTrue(evaluator.evaluate("contains:done", null));
    }

    // --- Recognized-path sanity (grammar lock) ------------------------------

    @Test
    void contains_matchesCaseInsensitively() {
        assertTrue(evaluator.evaluate("contains:DONE", "task is done"));
        assertFalse(evaluator.evaluate("contains:done", "task is pending"));
    }

    @Test
    void notContains_negates() {
        assertTrue(evaluator.evaluate("not_contains:error", "all good"));
        assertFalse(evaluator.evaluate("not_contains:error", "an error occurred"));
    }

    @Test
    void lengthCompare_parsesValidBounds() {
        assertTrue(evaluator.evaluate("length>3", "abcd"));
        assertFalse(evaluator.evaluate("length>3", "ab"));
        assertTrue(evaluator.evaluate("length<3", "ab"));
        assertFalse(evaluator.evaluate("length<3", "abcd"));
    }

    @Test
    void emptyAndNotEmpty_checkBlankness() {
        assertTrue(evaluator.evaluate("not_empty", "x"));
        assertFalse(evaluator.evaluate("not_empty", "   "));
        assertTrue(evaluator.evaluate("empty", "   "));
        assertFalse(evaluator.evaluate("empty", "x"));
    }
}
