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
import reactor.core.publisher.Flux;

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
 * Domain Responsibility: Wire-shape canary for {@link AnthropicModelProvider} streaming.
 *   Verifies the bean's chat model handles a real Anthropic v1 Messages-API SSE stream
 *   (named events: {@code message_start}, {@code content_block_start},
 *   {@code content_block_delta}, {@code content_block_stop}, {@code message_delta},
 *   {@code message_stop}) and reassembles {@code text_delta} chunks into a single
 *   completed assistant message. Sibling of {@link AnthropicModelProviderRealismTest}
 *   (sync) and the other streaming canaries.
 *
 *   No known bug to pin — forward-defense against SDK or wire-format drift.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class AnthropicStreamingProviderRealismTest {

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
    void streamRoundtripsRealAnthropicMessagesSseFormat() {
        // Canonical Anthropic SSE stream. Shape pinned by
        // https://docs.anthropic.com/en/api/messages-streaming.
        String sseBody = ""
                + "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3-5-sonnet-20241022\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world.\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody)));

        ModelEntity model = new ModelEntity();
        model.setProvider("anthropic");
        model.setName("test-anthropic-stream-model");
        model.setModelName("claude-3-5-sonnet-20241022");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");
        model.setMaxOutputTokens(256);

        ChatModel chatModel = provider.buildChatModel(model, null);
        Flux<ChatResponse> stream = chatModel.stream(new Prompt(new UserMessage("Say hello world.")));

        List<ChatResponse> chunks = stream.collectList().block();
        assertThat(chunks).isNotNull();

        // Reassemble all text_delta content from the stream — exact chunk count varies by
        // SDK version but the concatenated content must match what we stubbed.
        String aggregated = chunks.stream()
                .map(c -> c.getResult() != null && c.getResult().getOutput() != null
                        ? c.getResult().getOutput().getText()
                        : "")
                .filter(s -> s != null)
                .reduce("", String::concat);
        assertThat(aggregated).isEqualTo("Hello world.");

        // Wire-shape verification: must use x-api-key (not Bearer) and request stream:true.
        wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-3-5-sonnet-20241022")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user"))));
    }
}
