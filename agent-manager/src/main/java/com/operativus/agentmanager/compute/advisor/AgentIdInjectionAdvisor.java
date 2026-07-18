package com.operativus.agentmanager.compute.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * Domain Responsibility: Injects per-run {@code agentId} and {@code sessionId} into
 *     {@link ChatClientRequest#context()} under the keys
 *     {@link PIIAnonymizationAdvisor#AGENT_ID_KEY} and
 *     {@link PIIAnonymizationAdvisor#SESSION_ID_KEY}. Sibling advisors that need to
 *     attribute audit rows or gate on per-agent compliance tier
 *     ({@link PIIAnonymizationAdvisor}, {@link StatefulStreamingPIIAdvisor}) read
 *     these context keys.
 *
 *     <p><b>Why this exists:</b> {@code AgentService.run}/{@code .stream} never
 *     populates {@code ChatClientRequest.context()} with these keys, and on the
 *     streaming path the advisor chain runs on a Reactor scheduler thread where
 *     JDK {@code ScopedValue}-bound {@code AgentContextHolder} fields are NOT
 *     visible (they bind only on the originating request thread). The only
 *     reliable wire-up is to mutate the request's context inside an advisor that
 *     runs BEFORE the consumers — same pattern PR #982 introduced with
 *     {@link ConversationIdInjectionAdvisor} for {@code chat_memory_conversation_id}.
 *
 * State: Stateless after construction (per-run instance built in
 *     {@link com.operativus.agentmanager.compute.service.AgentClientFactory#buildChatClient}).
 */
public class AgentIdInjectionAdvisor implements CallAdvisor, StreamAdvisor {

    private final String agentId;
    private final String sessionId;

    public AgentIdInjectionAdvisor(String agentId, String sessionId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public String getName() {
        return "AgentIdInjectionAdvisor";
    }

    @Override
    public int getOrder() {
        // Must run BEFORE PIIAnonymizationAdvisor (order 10) and
        // StatefulStreamingPIIAdvisor (order 15). HIGHEST_PRECEDENCE guarantees
        // the injection happens first regardless of other HIGHEST_PRECEDENCE
        // advisors' tie-breaking.
        return Integer.MIN_VALUE + 50;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(inject(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(inject(request));
    }

    private ChatClientRequest inject(ChatClientRequest request) {
        ChatClientRequest mutated = request;
        // Only inject when missing — defensive against future callers who set these
        // keys themselves via the request builder. Matches the
        // ConversationIdInjectionAdvisor pattern (PR #982).
        if (agentId != null && !agentId.isBlank()
                && (mutated.context() == null
                        || mutated.context().get(PIIAnonymizationAdvisor.AGENT_ID_KEY) == null)) {
            mutated = mutated.mutate()
                    .context(PIIAnonymizationAdvisor.AGENT_ID_KEY, agentId)
                    .build();
        }
        if (sessionId != null && !sessionId.isBlank()
                && (mutated.context() == null
                        || mutated.context().get(PIIAnonymizationAdvisor.SESSION_ID_KEY) == null)) {
            mutated = mutated.mutate()
                    .context(PIIAnonymizationAdvisor.SESSION_ID_KEY, sessionId)
                    .build();
        }
        return mutated;
    }
}
