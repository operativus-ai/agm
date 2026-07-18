package com.operativus.agentmanager.core.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link StepActionType#fromString} parsing for every enum value (case-insensitive)
 * plus the null / unknown fallback to AGENT. REQ-DR-5 additions (SEQUENTIAL, ROUTER) are
 * explicitly covered so a string set from a future workflow DAG payload parses to the
 * intended enum constant rather than silently defaulting to AGENT.
 */
public class StepActionTypeTest {

    @Test
    void fromString_agentLowercase_returnsAgent() {
        assertEquals(StepActionType.AGENT, StepActionType.fromString("agent"));
    }

    @Test
    void fromString_conditionMixedCase_returnsCondition() {
        assertEquals(StepActionType.CONDITION, StepActionType.fromString("Condition"));
    }

    @Test
    void fromString_parallelUppercase_returnsParallel() {
        assertEquals(StepActionType.PARALLEL, StepActionType.fromString("PARALLEL"));
    }

    @Test
    void fromString_webhook_returnsWebhook() {
        assertEquals(StepActionType.WEBHOOK, StepActionType.fromString("webhook"));
    }

    @Test
    void fromString_loop_returnsLoop() {
        assertEquals(StepActionType.LOOP, StepActionType.fromString("LOOP"));
    }

    @Test
    void fromString_sequential_returnsSequential() {
        // REQ-DR-5 addition — explicit linear marker for serialized DAGs.
        assertEquals(StepActionType.SEQUENTIAL, StepActionType.fromString("SEQUENTIAL"));
    }

    @Test
    void fromString_sequentialLowercase_returnsSequential() {
        assertEquals(StepActionType.SEQUENTIAL, StepActionType.fromString("sequential"));
    }

    @Test
    void fromString_router_returnsRouter() {
        // REQ-DR-5 addition — placeholder for REQ-DR-4 (Workflow Router step). Parsing
        // must round-trip so the upcoming dispatcher branch sees the intended value
        // rather than the AGENT fallback.
        assertEquals(StepActionType.ROUTER, StepActionType.fromString("ROUTER"));
    }

    @Test
    void fromString_routerMixedCase_returnsRouter() {
        assertEquals(StepActionType.ROUTER, StepActionType.fromString("Router"));
    }

    @Test
    void fromString_null_returnsAgentFallback() {
        assertEquals(StepActionType.AGENT, StepActionType.fromString(null));
    }

    @Test
    void fromString_unknown_returnsAgentFallback() {
        assertEquals(StepActionType.AGENT, StepActionType.fromString("GARBAGE"));
    }

    @Test
    void fromString_blank_returnsAgentFallback() {
        // Blank string is also unknown — the catch path falls back to AGENT.
        assertEquals(StepActionType.AGENT, StepActionType.fromString(""));
    }
}
