package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.config.AgentMdcFilter;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentLoggingAdvisor}. Verifies:
 * - Phase MDC lifecycle (LLM_RPC_START → LLM_RPC_END / LLM_RPC_ERROR)
 * - Latency and token metric logging (structural only, no raw prompt leakage)
 * - Stream lifecycle phases (LLM_STREAM_START → LLM_STREAM_END / LLM_STREAM_ERROR)
 * - MDC cleanup after each call/stream, preventing Virtual Thread context leaks
 */
class AgentLoggingAdvisorTest {

    private AgentLoggingAdvisor advisor;
    private AgentRunEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = mock(AgentRunEventBus.class);
        advisor = new AgentLoggingAdvisor(eventBus, new SimpleMeterRegistry());
        AgentMdcFilter.clearMdc();
    }

    @AfterEach
    void tearDown() {
        AgentMdcFilter.clearMdc();
    }

    @Test
    @DisplayName("getName returns AgentLoggingAdvisor")
    void testGetName() {
        assertThat(advisor.getName()).isEqualTo("AgentLoggingAdvisor");
    }

    @Test
    @DisplayName("getOrder returns 0 (outermost advisor)")
    void testGetOrder() {
        assertThat(advisor.getOrder()).isEqualTo(0);
    }

    @Test
    @DisplayName("adviseCall: populates MDC with phase and cleans up after successful call")
    void testAdviseCallSuccess() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse expectedResponse = mockSuccessResponse();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(expectedResponse);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(expectedResponse);
        // MDC phase should be cleared after the call
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
    }

    @Test
    @DisplayName("adviseCall: on exception, sets LLM_RPC_ERROR phase and re-throws")
    void testAdviseCallError() {
        ChatClientRequest request = mockRequest();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new RuntimeException("Provider timeout"));

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Provider timeout");

        // MDC phase should be cleared even after error
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
    }

    @Test
    @DisplayName("adviseStream: emits stream events and cleans up MDC on completion")
    void testAdviseStreamCompletion() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse streamChunk = mockSuccessResponse();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(streamChunk));

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        StepVerifier.create(result)
                .expectNext(streamChunk)
                .verifyComplete();

        // MDC phase should be cleared after stream completes
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
    }

    @Test
    @DisplayName("adviseStream: logs LLM_STREAM_ERROR on stream failure and cleans MDC")
    void testAdviseStreamError() {
        ChatClientRequest request = mockRequest();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.error(new RuntimeException("Stream interrupted")));

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        // MDC phase should be cleared after error
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
    }

    @Test
    @DisplayName("[Private by Design] adviseCall does NOT leak raw prompt text into MDC or log context")
    void testNoPromptLeakage() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse expectedResponse = mockSuccessResponse();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(expectedResponse);

        advisor.adviseCall(request, chain);

        // Verify no MDC key contains the raw prompt text
        assertThat(MDC.getCopyOfContextMap()).doesNotContainValue("Tell me a secret about user john@example.com");
    }

    // --- Helper Methods ---

    private ChatClientRequest mockRequest() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        when(request.context()).thenReturn(Map.of("agentId", "test-agent-001"));
        return request;
    }

    private ChatClientResponse mockSuccessResponse() {
        AssistantMessage assistantMessage = new AssistantMessage("Hello, I'm an AI assistant.");
        Generation generation = new Generation(assistantMessage);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(100, 50))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    /** A streaming content chunk with no usage metadata — mirrors the non-terminal chunks a provider
     *  emits before the final usage-bearing chunk. */
    private ChatClientResponse mockResponseWithoutUsage() {
        AssistantMessage assistantMessage = new AssistantMessage("token ");
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build());

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    // --- §5.12 LLM_REQUEST / LLM_RESPONSE event emission ---

    @Test
    @DisplayName("adviseCall: emits LLM_REQUEST then LLM_RESPONSE with ok status under bound runId")
    void adviseCall_WithBoundRunId_EmitsRequestAndResponseEvents() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse expectedResponse = mockSuccessResponse();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(expectedResponse);

        ScopedValue.where(AgentContextHolder.currentRunId, "run-llm")
                .where(AgentContextHolder.agentId, "agent-llm")
                .run(() -> advisor.adviseCall(request, chain));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AgentRunEventType.LLM_REQUEST);
        assertThat(events.get(0).runId()).isEqualTo("run-llm");
        assertThat(events.get(0).payload().get("mode")).isEqualTo("call");

        assertThat(events.get(1).eventType()).isEqualTo(AgentRunEventType.LLM_RESPONSE);
        assertThat(events.get(1).payload().get("status")).isEqualTo("ok");
        assertThat(events.get(1).payload().get("mode")).isEqualTo("call");
        assertThat(events.get(1).payload()).containsKey("latencyMs");
    }

    @Test
    @DisplayName("adviseCall: on exception, LLM_RESPONSE fires with error status and errorClass")
    void adviseCall_OnException_EmitsRequestAndErrorResponseEvents() {
        ChatClientRequest request = mockRequest();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new RuntimeException("Provider timeout"));

        ScopedValue.where(AgentContextHolder.currentRunId, "run-llm-err")
                .run(() -> {
                    assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                            .isInstanceOf(RuntimeException.class);
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AgentRunEventType.LLM_REQUEST);
        assertThat(events.get(1).eventType()).isEqualTo(AgentRunEventType.LLM_RESPONSE);
        assertThat(events.get(1).payload().get("status")).isEqualTo("error");
        assertThat(events.get(1).payload().get("errorClass")).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("adviseCall: without bound runId, no events are published")
    void adviseCall_WithoutBoundRunId_SkipsEventPublish() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse expectedResponse = mockSuccessResponse();
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(expectedResponse);

        advisor.adviseCall(request, chain);

        verify(eventBus, never()).publish(any());
    }

    @Test
    @DisplayName("adviseStream: completion emits LLM_REQUEST + LLM_RESPONSE(ok)")
    void adviseStream_Completion_EmitsRequestAndResponseEvents() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse chunk = mockSuccessResponse();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk));

        ScopedValue.where(AgentContextHolder.currentRunId, "run-stream")
                .run(() -> {
                    StepVerifier.create(advisor.adviseStream(request, chain))
                            .expectNextCount(1)
                            .verifyComplete();
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AgentRunEventType.LLM_REQUEST);
        assertThat(events.get(0).payload().get("mode")).isEqualTo("stream");
        assertThat(events.get(1).eventType()).isEqualTo(AgentRunEventType.LLM_RESPONSE);
        assertThat(events.get(1).payload().get("status")).isEqualTo("ok");
        assertThat(events.get(1).payload().get("mode")).isEqualTo("stream");
    }

    @Test
    @DisplayName("adviseStream: completion captures token usage from the stream into the LLM_RESPONSE payload and the run telemetry accumulator")
    void adviseStream_Completion_CapturesTokenUsageFromStream() {
        ChatClientRequest request = mockRequest();
        // A real stream interleaves content-only chunks with a terminal chunk that carries usage.
        ChatClientResponse contentChunk = mockResponseWithoutUsage();
        ChatClientResponse usageChunk = mockSuccessResponse(); // DefaultUsage(100, 50)
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(contentChunk, usageChunk));

        RunTelemetryAccumulator telemetry = new RunTelemetryAccumulator();

        ScopedValue.where(AgentContextHolder.currentRunId, "run-stream-tok")
                .where(AgentContextHolder.telemetry, telemetry)
                .run(() -> StepVerifier.create(advisor.adviseStream(request, chain))
                        .expectNextCount(2)
                        .verifyComplete());

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        AgentRunEvent response = captor.getAllValues().get(1);
        assertThat(response.eventType()).isEqualTo(AgentRunEventType.LLM_RESPONSE);
        assertThat(response.payload().get("status")).isEqualTo("ok");
        assertThat(response.payload().get("promptTokens")).isEqualTo(100L);
        assertThat(response.payload().get("completionTokens")).isEqualTo(50L);
        assertThat(response.payload().get("totalTokens")).isEqualTo(150L);

        // Streamed turn is now reflected in the run-scoped accumulator (previously stayed at zero).
        assertThat(telemetry.getTotalInputTokens()).isEqualTo(100L);
        assertThat(telemetry.getTotalOutputTokens()).isEqualTo(50L);
        assertThat(telemetry.getLlmCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("adviseStream: completion with no usage on any chunk still publishes ok and counts the call")
    void adviseStream_Completion_NoUsage_StillCountsCall() {
        ChatClientRequest request = mockRequest();
        ChatClientResponse contentChunk = mockResponseWithoutUsage();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(contentChunk));

        RunTelemetryAccumulator telemetry = new RunTelemetryAccumulator();

        ScopedValue.where(AgentContextHolder.currentRunId, "run-stream-nousage")
                .where(AgentContextHolder.telemetry, telemetry)
                .run(() -> StepVerifier.create(advisor.adviseStream(request, chain))
                        .expectNextCount(1)
                        .verifyComplete());

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        AgentRunEvent response = captor.getAllValues().get(1);
        assertThat(response.payload().get("status")).isEqualTo("ok");
        assertThat(response.payload()).doesNotContainKey("promptTokens");
        assertThat(telemetry.getLlmCallCount()).isEqualTo(1);
        assertThat(telemetry.getTotalInputTokens()).isEqualTo(0L);
    }

    @Test
    @DisplayName("adviseStream: error emits LLM_REQUEST + LLM_RESPONSE(error, errorClass)")
    void adviseStream_Error_EmitsRequestAndErrorResponseEvents() {
        ChatClientRequest request = mockRequest();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.error(new IllegalStateException("stream died")));

        ScopedValue.where(AgentContextHolder.currentRunId, "run-stream-err")
                .run(() -> {
                    StepVerifier.create(advisor.adviseStream(request, chain))
                            .expectError(IllegalStateException.class)
                            .verify();
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AgentRunEventType.LLM_REQUEST);
        assertThat(events.get(1).eventType()).isEqualTo(AgentRunEventType.LLM_RESPONSE);
        assertThat(events.get(1).payload().get("status")).isEqualTo("error");
        assertThat(events.get(1).payload().get("errorClass")).isEqualTo("IllegalStateException");
    }
}
