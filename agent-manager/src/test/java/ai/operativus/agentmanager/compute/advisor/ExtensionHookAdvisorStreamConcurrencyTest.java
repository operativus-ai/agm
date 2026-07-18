package ai.operativus.agentmanager.compute.advisor;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import ai.operativus.agentmanager.core.spi.OutputPiiScrubber;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins that {@link ExtensionHookAdvisor#adviseStream} keeps each
 *   stream's post-hook payload isolated from any other concurrent stream's payload —
 *   the {@code StringBuilder} that accumulates chunks is allocated per-call (a method
 *   local), so two concurrent streams through the same advisor instance MUST produce
 *   two cleanly separated webhook bodies.
 *
 * State: Stateless. Pure JUnit + Mockito + a real per-test {@link WireMockServer} so
 *   the assertion runs against captured HTTP bodies rather than mock verifications.
 *
 * <p>Why this test exists: existing unit tests cover single-stream paths only. Virtual
 * threads + Spring Boot 4 make it easy for the same advisor instance to be invoked
 * concurrently. A regression that promoted the per-call StringBuilder to an instance
 * field would cause cross-stream chunk mixing — the LLM reply for run A would arrive
 * at the webhook for run B, intermixed. This is a category of bug that only surfaces
 * under load, exactly the kind a runtime concurrency test pins early.</p>
 */
class ExtensionHookAdvisorStreamConcurrencyTest {

    private static final String HOOK_ID = "e2-stream-isolation-hook";
    private static final String HOOK_PATH = "/hooks/e2-stream";
    private static final int CHUNKS_PER_STREAM = 8;

    private WireMockServer wiremock;
    private WebClient webClient;
    private ExtensionRegistrationRepository repo;
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

        repo = mock(ExtensionRegistrationRepository.class);
        when(repo.findById(HOOK_ID)).thenReturn(Optional.of(ext));

        advisor = new ExtensionHookAdvisor(
                List.of(), List.of(HOOK_ID), repo, webClient, OutputPiiScrubber.NO_OP, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (wiremock != null) wiremock.stop();
    }

    @Test
    void twoConcurrentStreams_postHookPayloadsRemainIsolated() throws Exception {
        Flux<ChatClientResponse> fluxA = chunkFlux("AAA");
        Flux<ChatClientResponse> fluxB = chunkFlux("BBB");

        ChatClientRequest reqA = mock(ChatClientRequest.class);
        ChatClientRequest reqB = mock(ChatClientRequest.class);

        StreamAdvisorChain chainA = mock(StreamAdvisorChain.class);
        when(chainA.nextStream(any())).thenReturn(fluxA);
        StreamAdvisorChain chainB = mock(StreamAdvisorChain.class);
        when(chainB.nextStream(any())).thenReturn(fluxB);

        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> futA = CompletableFuture.runAsync(
                    () -> advisor.adviseStream(reqA, chainA).blockLast(), vts);
            CompletableFuture<Void> futB = CompletableFuture.runAsync(
                    () -> advisor.adviseStream(reqB, chainB).blockLast(), vts);
            CompletableFuture.allOf(futA, futB).get(10, TimeUnit.SECONDS);
        }

        List<ServeEvent> events = wiremock.getAllServeEvents();
        assertThat(events)
                .as("exactly one post-hook POST per concurrent stream")
                .hasSize(2);

        String bodyOne = events.get(0).getRequest().getBodyAsString();
        String bodyTwo = events.get(1).getRequest().getBodyAsString();
        String expectedA = "AAA".repeat(CHUNKS_PER_STREAM);
        String expectedB = "BBB".repeat(CHUNKS_PER_STREAM);

        // Each body must contain ONE pure run (AAA*N or BBB*N), never a mixed string.
        boolean perfectIsolation =
                (bodyOne.contains(expectedA) && bodyTwo.contains(expectedB))
                        || (bodyOne.contains(expectedB) && bodyTwo.contains(expectedA));

        assertThat(perfectIsolation)
                .as("stream A's post-hook payload must contain only AAA-chunks, B's only BBB-chunks. "
                        + "Interleaved content (e.g. \"AAABBBAAA\") would mean the StringBuilder leaked "
                        + "across concurrent adviseStream invocations.\n  body1=%s\n  body2=%s",
                        bodyOne, bodyTwo)
                .isTrue();

        // Also assert NEITHER body contains chunks of the OTHER stream — pins isolation even
        // if both runs happened to land on the "right" webhook in order.
        if (bodyOne.contains(expectedA)) {
            assertThat(bodyOne).doesNotContain("BBB");
            assertThat(bodyTwo).doesNotContain("AAA");
        } else {
            assertThat(bodyOne).doesNotContain("AAA");
            assertThat(bodyTwo).doesNotContain("BBB");
        }
    }

    private static Flux<ChatClientResponse> chunkFlux(String chunkText) {
        ChatClientResponse[] chunks = new ChatClientResponse[CHUNKS_PER_STREAM];
        for (int i = 0; i < CHUNKS_PER_STREAM; i++) {
            chunks[i] = chunkResponse(chunkText);
        }
        return Flux.just(chunks);
    }

    private static ChatClientResponse chunkResponse(String text) {
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
