package com.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.core.entity.ModelEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Provider-realism canary for {@link AnthropicModelProvider}. Verifies the
 *   bean builds a Spring AI {@link org.springframework.ai.anthropic.AnthropicChatModel} that
 *   (1) parses a real Anthropic v1 Messages-API response and (2) sends a request whose shape
 *   matches the public spec — {@code model} + {@code messages} body, {@code x-api-key} header.
 *   {@code FakeChatModel} bypasses the real Spring AI provider class entirely and cannot catch
 *   SDK upgrades that break the wire contract; this test does.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class AnthropicModelProviderRealismTest {

    private static WireMockServer wireMock;
    private AnthropicModelProvider provider;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setupProvider() {
        ProviderCredentialOperations creds = (orgId, provider) -> java.util.Optional.empty();
        ObjectProvider<ToolCallingManager> tcmProvider = mock(ObjectProvider.class);
        when(tcmProvider.getObject()).thenReturn(DefaultToolCallingManager.builder().build());
        provider = new AnthropicModelProvider(creds, tcmProvider);
    }

    @Test
    void buildChatModelRoundtripsRealAnthropicMessagesWireFormat() {
        // Canonical Anthropic POST /v1/messages response. Shape pinned by the public spec at
        // https://docs.anthropic.com/en/api/messages — drift here surfaces as parse failure
        // rather than the silent-pass that FakeChatModel offers.
        String anthropicResponse = """
                {
                  "id": "msg_test_9NLkX",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-3-5-sonnet-20241022",
                  "content": [
                    { "type": "text", "text": "The capital of France is Paris." }
                  ],
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {
                    "input_tokens": 12,
                    "output_tokens": 7
                  }
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("anthropic");
        model.setName("test-anthropic-model");
        model.setModelName("claude-3-5-sonnet-20241022");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");
        model.setMaxOutputTokens(256);

        ChatModel chatModel = provider.buildChatModel(model, null);
        ChatResponse response = chatModel.call(new Prompt(new UserMessage("What is the capital of France?")));

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("The capital of France is Paris.");

        var usage = response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(12);
        assertThat(usage.getCompletionTokens()).isEqualTo(7);
        assertThat(usage.getTotalTokens()).isEqualTo(19);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-3-5-sonnet-20241022")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        equalTo("What is the capital of France?"))));
    }
}
