package com.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
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
 * Domain Responsibility: Provider-realism canary for {@link LiteLlmModelProvider}. LiteLLM speaks
 *   the OpenAI wire format, so the gateway provider builds an OpenAI-compatible chat model — but
 *   pointed at the configured LiteLLM proxy. This test proves a model row with NO {@code baseUrl}
 *   routes to {@code agm.providers.litellm.base-url} (the {@code defaultBaseUrl()} override), parses
 *   a real OpenAI-format response, and sends the right wire shape with the resolved key.
 * State: Stateless. Single per-class {@link WireMockServer} standing in for the LiteLLM proxy.
 */
class LiteLlmModelProviderRealismTest {

    private static WireMockServer liteLlmProxy;
    private LiteLlmModelProvider provider;

    @BeforeAll
    static void startProxy() {
        liteLlmProxy = new WireMockServer(options().dynamicPort());
        liteLlmProxy.start();
    }

    @AfterAll
    static void stopProxy() {
        liteLlmProxy.stop();
    }

    @AfterEach
    void resetStubs() {
        liteLlmProxy.resetAll();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setupProvider() {
        ProviderCredentialOperations creds = (orgId, prov) -> java.util.Optional.empty();
        ObjectProvider<ToolCallingManager> tcmProvider = mock(ObjectProvider.class);
        when(tcmProvider.getObject()).thenReturn(DefaultToolCallingManager.builder().build());
        // The configured LiteLLM proxy URL is the WireMock base — what defaultBaseUrl() returns.
        provider = new LiteLlmModelProvider(creds, tcmProvider, liteLlmProxy.baseUrl());
    }

    @Test
    void providerKeyIsLitellm() {
        assertThat(provider.getProviderKeys()).containsExactly("LITELLM");
    }

    @Test
    void routesToConfiguredProxy_whenModelRowHasNoBaseUrl() {
        String openAiResponse = """
                {
                  "id": "chatcmpl-litellm-1",
                  "object": "chat.completion",
                  "created": 1234567890,
                  "model": "claude-3-5-sonnet",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "routed via LiteLLM"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
                }
                """;
        liteLlmProxy.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(openAiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("LITELLM");
        model.setName("test-litellm-model");
        model.setModelName("claude-3-5-sonnet"); // a non-OpenAI model LiteLLM routes
        model.setApiKey("litellm-master-key-canary");
        // NOTE: no baseUrl on the row — must fall back to the configured LiteLLM proxy.

        ChatModel chatModel = provider.buildChatModel(model, null);
        ChatResponse response = chatModel.call(new Prompt(new UserMessage("ping")));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("routed via LiteLLM");
        // The request hit the LiteLLM proxy (default base URL) with the key + model name.
        liteLlmProxy.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer litellm-master-key-canary"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-3-5-sonnet")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("ping"))));
    }
}
