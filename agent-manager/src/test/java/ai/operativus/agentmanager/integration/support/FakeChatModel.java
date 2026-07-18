package ai.operativus.agentmanager.integration.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Domain Responsibility: Scripted, in-process replacement for Spring AI {@link ChatModel}.
 *   Tests wire the responses they need via {@code respondWith(...)} and the fake plays
 *   them back in FIFO order. Every {@link Prompt} the production code ships to the LLM
 *   boundary is recorded so tests can assert on what was sent (system messages, advisor
 *   transformations, tool definitions).
 * State: Mutable (the script and the recorded-prompt log). A fresh instance per test
 *   class is preferred; tests that share a class-scoped instance should call
 *   {@link #reset()} between scenarios.
 *
 * Thread-safety: thread-safe — backing deques are {@link ConcurrentLinkedDeque} so
 *   parallel orchestrators (Swarm/Broadcast workers) can drain scripted responses
 *   concurrently without losing entries. The {@code received} list is
 *   {@link CopyOnWriteArrayList} so streaming paths record prompts without races.
 */
public final class FakeChatModel implements ChatModel {

    private final Deque<Function<Prompt, ChatResponse>> sync = new ConcurrentLinkedDeque<>();
    private final Deque<List<String>> stream = new ConcurrentLinkedDeque<>();
    private final Deque<Function<Prompt, Flux<ChatResponse>>> streamFn = new ConcurrentLinkedDeque<>();
    private final List<Prompt> received = new CopyOnWriteArrayList<>();

    /** Sticky fallback for {@link #call(Prompt)} once the scripted deque is drained. */
    private volatile Function<Prompt, ChatResponse> defaultFn = p -> textResponse("OK", "STOP");

    /** Script the next {@link #call(Prompt)} to return a single-message text response. */
    public FakeChatModel respondWith(String text) {
        sync.add(p -> textResponse(text, "STOP"));
        return this;
    }

    /** Script the next {@link #call(Prompt)} to return a tool call instead of content. */
    public FakeChatModel respondWithToolCall(String name, String arguments) {
        sync.add(p -> toolCallResponse(name, arguments));
        return this;
    }

    /**
     * Script the next {@link #call(Prompt)} to return a text response with token usage metadata.
     * GenAiMetricsAdvisor reads the usage to compute cost; use this when a test needs non-zero
     * cost to exercise budget-enforcement paths.
     */
    public FakeChatModel respondWithTokens(String text, int inputTokens, int outputTokens) {
        sync.add(p -> textResponseWithUsage(text, "STOP", inputTokens, outputTokens));
        return this;
    }

    /** Script the next {@link #call(Prompt)} to return a fully-custom response. */
    public FakeChatModel respondWith(Function<Prompt, ChatResponse> fn) {
        sync.add(fn);
        return this;
    }

    /**
     * Set a STICKY, content-aware fallback used for every {@link #call(Prompt)} once the
     * scripted deque is drained (and immediately when nothing is queued). Unlike
     * {@link #respondWith(Function)} it is NOT consumed — ideal for concurrent tests where
     * the exact chat-call count is unpredictable (an advisor or retry can add an extra
     * call), which would otherwise exhaust a fixed-size deque and fall through to the
     * generic "OK" response. Reset by {@link #reset()}.
     */
    public FakeChatModel respondWithDefault(Function<Prompt, ChatResponse> fn) {
        this.defaultFn = fn;
        return this;
    }

    /** Script the next {@link #stream(Prompt)} to emit each chunk as its own ChatResponse. */
    public FakeChatModel respondWithStream(String... chunks) {
        stream.add(List.of(chunks));
        return this;
    }

    /**
     * Script the next {@link #stream(Prompt)} with a custom Flux. Use to exercise async
     * behaviors the canned-chunks variant cannot model — mid-flight cancellation,
     * error mid-stream, per-chunk delays. The function receives the prompt so the test
     * can route on its contents if needed.
     */
    public FakeChatModel respondWithStreamFunction(Function<Prompt, Flux<ChatResponse>> fn) {
        streamFn.add(fn);
        return this;
    }

    /**
     * Convenience: a slow stream that emits each chunk after the given delay. Used by
     * cancel-mid-flight tests so the DELETE request has time to land while the Flux is
     * still emitting.
     */
    public FakeChatModel respondWithSlowStream(Duration perChunkDelay, String... chunks) {
        List<String> chunkList = List.of(chunks);
        int last = chunkList.size() - 1;
        List<ChatResponse> responses = new ArrayList<>(chunkList.size());
        for (int i = 0; i < chunkList.size(); i++) {
            responses.add(textResponse(chunkList.get(i), i == last ? "STOP" : null));
        }
        streamFn.add(p -> Flux.fromIterable(responses).delayElements(perChunkDelay));
        return this;
    }

    /** Snapshot of every prompt the production code has shipped to this fake so far. */
    public List<Prompt> receivedPrompts() {
        return List.copyOf(received);
    }

    public void reset() {
        sync.clear();
        stream.clear();
        streamFn.clear();
        received.clear();
        defaultFn = p -> textResponse("OK", "STOP");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        // Single atomic poll (not isEmpty()-then-poll, which races to a null poll under
        // concurrency) — fall back to the sticky default when the deque is drained.
        Function<Prompt, ChatResponse> fn = sync.poll();
        return (fn != null ? fn : defaultFn).apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        received.add(prompt);
        if (!streamFn.isEmpty()) {
            return streamFn.poll().apply(prompt);
        }
        List<String> chunks = stream.isEmpty() ? List.of("OK") : stream.poll();
        int last = chunks.size() - 1;
        List<ChatResponse> responses = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            responses.add(textResponse(chunks.get(i), i == last ? "STOP" : null));
        }
        return Flux.fromIterable(responses);
    }

    private static ChatResponse textResponse(String text, String finishReason) {
        return generation(new AssistantMessage(text), finishReason);
    }

    private static ChatResponse textResponseWithUsage(String text, String finishReason,
                                                       int inputTokens, int outputTokens) {
        ChatGenerationMetadata meta = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        Generation gen = new Generation(new AssistantMessage(text), meta);
        DefaultUsage usage = new DefaultUsage(inputTokens, outputTokens, inputTokens + outputTokens);
        ChatResponseMetadata responseMeta = ChatResponseMetadata.builder()
                .usage(usage)
                .build();
        return new ChatResponse(List.of(gen), responseMeta);
    }

    private static ChatResponse toolCallResponse(String name, String arguments) {
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), "function", name, arguments);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return generation(msg, "TOOL_CALLS");
    }

    private static ChatResponse generation(AssistantMessage msg, String finishReason) {
        ChatGenerationMetadata meta = (finishReason == null)
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(msg, meta)));
    }
}
