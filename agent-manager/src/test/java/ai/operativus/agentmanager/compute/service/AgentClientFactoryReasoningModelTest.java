package ai.operativus.agentmanager.compute.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the OpenAI reasoning-model detection in {@link AgentClientFactory#isReasoningModel(String)}.
 *
 * Regression context: o1/o3/o4-family models reject `temperature` and `top_p`
 * (400 "Unsupported parameter: 'temperature' is not supported with this model"),
 * which previously broke every chat with an o-series agent — surfaced in the UI as
 * "Cannot connect to agent stream. (Stream Error: Bad Request)". buildChatOptions
 * omits both sampling params when this returns true, so the boundary must stay tight:
 * o-series in, standard chat models (incl. gpt-4o) out.
 */
class AgentClientFactoryReasoningModelTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "o1", "o1-mini", "o1-preview",
            "o3", "o3-mini",
            "o4-mini",
            "O3-MINI",        // case-insensitive
            "  o3-mini  ",    // surrounding whitespace tolerated
    })
    void detectsOpenAiReasoningModels(String modelId) {
        assertThat(AgentClientFactory.isReasoningModel(modelId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gpt-4o", "gpt-4o-mini", "gpt-4", "gpt-4-turbo", "gpt-3.5-turbo",
            "gemini-2.5-pro", "gemini-2.0-flash",
            "claude-3-5-sonnet", "claude-haiku-4-5",
            "ollama-llama3",
            "omni-model",     // starts with 'o' but not o+digit — must NOT match
    })
    void leavesStandardChatModelsAlone(String modelId) {
        assertThat(AgentClientFactory.isReasoningModel(modelId)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void treatsNullOrBlankAsNonReasoning(String modelId) {
        assertThat(AgentClientFactory.isReasoningModel(modelId)).isFalse();
    }
}
