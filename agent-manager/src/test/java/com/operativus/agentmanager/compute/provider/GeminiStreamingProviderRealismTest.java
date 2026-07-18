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
import reactor.core.publisher.Flux;

import java.util.NoSuchElementException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Regression-locked canary for the Spring AI Gemini streaming
 *   {@link NoSuchElementException} that {@code AgentStreamManager} works around. Stubs a real
 *   {@code :streamGenerateContent} SSE response whose terminal chunk carries
 *   {@code finishReason} only (no {@code parts}) — the exact wire shape the bug fires on —
 *   and asserts the bare provider stream still propagates that exception today.
 *
 *   This test is intentionally regression-locked: it MUST FAIL the day Spring AI fixes
 *   {@code responseCandidateToGeneration()} (or the GenAI SDK stops emitting empty terminal
 *   chunks). When that happens, remove the
 *   {@code .onErrorResume(NoSuchElementException.class, …)} branch in
 *   {@code AgentStreamManager} and delete this test (or invert it to assert clean stream
 *   completion).
 *
 *   Companion to {@link GeminiModelProviderRealismTest} (chat sync) and
 *   {@link OpenAiModelProviderRealismTest} (chat sync).
 * State: Stateless. Single per-class {@link WireMockServer}.
 */
class GeminiStreamingProviderRealismTest {

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
    void streamTerminalFinishReasonOnlyChunkTriggersUpstreamNoSuchElementException() {
        // Three-chunk SSE response. First two carry content; the third is the bug-triggering
        // terminal chunk: finishReason=STOP, no parts. Spring AI's
        // responseCandidateToGeneration() calls Optional.get() unconditionally on the empty
        // candidate, throwing NoSuchElementException — the workaround in
        // AgentStreamManager#buildClientContentFlux exists to swallow that error after the
        // real content has already been emitted.
        String sseBody = ""
                + "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello \"}]},\"index\":0}]}\n\n"
                + "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"world.\"}]},\"index\":0}]}\n\n"
                + "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5},\"modelVersion\":\"gemini-2.0-flash\"}\n\n";

        wireMock.stubFor(post(urlPathMatching("/.*models/.*:streamGenerateContent.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody)));

        ModelEntity model = new ModelEntity();
        model.setProvider("google");
        model.setName("test-gemini-stream-model");
        model.setModelName("gemini-2.0-flash");
        model.setBaseUrl(wireMock.baseUrl());
        model.setApiKey("test-key-canary-12345");
        model.setMaxOutputTokens(256);

        ChatModel chatModel = provider.buildChatModel(model, null);
        Flux<ChatResponse> stream = chatModel.stream(new Prompt(new UserMessage("Say hello world.")));

        // Subscribe and drain the flux. The bug fires on the terminal chunk; reactor
        // surfaces it directly (not wrapped) — the throwable IS NoSuchElementException
        // emerging from GoogleGenAiChatModel#internalStream, where the SDK calls
        // Optional.get() on the empty terminal candidate. The day this assertion stops
        // holding (the stream completes cleanly), the AgentStreamManager workaround is no
        // longer needed — see the class-level Javadoc.
        assertThatThrownBy(() -> stream.collectList().block())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No value present");
    }
}
