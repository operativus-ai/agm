package com.operativus.agentmanager.compute.advisor;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import com.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import com.operativus.agentmanager.core.spi.OutputPiiScrubber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins the terminal-signal contract of
 *   {@link ExtensionHookAdvisor#adviseStream}. The advisor wires post-hooks via
 *   {@code .doFinally(signalType -> ...)} (F2 production fix), so post-hooks fire on
 *   ALL terminal signals: complete, error, and downstream cancel. Ops dashboards
 *   counting hook invocations see one record per run regardless of outcome; the
 *   buffered text reflects whatever chunks arrived before the terminal signal.
 *
 * State: Stateless. Pure JUnit + Mockito + per-test {@link WireMockServer} so observed
 *   webhook POSTs are direct.
 *
 * <p>The earlier version of cases 2 and 3 in this test was a REGRESSION-LOCK pinning
 * the pre-F2 broken behavior (doOnComplete-only, post-hooks skipped on error/cancel).
 * Both have been flipped to the positive expectation.</p>
 */
class ExtensionHookAdvisorStreamTerminalSignalsTest {

    private static final String HOOK_ID = "e3-terminal-signal-hook";
    private static final String HOOK_PATH = "/hooks/e3-terminal";

    private WireMockServer wiremock;
    private WebClient webClient;
    private ExtensionHookAdvisor advisor;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        wiremock.stubFor(post(urlPathEqualTo(HOOK_PATH))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        webClient = WebClient.builder().build();

        ExtensionRegistrationEntity ext = new ExtensionRegistrationEntity();
        ext.setId(HOOK_ID);
        ext.setActive(true);
        ext.setType("WEBHOOK");
        ext.setUrl("http://localhost:" + wiremock.port() + HOOK_PATH);

        ExtensionRegistrationRepository repo = mock(ExtensionRegistrationRepository.class);
        when(repo.findById(HOOK_ID)).thenReturn(Optional.of(ext));

        advisor = new ExtensionHookAdvisor(
                List.of(), List.of(HOOK_ID), repo, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (wiremock != null) wiremock.stop();
    }

    @Test
    void streamCompletesNormally_postHookFires() {
        Flux<ChatClientResponse> source = Flux.just(chunk("alpha"), chunk("beta"));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(source);

        advisor.adviseStream(mock(ChatClientRequest.class), chain).blockLast();

        assertThat(wiremock.getAllServeEvents())
                .as("normal completion fires doOnComplete → post-hook MUST dispatch exactly once")
                .hasSize(1);
        assertThat(wiremock.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .as("post-hook body must contain the concatenated chunk text")
                .contains("alphabeta");
    }

    /**
     * F2 fix landed: doFinally semantics. A stream that errors mid-flight MUST still
     * fire post-hooks once with whatever chunks were buffered before the error.
     */
    @Test
    void streamErrorsMidFlight_postHookFires_F2ProductionFix() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(chunk("partial"))
                .concatWith(Flux.error(new RuntimeException("simulated downstream LLM failure")));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(source);

        assertThatThrownBy(() -> advisor.adviseStream(mock(ChatClientRequest.class), chain).blockLast())
                .hasMessageContaining("simulated downstream LLM failure");

        assertThat(wiremock.getAllServeEvents())
                .as("F2 production fix: stream error MUST fire post-hook exactly once (doFinally)")
                .hasSize(1);
        assertThat(wiremock.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .as("error-path post-hook body carries the chunks that arrived before the error")
                .contains("partial");
    }

    /**
     * F2 fix landed: doFinally semantics. A cancelled stream MUST still fire post-hooks
     * once. {@code Flux.take(1)} on a 3-element source forces cancel after the first emission.
     */
    @Test
    void streamCancelledByDownstream_postHookFires_F2ProductionFix() {
        Flux<ChatClientResponse> source = Flux.just(chunk("c1"), chunk("c2"), chunk("c3"));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(source);

        advisor.adviseStream(mock(ChatClientRequest.class), chain)
                .take(1)
                .blockLast();

        assertThat(wiremock.getAllServeEvents())
                .as("F2 production fix: downstream cancel MUST fire post-hook exactly once (doFinally)")
                .hasSize(1);
        assertThat(wiremock.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .as("cancel-path post-hook body carries the chunks emitted before cancel")
                .contains("c1");
    }

    private static ChatClientResponse chunk(String text) {
        ChatClientResponse resp = mock(ChatClientResponse.class);
        ChatResponse cr = mock(ChatResponse.class);
        Generation g = mock(Generation.class);
        AssistantMessage am = mock(AssistantMessage.class);
        when(resp.chatResponse()).thenReturn(cr);
        when(cr.getResult()).thenReturn(g);
        when(g.getOutput()).thenReturn(am);
        when(am.getText()).thenReturn(text);
        return resp;
    }
}
