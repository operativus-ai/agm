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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
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
 * Domain Responsibility: Provider-realism canary for {@link OpenAiModelProvider#buildEmbeddingModel}.
 *   Verifies that the bean builds a Spring AI {@code OpenAiEmbeddingModel} which (1) parses a real
 *   OpenAI embeddings wire-format response and (2) sends a request whose shape matches the public
 *   API spec — model name + input array in the body, {@code Authorization: Bearer ...} header.
 *   {@code FakeEmbeddingModel} bypasses the real Spring AI provider entirely and cannot catch SDK
 *   upgrades that break the embeddings wire contract; this test does. Sibling of
 *   {@link OpenAiModelProviderRealismTest} for the chat path.
 * State: Stateless. Single per-class {@link WireMockServer} (fast bring-up).
 */
class OpenAiEmbeddingProviderRealismTest {

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
    void buildEmbeddingModelRoundtripsRealOpenAiEmbeddingsWireFormat() {
        // Canonical OpenAI POST /embeddings response with base64 encoding (Spring AI 2.0
        // default). Shape pinned by https://platform.openai.com/docs/api-reference/embeddings/create.
        // The base64 path is what FinOpsObservedEmbeddingModel#call ultimately receives; if Spring
        // AI flips the default back to float-arrays, this test surfaces the parse mismatch.
        float[] expectedVector = {0.1f, 0.2f, 0.3f, 0.4f};
        String b64Embedding = encodeFloatArrayToBase64(expectedVector);

        String openAiResponse = """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "embedding": "%s",
                      "index": 0
                    }
                  ],
                  "model": "text-embedding-3-small",
                  "usage": {
                    "prompt_tokens": 5,
                    "total_tokens": 5
                  }
                }
                """.formatted(b64Embedding);

        wireMock.stubFor(post(urlPathEqualTo("/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(openAiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("openai");
        model.setName("test-openai-embed-model");
        model.setModelName("text-embedding-3-small");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");

        EmbeddingModel embeddingModel = provider.buildEmbeddingModel(model);
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of("hello world"));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getOutput()).containsExactly(expectedVector);

        var usage = response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(5L);
        assertThat(usage.getTotalTokens()).isEqualTo(5L);

        wireMock.verify(postRequestedFor(urlPathEqualTo("/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-3-small")))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("hello world"))));
    }

    @Test
    void buildEmbeddingModelBindsConfiguredDimensionsForV3Model() {
        // When the bean is Spring-injected with the pgvector store dimension, text-embedding-3-*
        // requests must carry `dimensions` so the OpenAI side reduces its native 1536/3072 output to
        // fit the vector column. Without it, the emitted vector would mismatch the 768-dim store.
        org.springframework.test.util.ReflectionTestUtils.setField(provider, "embeddingDimensions", 768);

        float[] vector = {0.1f, 0.2f};
        String openAiResponse = """
                {
                  "object": "list",
                  "data": [ { "object": "embedding", "embedding": "%s", "index": 0 } ],
                  "model": "text-embedding-3-small",
                  "usage": { "prompt_tokens": 2, "total_tokens": 2 }
                }
                """.formatted(encodeFloatArrayToBase64(vector));

        wireMock.stubFor(post(urlPathEqualTo("/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(openAiResponse)));

        ModelEntity model = new ModelEntity();
        model.setProvider("OPENAI");
        model.setName("test-openai-embed-768");
        model.setModelName("text-embedding-3-small");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");

        provider.buildEmbeddingModel(model).embedForResponse(List.of("hello world"));

        wireMock.verify(postRequestedFor(urlPathEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-3-small")))
                .withRequestBody(matchingJsonPath("$.dimensions", equalTo("768"))));
    }

    private static String encodeFloatArrayToBase64(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return Base64.getEncoder().encodeToString(bb.array());
    }
}
