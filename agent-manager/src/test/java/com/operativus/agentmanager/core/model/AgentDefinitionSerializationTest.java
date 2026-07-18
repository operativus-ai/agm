package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import com.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import com.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that AgentDefinition correctly serializes and deserializes
 * the preHooks and postHooks fields through Jackson, confirming the
 * fix for the previous silent data loss issue.
 */
class AgentDefinitionSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("preHooks and postHooks survive JSON round-trip deserialization")
    void testHookFieldsRoundTrip() throws Exception {
        String json = """
                {
                    "agentId": "test-agent-001",
                    "name": "Test Agent",
                    "description": "A test agent",
                    "instructions": "Be helpful",
                    "model": "gemini-2.5-pro",
                    "contextWindowSize": 128000,
                    "memoryEnabled": true,
                    "addHistoryToMessages": true,
                    "tools": ["search_knowledge_base"],
                    "isReasoningEnabled": false,
                    "isTeam": false,
                    "requiresPiiRedaction": false,
                    "approvedForProduction": false,
                    "maintenanceMode": false,
                    "active": true,
                    "enforceJsonOutput": false,
                    "preHooks": ["ext-pii-scrubber", "ext-telemetry"],
                    "postHooks": ["ext-logging-webhook"]
                }
                """;

        AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

        assertThat(def.preHooks()).isNotNull();
        assertThat(def.preHooks()).containsExactly("ext-pii-scrubber", "ext-telemetry");
        assertThat(def.postHooks()).isNotNull();
        assertThat(def.postHooks()).containsExactly("ext-logging-webhook");
    }

    @Test
    @DisplayName("Missing preHooks/postHooks deserialize as null (not an error)")
    void testHookFieldsMissing() throws Exception {
        String json = """
                {
                    "agentId": "test-agent-002",
                    "name": "Minimal Agent",
                    "description": "No hooks",
                    "instructions": "Do nothing",
                    "model": "gemini-2.5-flash",
                    "isReasoningEnabled": false,
                    "isTeam": false,
                    "requiresPiiRedaction": false,
                    "approvedForProduction": false,
                    "maintenanceMode": false,
                    "active": true,
                    "enforceJsonOutput": false
                }
                """;

        AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

        assertThat(def.preHooks()).isNull();
        assertThat(def.postHooks()).isNull();
    }

    @Test
    @DisplayName("Empty preHooks/postHooks arrays deserialize correctly")
    void testHookFieldsEmptyArrays() throws Exception {
        String json = """
                {
                    "agentId": "test-agent-003",
                    "name": "Empty Hooks Agent",
                    "description": "Has empty hook arrays",
                    "instructions": "Be helpful",
                    "model": "gpt-4o",
                    "isReasoningEnabled": false,
                    "isTeam": false,
                    "requiresPiiRedaction": false,
                    "approvedForProduction": false,
                    "maintenanceMode": false,
                    "active": true,
                    "enforceJsonOutput": false,
                    "preHooks": [],
                    "postHooks": []
                }
                """;

        AgentDefinition def = mapper.readValue(json, AgentDefinition.class);

        assertThat(def.preHooks()).isNotNull().isEmpty();
        assertThat(def.postHooks()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("AgentDefinition serialization preserves hook fields")
    void testSerializationPreservesHooks() throws Exception {
        AgentDefinition def = new AgentDefinition(
                "agent-123", "My Agent", "Desc", "Be helpful", "gemini-2.5-pro",
                128000, true, true, List.of("tool1"), false, false, null,
                null, null, false, false, false, true, null,
                null, null, null, null, null, null, null,
                false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                List.of("hook-a", "hook-b"), List.of("hook-c"),
                false, null, null
        , null);

        String json = mapper.writeValueAsString(def);
        AgentDefinition deserialized = mapper.readValue(json, AgentDefinition.class);

        assertThat(deserialized.preHooks()).containsExactly("hook-a", "hook-b");
        assertThat(deserialized.postHooks()).containsExactly("hook-c");
    }

    @Test
    @DisplayName("humanReview round-trips through JSON")
    void testHumanReviewRoundTrip() throws Exception {
        HumanReview review = new HumanReview(
                true,                       // requiresConfirmation
                false,                      // requiresUserInput
                false,                      // requiresOutputReview
                OnRejectPolicy.SKIP,
                OnTimeoutPolicy.AUTO_REJECT,
                OnErrorPolicy.CANCEL,
                30L,                        // timeoutSeconds
                List.of("ops-admin"),
                null                        // elseStepId
        );

        AgentDefinition def = new AgentDefinition(
                "agent-456", "Reviewable", "Desc", "Be helpful", "gemini-2.5-pro",
                null, null, null, null, false, false, null,
                null, null, false, false, false, true, null,
                null, null, null, null, null, null, null,
                false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null,
                false, null, review
        , null);

        String json = mapper.writeValueAsString(def);
        AgentDefinition deserialized = mapper.readValue(json, AgentDefinition.class);

        assertThat(deserialized.humanReview()).isNotNull();
        assertThat(deserialized.humanReview().requiresConfirmation()).isTrue();
        assertThat(deserialized.humanReview().approvers()).containsExactly("ops-admin");
        assertThat(deserialized.humanReview().timeoutSeconds()).isEqualTo(30L);
    }

    @Test
    @DisplayName("withHumanReview returns a copy with the new value and preserves other fields")
    void testWithHumanReviewCopy() {
        AgentDefinition base = new AgentDefinition(
                "agent-789", "Base", "Desc", "Be helpful", "gpt-4o",
                null, null, null, null, false, false, null,
                null, null, false, false, false, true, null,
                null, null, null, null, null, null, null,
                false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null,
                false, null, null
        , null);

        HumanReview override = new HumanReview(
                true, null, null,
                OnRejectPolicy.CANCEL, null, null,
                null, null, null);
        AgentDefinition overridden = base.withHumanReview(override);

        assertThat(overridden).isNotSameAs(base);
        assertThat(overridden.humanReview()).isSameAs(override);
        assertThat(overridden.id()).isEqualTo(base.id());
        assertThat(overridden.modelId()).isEqualTo(base.modelId());
        assertThat(base.humanReview()).isNull();
    }

    @Test
    @DisplayName("gap #8 — fallbackModelIds round-trips through JSON")
    void testFallbackModelIdsRoundTrip() throws Exception {
        AgentDefinition def = new AgentDefinition(
                "agent-fallback", "My Agent", "Desc", "Be helpful", "gpt-4o",
                null, null, null, null, false, false, null,
                null, null, false, false, false, true, null,
                null, null, null, null, null, null, null,
                false, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null,
                false, List.of("gemini-2.5-pro", "claude-3.5-sonnet"), null
        , null);

        String json = mapper.writeValueAsString(def);
        AgentDefinition deserialized = mapper.readValue(json, AgentDefinition.class);

        assertThat(deserialized.fallbackModelIds())
                .containsExactly("gemini-2.5-pro", "claude-3.5-sonnet");
    }

    @Test
    @DisplayName("gap #8 — missing fallbackModelIds deserializes as null")
    void testFallbackModelIdsMissing() throws Exception {
        String json = """
                {
                    "agentId": "agent-no-fb",
                    "name": "No Fallback",
                    "description": "Has no fallback chain",
                    "instructions": "Be helpful",
                    "model": "gpt-4o",
                    "isReasoningEnabled": false,
                    "isTeam": false,
                    "requiresPiiRedaction": false,
                    "approvedForProduction": false,
                    "maintenanceMode": false,
                    "active": true,
                    "enforceJsonOutput": false
                }
                """;
        AgentDefinition def = mapper.readValue(json, AgentDefinition.class);
        assertThat(def.fallbackModelIds()).isNull();
    }
}
