package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trips {@link RouterStepConfig} through Jackson — the same shape that
 * lands in the {@code workflow_steps.router_config} JSONB column via Hibernate's
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. Any field-name drift surfaces here
 * before it reaches a DB write.
 */
public class RouterStepConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_ruleSelector_preservesAllFields() throws Exception {
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("approve", "step-approve-1");
        choices.put("reject", "step-reject-2");
        RouterStepConfig original = new RouterStepConfig(
                RouteSelectorType.RULE, "$.decision", choices, "approve");

        String json = mapper.writeValueAsString(original);
        RouterStepConfig parsed = mapper.readValue(json, RouterStepConfig.class);

        assertEquals(RouteSelectorType.RULE, parsed.selectorType());
        assertEquals("$.decision", parsed.selectorExpression());
        assertEquals(choices, parsed.choices());
        assertEquals("approve", parsed.defaultChoice());
    }

    @Test
    void roundTrip_hitlSelector_nullSelectorExpressionAndDefaultPreserved() throws Exception {
        RouterStepConfig original = new RouterStepConfig(
                RouteSelectorType.HITL, null, Map.of("a", "step-a", "b", "step-b"), null);

        String json = mapper.writeValueAsString(original);
        RouterStepConfig parsed = mapper.readValue(json, RouterStepConfig.class);

        assertEquals(RouteSelectorType.HITL, parsed.selectorType());
        assertNull(parsed.selectorExpression());
        assertEquals(2, parsed.choices().size());
        assertNull(parsed.defaultChoice());
    }

    @Test
    void deserialize_unknownSelectorTypeString_failsExplicitly() {
        String bad = "{\"selectorType\":\"GARBAGE\",\"choices\":{\"x\":\"step-x\"}}";
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mapper.readValue(bad, RouterStepConfig.class));
    }
}
