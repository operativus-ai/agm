package ai.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * Domain Responsibility: Provider-realism canary for {@link OllamaModelProvider}. Verifies the
 *   bean builds a Spring AI {@link org.springframework.ai.ollama.OllamaChatModel} that
 *   (1) parses a real Ollama {@code /api/chat} response and (2) sends a request whose shape
 *   matches the public spec — {@code messages} body with no auth header (Ollama is
 *   self-hosted). {@code FakeChatModel} bypasses the real Spring AI provider class entirely
 *   and cannot catch SDK upgrades that break the wire contract; this test does.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class OllamaModelProviderRealismTest {

    private static WireMockServer wireMock;
    private OllamaModelProvider provider;

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
        provider = new OllamaModelProvider(creds, tcmProvider);
    }

    /**
     * TASK_REF=ollama-spring-ai-upstream-incompat
     *
     * Currently disabled because the OllamaApi class in spring-ai-ollama 2.0.0-SNAPSHOT
     * (verified through the 2026-05-11 snapshot) calls
     * {@code RestClient.ResponseSpec.requiredBody(Class)}, a method that does not exist on
     * Spring Web 7.0.2 — only {@code body(Class)} / {@code body(ParameterizedTypeReference)}
     * are present. Any invocation of OllamaChatModel.call(...) crashes with NoSuchMethodError,
     * making Ollama unusable in this codebase regardless of provider configuration.
     *
     * Re-enable once either (a) Spring Framework adds {@code requiredBody(Class)} to
     * RestClient.ResponseSpec or (b) Spring AI Ollama replaces the call. The canary contract
     * below is the behavior the provider+SDK should deliver once the upstream gap closes.
     */
    @Disabled("ollama-spring-ai-upstream-incompat — see Javadoc above")
    @Test
    void buildChatModelRoundtripsRealOllamaChatWireFormat() {
        // Canonical Ollama POST /api/chat response. Shape pinned by the public API doc at
        // https://github.com/ollama/ollama/blob/main/docs/api.md#chat-request-non-streaming
        // — drift here surfaces as parse failure rather than the silent-pass that
        // FakeChatModel offers.
        String ollamaResponse = """
                {
                  "model": "llama3",
                  "created_at": "2026-05-11T22:00:00Z",
                  "message": {
                    "role": "assistant",
                    "content": "The capital of France is Paris."
                  },
                  "done": true,
                  "done_reason": "stop",
                  "total_duration": 1234567890,
                  "load_duration": 100000000,
                  "prompt_eval_count": 12,
                  "prompt_eval_duration": 200000000,
                  "eval_count": 7,
                  "eval_duration": 300000000
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ollamaResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("ollama");
        model.setName("test-ollama-model");
        model.setModelName("llama3");
        model.setBaseUrl(wireMock.baseUrl());

        ChatModel chatModel = provider.buildChatModel(model, null);
        ChatResponse response = chatModel.call(new Prompt(new UserMessage("What is the capital of France?")));

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("The capital of France is Paris.");

        var usage = response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(12);
        assertThat(usage.getCompletionTokens()).isEqualTo(7);
        assertThat(usage.getTotalTokens()).isEqualTo(19);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("llama3")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        equalTo("What is the capital of France?"))));
    }
}
