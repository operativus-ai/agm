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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Provider-realism canary for {@link GeminiModelProvider}. Verifies the
 *   bean builds a Spring AI {@link org.springframework.ai.google.genai.GoogleGenAiChatModel}
 *   that (1) parses a real Gemini generateContent response and (2) sends a request whose shape
 *   matches the public spec — {@code contents[0].parts[0].text} body, {@code x-goog-api-key}
 *   header. {@code FakeChatModel} bypasses the real Spring AI provider class entirely and
 *   cannot catch SDK upgrades that break the wire contract; this test does.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class GeminiModelProviderRealismTest {

    private static WireMockServer wireMock;
    private GeminiModelProvider provider;

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
        provider = new GeminiModelProvider(creds, tcmProvider);
    }

    @Test
    void buildChatModelRoundtripsRealGeminiGenerateContentWireFormat() {
        // Canonical POST .../v1beta/models/{model}:generateContent response. Shape pinned by
        // https://ai.google.dev/api/generate-content — drift here surfaces as parse failure
        // rather than the silent-pass that FakeChatModel offers.
        String geminiResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [ { "text": "The capital of France is Paris." } ]
                      },
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 12,
                    "candidatesTokenCount": 7,
                    "totalTokenCount": 19
                  },
                  "modelVersion": "gemini-2.0-flash"
                }
                """;

        wireMock.stubFor(post(urlPathMatching("/.*models/.*:generateContent"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("google");
        model.setName("test-gemini-model");
        model.setModelName("gemini-2.0-flash");
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

        wireMock.verify(postRequestedFor(urlPathMatching("/.*models/gemini-2\\.0-flash:generateContent"))
                .withHeader("x-goog-api-key", equalTo("test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].text",
                        equalTo("What is the capital of France?"))));
    }
}
