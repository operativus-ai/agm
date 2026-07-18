package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link RouteSelectorType#fromString} parsing for every enum value
 * (case-insensitive) plus null / unknown returning {@code null}. The
 * router-step dispatcher (REQ-DR-4 PR-3) uses {@code null} as the signal to
 * fall through to AGENT behavior on a malformed routerConfig.
 */
public class RouteSelectorTypeTest {

    @Test
    void fromString_ruleLowercase_returnsRule() {
        assertEquals(RouteSelectorType.RULE, RouteSelectorType.fromString("rule"));
    }

    @Test
    void fromString_llmMixedCase_returnsLlm() {
        assertEquals(RouteSelectorType.LLM, RouteSelectorType.fromString("Llm"));
    }

    @Test
    void fromString_hitlUppercase_returnsHitl() {
        assertEquals(RouteSelectorType.HITL, RouteSelectorType.fromString("HITL"));
    }

    @Test
    void fromString_null_returnsNull() {
        assertNull(RouteSelectorType.fromString(null));
    }

    @Test
    void fromString_unknown_returnsNull() {
        assertNull(RouteSelectorType.fromString("MAGIC"));
    }

    @Test
    void fromString_blank_returnsNull() {
        assertNull(RouteSelectorType.fromString(""));
    }
}
