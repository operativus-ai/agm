package ai.operativus.agentmanager.compute.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Provider-realism canary for the new
 *   {@link GeminiModelProvider#buildEmbeddingModel} override. Verifies the bean builds a
 *   Spring AI {@code GoogleGenAiTextEmbeddingModel} that (1) parses a real Gemini
 *   {@code :embedContent} response and (2) sends a request whose shape matches the public
 *   spec — model name in the URL path, content text in the body. Sibling of
 *   {@link GeminiModelProviderRealismTest} (chat sync) and
 *   {@link OpenAiEmbeddingProviderRealismTest}.
 *
 *   Pre-this-PR, {@code GeminiModelProvider} did not override {@code buildEmbeddingModel},
 *   so the inherited {@code AbstractDynamicModelProvider} default threw
 *   {@code UnsupportedOperationException} for any embedding call resolved to a Gemini model.
 *   This canary verifies the override actually round-trips the wire.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class GeminiEmbeddingProviderRealismTest {

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
    void buildEmbeddingModelRoundtripsRealGeminiBatchEmbedContentsWireFormat() {
        // Canonical Gemini POST .../v1beta/models/{model}:batchEmbedContents response.
        // The Google GenAI Java SDK always uses the batch endpoint (plural), even for a
        // single input. Shape pinned by https://ai.google.dev/api/embeddings — drift here
        // surfaces as parse failure rather than the silent-pass that FakeEmbeddingModel
        // offers.
        String geminiResponse = """
                {
                  "embeddings": [
                    { "values": [0.1, 0.2, 0.3, 0.4] }
                  ]
                }
                """;

        wireMock.stubFor(post(urlPathMatching("/.*models/.*:batchEmbedContents"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(geminiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("google");
        model.setName("test-gemini-embed-model");
        model.setModelName("text-embedding-004");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");

        EmbeddingModel embeddingModel = provider.buildEmbeddingModel(model);
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of("hello world"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);

        // Wire-shape verification: the model name appears both in the URL path and inside
        // each request entry's "model" field (the SDK uses the batch shape even for a
        // single input).
        wireMock.verify(postRequestedFor(urlPathMatching("/.*models/text-embedding-004:batchEmbedContents"))
                .withRequestBody(matchingJsonPath("$.requests[0].model", equalTo("models/text-embedding-004")))
                .withRequestBody(matchingJsonPath("$.requests[0].content.parts[0].text", equalTo("hello world"))));
    }
}
