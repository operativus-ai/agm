package com.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.core.entity.ModelEntity;
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
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import reactor.core.publisher.Flux;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Wire-shape canary for {@link OllamaModelProvider} streaming.
 *   Verifies the bean's chat model handles a real Ollama {@code POST /api/chat} NDJSON
 *   stream (newline-delimited JSON chunks with {@code message.content} deltas + a terminal
 *   {@code done:true} chunk) and reassembles deltas into a single completed assistant
 *   message. Sibling of {@link OllamaModelProviderRealismTest} (sync chat) and
 *   {@link OllamaEmbeddingProviderRealismTest} (embedding); carries the same
 *   {@code @Disabled} pin for the same upstream Spring AI Ollama incompat.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class OllamaStreamingProviderRealismTest {

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
     * Disabled for the same reason as {@link OllamaModelProviderRealismTest} and
     * {@link OllamaEmbeddingProviderRealismTest}: every spring-ai-ollama 2.0.0-SNAPSHOT
     * (verified through the 2026-05-08 snapshot) calls
     * {@code RestClient.ResponseSpec.requiredBody(Class)} from {@code OllamaApi.chat(...)},
     * a method missing on Spring Web 7.0.2 — only {@code body(Class)} /
     * {@code body(ParameterizedTypeReference)} are present. Any
     * {@code OllamaChatModel.stream(...)} crashes with NoSuchMethodError before the WireMock
     * NDJSON stub is ever consumed.
     *
     * Re-enable once either (a) Spring Framework adds {@code requiredBody(Class)} to
     * {@code RestClient.ResponseSpec}, or (b) Spring AI Ollama replaces the call with
     * {@code body(Class)}.
     */
    @Test
    @Disabled("ollama-spring-ai-upstream-incompat — see TASK_REF in javadoc")
    void streamRoundtripsRealOllamaChatNdjsonFormat() {
        // Canonical Ollama POST /api/chat streaming response (NDJSON, not SSE).
        // Shape pinned by https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion.
        // 3 chunks: 2 content deltas + 1 terminal done chunk with usage metrics.
        String ndjsonBody = ""
                + "{\"model\":\"llama3.2\",\"created_at\":\"2026-05-11T19:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello \"},\"done\":false}\n"
                + "{\"model\":\"llama3.2\",\"created_at\":\"2026-05-11T19:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"world.\"},\"done\":false}\n"
                + "{\"model\":\"llama3.2\",\"created_at\":\"2026-05-11T19:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"total_duration\":12345678,\"load_duration\":1000000,\"prompt_eval_count\":3,\"eval_count\":2}\n";

        wireMock.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjsonBody)));

        ModelEntity model = new ModelEntity();
        model.setProvider("ollama");
        model.setName("test-ollama-stream-model");
        model.setModelName("llama3.2");
        model.setBaseUrl(wireMock.baseUrl());

        ChatModel chatModel = provider.buildChatModel(model, null);
        Flux<ChatResponse> stream = chatModel.stream(new Prompt(new UserMessage("Say hello world.")));

        List<ChatResponse> chunks = stream.collectList().block();
        assertThat(chunks).isNotNull();

        String aggregated = chunks.stream()
                .map(c -> c.getResult() != null && c.getResult().getOutput() != null
                        ? c.getResult().getOutput().getText()
                        : "")
                .filter(s -> s != null)
                .reduce("", String::concat);
        assertThat(aggregated).isEqualTo("Hello world.");

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("llama3.2")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user"))));
    }
}
