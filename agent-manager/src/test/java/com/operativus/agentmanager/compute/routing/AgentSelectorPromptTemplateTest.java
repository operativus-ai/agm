package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AgentSelectorPromptTemplateTest {

    private static AgentDefinition agent(String id, String name, String description, List<String> tools) {
        return new AgentDefinition(
                id, name, description, "instructions", "model-x",
                null, null, null, tools,
                false, false, null, null,
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void render_omitsEmptyCapabilitiesAndTools() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "Alpha", "First agent", null)),
                Map.of());

        assertEquals("- Alpha (ID: a-1): First agent", rendered);
    }

    @Test
    void render_includesCapabilitiesWhenPresent() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "Alpha", "Handles tax", null)),
                Map.of("a-1", List.of("tax-questions", "refund-disputes")));

        assertTrue(rendered.contains("[Capabilities: tax-questions, refund-disputes]"),
                "Expected capability brackets in output, got: " + rendered);
    }

    @Test
    void render_includesToolsWhenPresent() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "Alpha", "desc", List.of("web-search", "calculator"))),
                Map.of());

        assertTrue(rendered.contains("[Tools: web-search, calculator]"));
    }

    @Test
    void render_includesBothCapabilitiesAndTools() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "Alpha", "desc", List.of("calculator"))),
                Map.of("a-1", List.of("math")));

        // Order: capabilities first, then tools — both inside square-bracket suffixes
        assertTrue(rendered.contains("[Capabilities: math] [Tools: calculator]"),
                "Expected ordered cap+tools brackets, got: " + rendered);
    }

    @Test
    void render_handlesNullCapsByAgentMap() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "Alpha", "desc", null)),
                null);

        assertEquals("- Alpha (ID: a-1): desc", rendered);
    }

    @Test
    void render_joinsMultipleCandidatesWithNewlines() {
        String rendered = AgentSelectorPromptTemplate.renderCandidates(
                List.of(agent("a-1", "A", "first", null), agent("a-2", "B", "second", null)),
                Map.of());

        assertEquals("- A (ID: a-1): first\n- B (ID: a-2): second", rendered);
    }
}
