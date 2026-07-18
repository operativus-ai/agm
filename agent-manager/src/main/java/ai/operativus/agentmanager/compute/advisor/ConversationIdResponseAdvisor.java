package ai.operativus.agentmanager.compute.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * Domain Responsibility: Carries the conversation id forward from
 *     {@link ChatClientRequest#context()} into {@link ChatClientResponse#context()} so
 *     {@code MessageChatMemoryAdvisor.after} can resolve it via
 *     {@code BaseChatMemoryAdvisor.getConversationId(Map)} when persisting the assistant
 *     reply to memory.
 *
 *     <p><b>Why this exists:</b> {@link ConversationIdInjectionAdvisor} closes the read
 *     path (request context). But Spring AI 2.0-SNAPSHOT's terminal call advisor builds
 *     the {@code ChatClientResponse} without propagating the request's context map.
 *     {@code MessageChatMemoryAdvisor.after} then reads {@code response.context()} (NOT
 *     {@code request.context()}) and throws {@code Assert.notNull("conversationId cannot
 *     be null")}. Manifests as a 500 on the SECOND turn of any memory-enabled agent —
 *     turn 1 happens to succeed because the cached LLM response from a prior session
 *     carries the key forward (semantic cache hit pre-populates response context).
 *
 *     <p>Ordering: must sit AFTER {@code MessageChatMemoryAdvisor} in the chain so that
 *     on the unwind it executes BEFORE {@code MessageChatMemoryAdvisor.after} (BaseAdvisor
 *     wraps the chain; lower order = outer wrapper). The order
 *     {@code HIGHEST_PRECEDENCE + 1500} places it directly after Spring AI's MCM at
 *     {@code HIGHEST_PRECEDENCE + 1000} and before the application-tier advisors at
 *     order &ge; 0.
 *
 * State: Stateless after construction.
 */
public class ConversationIdResponseAdvisor implements CallAdvisor {

    @Override
    public String getName() {
        return "ConversationIdResponseAdvisor";
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 1500;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String convId = request.context() == null
                ? null
                : (String) request.context().get(ConversationIdInjectionAdvisor.CONTEXT_KEY);
        ChatClientResponse resp = chain.nextCall(request);
        if (resp == null || convId == null) {
            return resp;
        }
        // Only inject when missing — defensive against future Spring AI versions that
        // propagate context themselves.
        if (resp.context() != null && resp.context().get(ConversationIdInjectionAdvisor.CONTEXT_KEY) != null) {
            return resp;
        }
        return resp.mutate().context(ConversationIdInjectionAdvisor.CONTEXT_KEY, convId).build();
    }
}
