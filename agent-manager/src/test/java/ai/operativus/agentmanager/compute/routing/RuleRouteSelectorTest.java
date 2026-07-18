package ai.operativus.agentmanager.compute.routing;

import ai.operativus.agentmanager.core.model.RouterStepConfig;
import ai.operativus.agentmanager.core.model.enums.RouteSelectorType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link RuleRouteSelector} behavior: JSONPath-on-JSON happy path,
 * non-JSON wrapped-as-text fallback, and every defensive defaultChoice
 * branch (path-not-found, parse error, unmatched leaf, blank expression).
 */
class RuleRouteSelectorTest {

    private final RuleRouteSelector selector = new RuleRouteSelector();

    private static RouterStepConfig cfg(String expr, Map<String, String> choices, String defaultKey) {
        return new RouterStepConfig(RouteSelectorType.RULE, expr, choices, defaultKey);
    }

    @Test
    void selectorType_isRule() {
        assertEquals(RouteSelectorType.RULE, selector.selectorType());
    }

    @Test
    void selectChoice_jsonOutputLeafMatchesChoiceKey_returnsKey() {
        String prior = "{\"decision\":\"approve\",\"score\":0.92}";
        RouterStepConfig c = cfg("$.decision", Map.of("approve", "step-a", "reject", "step-b"), null);

        assertEquals("approve", selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_jsonLeafNotInChoices_returnsDefaultChoice() {
        String prior = "{\"decision\":\"escalate\"}";
        RouterStepConfig c = cfg("$.decision", Map.of("approve", "step-a"), "approve");

        assertEquals("approve", selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_jsonLeafNotInChoicesAndNoDefault_returnsNull() {
        String prior = "{\"decision\":\"escalate\"}";
        RouterStepConfig c = cfg("$.decision", Map.of("approve", "step-a"), null);

        assertNull(selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_jsonPathMissing_returnsDefaultChoice() {
        String prior = "{\"other\":\"value\"}";
        RouterStepConfig c = cfg("$.missing", Map.of("a", "step-a"), "a");

        assertEquals("a", selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_plainTextPriorOutput_resolvesViaWrappedTextPath() {
        // Free-text agent output gets wrapped as {"text": "..."} so authors can
        // write $.text-based JSONPath expressions. Here we use a literal match
        // by extracting the wrapped field then checking the choices map.
        String prior = "approve";
        RouterStepConfig c = cfg("$.text", Map.of("approve", "step-a", "reject", "step-b"), null);

        assertEquals("approve", selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_plainTextNotInChoices_returnsDefault() {
        String prior = "wat";
        RouterStepConfig c = cfg("$.text", Map.of("approve", "step-a"), "approve");

        assertEquals("approve", selector.selectChoice(c, prior));
    }

    @Test
    void selectChoice_priorOutputNull_returnsDefaultViaPathNotFound() {
        RouterStepConfig c = cfg("$.decision", Map.of("a", "step-a"), "a");

        assertEquals("a", selector.selectChoice(c, null));
    }

    @Test
    void selectChoice_blankSelectorExpression_returnsDefaultChoice() {
        RouterStepConfig c = cfg("   ", Map.of("a", "step-a"), "a");

        assertEquals("a", selector.selectChoice(c, "{}"));
    }

    @Test
    void selectChoice_malformedJsonPathExpression_returnsDefault() {
        // "$$invalid" is not a valid JSONPath — defensive: surface as defaultChoice.
        RouterStepConfig c = cfg("$$invalid", Map.of("a", "step-a"), "a");

        assertEquals("a", selector.selectChoice(c, "{\"decision\":\"a\"}"));
    }

    @Test
    void selectChoice_nullConfig_throwsExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> selector.selectChoice(null, "{}"));
    }

    @Test
    void selectChoice_textWithJsonSpecialChars_isEscapedAndMatches() {
        // The wrapper must escape quotes / backslashes / control chars so the
        // wrapped document stays parseable. {"text": "approve\nme"} → resolves to
        // "approve\nme" which is not a choice key → returns default.
        String prior = "approve\nme";
        RouterStepConfig c = cfg("$.text", Map.of("approve", "step-a"), "approve");

        assertEquals("approve", selector.selectChoice(c, prior));
    }
}
