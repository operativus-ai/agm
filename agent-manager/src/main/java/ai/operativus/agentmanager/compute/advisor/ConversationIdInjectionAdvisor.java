package ai.operativus.agentmanager.compute.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * Domain Responsibility: Injects the per-run conversation id into
 *     {@link ChatClientRequest#context()} so Spring AI's
 *     {@code MessageChatMemoryAdvisor.before} can resolve it via
 *     {@code BaseChatMemoryAdvisor.getConversationId(Map)} without throwing
 *     {@code Assert.notNull("conversationId cannot be null")}.
 *
 *     <p><b>Why this exists:</b> The pre-existing
 *     {@code builder.defaultAdvisors(spec -&gt; spec.param("chat_memory_conversation_id", sessionId))}
 *     wiring in {@link ai.operativus.agentmanager.compute.service.AgentClientFactory}
 *     does NOT propagate the param into the per-request context map in Spring AI
 *     2.0-SNAPSHOT. {@code BaseChatMemoryAdvisor.getConversationId} reads from
 *     {@code request.context()} at call time, so the only reliable wire-up is to
 *     mutate the request's context inside an advisor that runs BEFORE
 *     {@code MessageChatMemoryAdvisor}.
 *
 *     <p>Pair with {@link ConversationIdResponseAdvisor} — together they close
 *     Bug #2 on both the read path (this advisor, request.context()) and the write
 *     path ({@link ConversationIdResponseAdvisor}, response.context()).
 *
 * State: Stateless after construction (sessionId is per-instance and the
 *     advisor is rebuilt per agent run in {@code AgentClientFactory.buildChatClient}).
 */
public class ConversationIdInjectionAdvisor implements CallAdvisor, StreamAdvisor {

    /** Spring AI's well-known context key — must match {@code BaseChatMemoryAdvisor}. */
    static final String CONTEXT_KEY = "chat_memory_conversation_id";

    private final String sessionId;

    public ConversationIdInjectionAdvisor(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String getName() {
        return "ConversationIdInjectionAdvisor";
    }

    @Override
    public int getOrder() {
        // Must run BEFORE MessageChatMemoryAdvisor. Spring AI's MessageChatMemoryAdvisor
        // defaults to Ordered.HIGHEST_PRECEDENCE + 1000 (see Spring AI source); ordering
        // ourselves at HIGHEST_PRECEDENCE ensures we mutate the context before it reads.
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(maybeInject(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // Streaming chain shares MessageChatMemoryAdvisor's before() read of
        // chat_memory_conversation_id from request.context(); without this mutation the
        // streaming run fails immediately with IllegalArgumentException: conversationId
        // cannot be null, manifesting as a [START, ERROR, CONTENT_DONE, STOP] SSE
        // sequence. Mirror the sync-path injection.
        return chain.nextStream(maybeInject(request));
    }

    private ChatClientRequest maybeInject(ChatClientRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            return request;
        }
        // Only inject when missing — defensive against future callers who set it
        // themselves via the request builder.
        if (request.context() != null && request.context().get(CONTEXT_KEY) != null) {
            return request;
        }
        return request.mutate().context(CONTEXT_KEY, sessionId).build();
    }
}
