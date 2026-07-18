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
 * Domain Responsibility: Wire-shape canary for {@link OpenAiModelProvider} streaming. Verifies
 *   the bean's chat model handles a real OpenAI {@code POST /chat/completions} SSE response
 *   (delta chunks + terminal {@code [DONE]} sentinel) and reassembles deltas into a single
 *   completed assistant message. Sibling of {@link OpenAiModelProviderRealismTest} (sync) and
 *   {@link GeminiStreamingProviderRealismTest} (regression-locked).
 *
 *   No known bug to pin — this is a forward-guard against SDK or wire-format drift. If the
 *   OpenAI SDK changes its delta-aggregation contract, or stops handling the {@code [DONE]}
 *   sentinel, this canary surfaces it as a parse failure rather than the silent-pass that
 *   {@code FakeChatModel} offers.
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class OpenAiStreamingProviderRealismTest {

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
    void streamRoundtripsRealOpenAiChatCompletionSseFormat() {
        // Canonical OpenAI SSE stream: 3 delta chunks (role, content fragment 1, content
        // fragment 2) + terminal stop chunk + [DONE] sentinel. Shape pinned by
        // https://platform.openai.com/docs/api-reference/chat/streaming.
        String sseBody = ""
                + "data: {\"id\":\"chatcmpl-test-1\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test-1\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello \"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test-1\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world.\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test-1\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        wireMock.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody)));

        ModelEntity model = new ModelEntity();
        model.setProvider("openai");
        model.setName("test-openai-stream-model");
        model.setModelName("gpt-4o-mini");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");
        model.setMaxOutputTokens(256);

        ChatModel chatModel = provider.buildChatModel(model, null);
        Flux<ChatResponse> stream = chatModel.stream(new Prompt(new UserMessage("Say hello world.")));

        List<ChatResponse> chunks = stream.collectList().block();
        assertThat(chunks).isNotNull();

        // Reassemble all delta text from the stream — exact chunk count varies by SDK
        // version but the concatenated content must match what we stubbed.
        String aggregated = chunks.stream()
                .map(c -> c.getResult() != null && c.getResult().getOutput() != null
                        ? c.getResult().getOutput().getText()
                        : "")
                .filter(s -> s != null)
                .reduce("", String::concat);
        assertThat(aggregated).isEqualTo("Hello world.");

        // Wire-shape verification: the request must include stream:true (otherwise the SDK
        // didn't actually take the streaming path).
        wireMock.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key-canary-12345"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        equalTo("Say hello world."))));
    }
}
