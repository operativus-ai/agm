package ai.operativus.agentmanager.compute.advisor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtlpSpanExportAdvisorTest {

    @Mock private Tracer tracer;
    @Mock private SpanBuilder spanBuilder;
    @Mock private Span span;
    @Mock private CallAdvisorChain callChain;
    @Mock private StreamAdvisorChain streamChain;
    @Mock private ChatClientRequest request;
    @Mock private ChatClientResponse response;
    @Mock private ChatResponse chatResponse;
    @Mock private ChatResponseMetadata metadata;
    @Mock private Usage usage;

    private OtlpSpanExportAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new OtlpSpanExportAdvisor(tracer, false, new SimpleMeterRegistry());
    }

    private void setupSpanMocks() {
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(any(AttributeKey.class), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        lenient().when(span.setAttribute(any(AttributeKey.class), anyString())).thenReturn(span);
        lenient().when(span.setAttribute(any(AttributeKey.class), anyLong())).thenReturn(span);
    }

    @Test
    void getName_ReturnsExpectedName() {
        assertEquals("OtlpSpanExportAdvisor", advisor.getName());
    }

    @Test
    void getOrder_ReturnsMaxMinusOne() {
        assertEquals(Integer.MAX_VALUE - 1, advisor.getOrder());
    }

    @Test
    void adviseCall_CreatesSpanWithAgentId() {
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of("agentId", "test-agent"));
        when(callChain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn("gpt-4");
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(usage.getTotalTokens()).thenReturn(150);

        ChatClientResponse result = advisor.adviseCall(request, callChain);

        assertNotNull(result);
        verify(tracer).spanBuilder("agm.llm.call");
        verify(span).end();
    }

    @Test
    void adviseCall_PropagatesExceptionAndEndsSpan() {
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of("agentId", "test-agent"));
        RuntimeException ex = new RuntimeException("LLM failed");
        when(callChain.nextCall(request)).thenThrow(ex);

        assertThrows(RuntimeException.class, () -> advisor.adviseCall(request, callChain));

        verify(span).recordException(ex);
        verify(span).end();
    }

    @Test
    void adviseCall_FallsBackToUnknownAgentId() {
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of());
        when(callChain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(null);

        advisor.adviseCall(request, callChain);

        // Should not throw — falls back to "unknown"
        verify(span).end();
    }

    @Test
    void adviseStream_CreatesSpanAndEndsOnComplete() {
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of("agentId", "stream-agent"));
        when(streamChain.nextStream(request)).thenReturn(Flux.just(response));

        Flux<ChatClientResponse> result = advisor.adviseStream(request, streamChain);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();

        verify(tracer).spanBuilder("agm.llm.stream");
        verify(span).end();
    }

    @Test
    void adviseStream_EndsSpanOnError() {
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of("agentId", "stream-agent"));
        RuntimeException ex = new RuntimeException("Stream failed");
        when(streamChain.nextStream(request)).thenReturn(Flux.error(ex));

        Flux<ChatClientResponse> result = advisor.adviseStream(request, streamChain);

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        verify(span).recordException(ex);
        verify(span).end();
    }

    @Test
    void adviseCall_WithIncludePrompts_DoesNotThrow() {
        OtlpSpanExportAdvisor advisorWithPrompts = new OtlpSpanExportAdvisor(tracer, true, new SimpleMeterRegistry());
        setupSpanMocks();
        when(request.context()).thenReturn(Map.of("agentId", "test-agent"));
        when(request.prompt()).thenReturn(null); // No prompt available
        when(callChain.nextCall(request)).thenReturn(response);
        when(response.chatResponse()).thenReturn(null);

        // Should not throw even with includePrompts=true and null prompt
        ChatClientResponse result = advisorWithPrompts.adviseCall(request, callChain);
        assertNotNull(result);
        verify(span).end();
    }
}
