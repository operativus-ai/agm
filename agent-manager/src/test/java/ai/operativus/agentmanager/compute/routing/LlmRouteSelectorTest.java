package ai.operativus.agentmanager.compute.routing;

import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure helpers of {@link LlmRouteSelector}: prompt construction and
 * LLM-response → choice-key resolution. The live ChatClient call is exercised
 * end-to-end in PR-6 (FakeChatModel-driven integration test).
 */
class LlmRouteSelectorTest {

    private static RouterStepConfig cfg(String expr, Map<String, String> choices, String defaultKey) {
        return new RouterStepConfig(RouteSelectorType.LLM, expr, choices, defaultKey);
    }

    // ---- resolveChoice ----

    @Test
    void resolveChoice_exactMatch_returnsKey() {
        RouterStepConfig c = cfg("classify", Map.of("approve", "step-a", "reject", "step-b"), null);
        assertEquals("approve", LlmRouteSelector.resolveChoice("approve", c));
    }

    @Test
    void resolveChoice_withSurroundingWhitespace_returnsKey() {
        RouterStepConfig c = cfg("classify", Map.of("approve", "step-a"), null);
        assertEquals("approve", LlmRouteSelector.resolveChoice("  approve\n", c));
    }

    @Test
    void resolveChoice_caseInsensitiveMatch_returnsCanonicalKey() {
        RouterStepConfig c = cfg("classify", Map.of("Approve", "step-a", "Reject", "step-b"), null);
        assertEquals("Approve", LlmRouteSelector.resolveChoice("approve", c));
        assertEquals("Reject", LlmRouteSelector.resolveChoice("REJECT", c));
    }

    @Test
    void resolveChoice_noMatch_returnsDefaultChoice() {
        RouterStepConfig c = cfg("classify", Map.of("a", "step-a"), "a");
        assertEquals("a", LlmRouteSelector.resolveChoice("escalate", c));
    }

    @Test
    void resolveChoice_noMatchAndNoDefault_returnsNull() {
        RouterStepConfig c = cfg("classify", Map.of("a", "step-a"), null);
        assertNull(LlmRouteSelector.resolveChoice("escalate", c));
    }

    @Test
    void resolveChoice_nullResponse_returnsDefault() {
        RouterStepConfig c = cfg("classify", Map.of("a", "step-a"), "a");
        assertEquals("a", LlmRouteSelector.resolveChoice(null, c));
    }

    @Test
    void resolveChoice_nullChoicesMap_returnsDefault() {
        RouterStepConfig c = cfg("classify", null, "fallback");
        assertEquals("fallback", LlmRouteSelector.resolveChoice("approve", c));
    }

    // ---- buildPrompt ----

    @Test
    void buildPrompt_includesInstructionChoicesAndOutput() {
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("approve", "step-a");
        choices.put("reject", "step-b");
        RouterStepConfig c = cfg("Is the request sensitive?", choices, null);

        String prompt = LlmRouteSelector.buildPrompt(c, "The customer asked for a refund.");

        assertTrue(prompt.contains("Is the request sensitive?"), "prompt missing instruction");
        assertTrue(prompt.contains("approve"), "prompt missing 'approve' choice");
        assertTrue(prompt.contains("reject"), "prompt missing 'reject' choice");
        assertTrue(prompt.contains("The customer asked for a refund."), "prompt missing prior output");
        assertTrue(prompt.contains("Respond with EXACTLY one category name"),
                "prompt missing output guard");
    }

    @Test
    void buildPrompt_blankInstruction_usesDefaultInstruction() {
        RouterStepConfig c = cfg("   ", Map.of("a", "step-a"), null);
        String prompt = LlmRouteSelector.buildPrompt(c, "output");
        assertTrue(prompt.contains("Classify the prior output"),
                "blank instruction should fall back to default classify text");
    }

    @Test
    void buildPrompt_nullInstruction_usesDefaultInstruction() {
        RouterStepConfig c = cfg(null, Map.of("a", "step-a"), null);
        String prompt = LlmRouteSelector.buildPrompt(c, "output");
        assertTrue(prompt.contains("Classify the prior output"),
                "null instruction should fall back to default classify text");
    }

    @Test
    void buildPrompt_nullPriorOutput_omitsGracefully() {
        RouterStepConfig c = cfg(null, Map.of("a", "step-a"), null);
        String prompt = LlmRouteSelector.buildPrompt(c, null);
        // Should not contain "null" literal as the payload — empty payload tolerated.
        assertTrue(prompt.contains("Prior output:"), "prompt missing 'Prior output:' label");
        assertTrue(!prompt.contains("Prior output:\nnull"), "literal 'null' leaked into prompt");
    }
}
