package com.operativus.agentmanager.compute.advisor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for audit F4: pins {@link ContentSafetyAdvisor#adviseStream} as a HARD GATE.
 *
 * <p>The previous implementation used {@code .doOnNext(...)} (chunk passthrough) with
 * {@code .doOnComplete(...)} for moderation. By the time the moderation check ran, every chunk
 * had already been delivered to the downstream subscriber — the user saw unsafe content followed
 * by a trailing error event. ContentSafetyAdvisor's domain responsibility is "block the
 * generation of harmful or restricted content"; a passthrough-then-trail-error implementation
 * cannot honor that contract.</p>
 *
 * <p>This test pins the corrected behavior: the upstream Flux is buffered via
 * {@code .collectList()}, moderation runs against the joined chunks, and emission happens only
 * if moderation passes. Streaming UX collapses to block-render (full latency before first byte)
 * — that is the price of correctness for a security-blocking advisor and is intentional.</p>
 *
 * State: Stateless
 */
class ContentSafetyAdvisorStreamingTest {

    private SimpleMeterRegistry meterRegistry;
    private ModerationService moderationService;
    private ContentSafetyAdvisor advisor;
    private StreamAdvisorChain chain;
    private ChatClientRequest request;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        moderationService = mock(ModerationService.class);
        advisor = new ContentSafetyAdvisor(moderationService, meterRegistry);
        chain = mock(StreamAdvisorChain.class);
        request = mock(ChatClientRequest.class);
    }

    @Test
    @DisplayName("Clean content: all chunks emit IN ORDER and scannedOk increments")
    void streamingCleanContent_emitsAllChunks() {
        ChatClientResponse chunk1 = chunk("Hello ");
        ChatClientResponse chunk2 = chunk("world");
        ChatClientResponse chunk3 = chunk("!");
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk1, chunk2, chunk3));
        when(moderationService.checkContent(any())).thenReturn(ModerationResult.clean());

        StepVerifier.create(advisor.adviseStream(request, chain))
                .expectNext(chunk1, chunk2, chunk3)
                .verifyComplete();

        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "ok").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "blocked").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("Blocked content: ZERO chunks emit, stream errors with SecurityException, scannedBlocked increments")
    void streamingBlockedContent_emitsNoChunks() {
        ChatClientResponse chunk1 = chunk("Here is how to ");
        ChatClientResponse chunk2 = chunk("do something dangerous");
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk1, chunk2));
        when(moderationService.checkContent(any()))
                .thenThrow(new SecurityException("Output blocked due to safety violations"));

        StepVerifier.create(advisor.adviseStream(request, chain))
                // The critical assertion: zero onNext signals — unsafe content is NEVER emitted.
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(SecurityException.class);
                    assertThat(e).hasMessageContaining("Output blocked");
                })
                .verify();

        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "blocked").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "ok").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("Empty stream: no moderation call, no counter increment, completes without error")
    void streamingEmpty_passesThroughCleanly() {
        when(chain.nextStream(any())).thenReturn(Flux.empty());

        StepVerifier.create(advisor.adviseStream(request, chain))
                .verifyComplete();

        // Empty/blank text bypasses moderation entirely; counters stay at zero.
        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "ok").count())
                .isEqualTo(0.0);
        assertThat(meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "blocked").count())
                .isEqualTo(0.0);
    }

    private static ChatClientResponse chunk(String text) {
        AssistantMessage msg = new AssistantMessage(text);
        Generation gen = new Generation(msg);
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }
}
