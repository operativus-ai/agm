package com.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.core.entity.ModelEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;

import java.util.List;

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
 * Domain Responsibility: Provider-realism canary for {@link OllamaModelProvider#buildEmbeddingModel}.
 *   Verifies the bean builds a Spring AI {@code OllamaEmbeddingModel} that (1) parses a real
 *   Ollama {@code /api/embed} response and (2) sends a request whose shape matches the public
 *   spec — {@code model} + {@code input} body, no auth header. Sibling of
 *   {@link OllamaModelProviderRealismTest} for the chat path; carries the same
 *   {@code @Disabled} pin for the same upstream Spring AI Ollama incompat.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class OllamaEmbeddingProviderRealismTest {

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
     * Disabled for the same reason as {@link OllamaModelProviderRealismTest}: every
     * spring-ai-ollama 2.0.0-SNAPSHOT (verified through the 2026-05-08 snapshot) calls
     * {@code RestClient.ResponseSpec.requiredBody(Class)} from {@code OllamaApi.embed(...)},
     * a method that does not exist on Spring Web 7.0.2 — only {@code body(Class)} /
     * {@code body(ParameterizedTypeReference)} are present. Any invocation of
     * OllamaEmbeddingModel.call(...) crashes with NoSuchMethodError, so the embedding path
     * is unusable for the same upstream reason as chat.
     *
     * Re-enable once either (a) Spring Framework adds {@code requiredBody(Class)} to
     * {@code RestClient.ResponseSpec}, or (b) Spring AI Ollama replaces the call with
     * {@code body(Class)}.
     */
    @Test
    @Disabled("ollama-spring-ai-upstream-incompat — see TASK_REF in javadoc")
    void buildEmbeddingModelRoundtripsRealOllamaEmbeddingsWireFormat() {
        // Canonical Ollama POST /api/embed response. Shape pinned by
        // https://github.com/ollama/ollama/blob/main/docs/api.md#generate-embeddings.
        String ollamaResponse = """
                {
                  "model": "nomic-embed-text",
                  "embeddings": [[0.1, 0.2, 0.3, 0.4]],
                  "total_duration": 14143917,
                  "load_duration": 1019500,
                  "prompt_eval_count": 8
                }
                """;

        wireMock.stubFor(post(urlPathEqualTo("/api/embed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ollamaResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("ollama");
        model.setName("test-ollama-embed-model");
        model.setModelName("nomic-embed-text");
        model.setBaseUrl(wireMock.baseUrl());

        EmbeddingModel embeddingModel = provider.buildEmbeddingModel(model);
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of("hello world"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/embed"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("nomic-embed-text")))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("hello world"))));
    }
}
