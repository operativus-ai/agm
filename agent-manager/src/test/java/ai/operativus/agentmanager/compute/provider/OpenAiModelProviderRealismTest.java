package ai.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.core.entity.ModelEntity;
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
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;

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
 * Domain Responsibility: Provider-realism canary for {@link OpenAiModelProvider}. Verifies that
 *   the bean builds a Spring AI {@link OpenAiChatModel} which (1) parses a real OpenAI wire-format
 *   completion response and (2) sends a request whose shape matches the public API spec — model
 *   name in the body, user message in the {@code messages} array, {@code Authorization: Bearer ...}
 *   header. {@code FakeChatModel} bypasses the real Spring AI provider class entirely and cannot
 *   catch SDK upgrades that break the wire contract; this test does.
 * State: Stateless. Single per-class {@link WireMockServer} (fast bring-up).
 */
class OpenAiModelProviderRealismTest {

    private static WireMockServer wireMock;
    private OpenAiModelProvider provider;

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
        provider = new OpenAiModelProvider(creds, tcmProvider);
    }

    @Test
    void buildChatModelRoundtripsRealOpenAiCompletionWireFormat() {
        // Canonical OpenAI POST /chat/completions response. Shape pinned by the public spec
        // at https://platform.openai.com/docs/api-reference/chat/create — drift here would
        // surface as a parse failure rather than the silent-pass that FakeChatModel offers.
        String openAiResponse = """
                {
                  "id": "chatcmpl-test-9NLkX",
                  "object": "chat.completion",
                  "created": 1234567890,
                  "model": "gpt-4o-mini",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "The capital of France is Paris."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 7,
                    "total_tokens": 19
                  }
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(openAiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("openai");
        model.setName("test-openai-model");
        model.setModelName("gpt-4o-mini");
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

        wireMock.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        equalTo("What is the capital of France?"))));
    }
}
