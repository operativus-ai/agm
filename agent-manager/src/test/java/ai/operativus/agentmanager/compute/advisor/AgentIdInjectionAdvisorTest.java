package ai.operativus.agentmanager.compute.advisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AgentIdInjectionAdvisor} — the HIGHEST_PRECEDENCE Call+Stream advisor that
 * populates {@code request.context()} with the per-run {@code agentId} and
 * {@code sessionId} keys under {@link PIIAnonymizationAdvisor#AGENT_ID_KEY} and
 * {@link PIIAnonymizationAdvisor#SESSION_ID_KEY}.
 *
 * <p>Cases:
 * <ol>
 *   <li>Null/blank agentId AND sessionId — request passes through unchanged.</li>
 *   <li>agentId only set — only {@code AGENT_ID_KEY} injected.</li>
 *   <li>sessionId only set — only {@code SESSION_ID_KEY} injected.</li>
 *   <li>Both set — both keys injected.</li>
 *   <li>Pre-existing keys preserved (caller-set wins, defensive match with
 *       {@link ConversationIdInjectionAdvisor}).</li>
 *   <li>Unrelated context keys preserved (no clobber of caller's other state).</li>
 *   <li>{@code adviseStream} mirrors {@code adviseCall} — same injection behaviour
 *       so the streaming path's downstream advisors see the same context.</li>
 *   <li>Order is HIGHEST_PRECEDENCE so it runs before PIIAnonymizationAdvisor (order 10)
 *       and StatefulStreamingPIIAdvisor (order 15).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AgentIdInjectionAdvisorTest {

    private static final String AGENT = PIIAnonymizationAdvisor.AGENT_ID_KEY;
    private static final String SESSION = PIIAnonymizationAdvisor.SESSION_ID_KEY;

    @Mock private CallAdvisorChain callChain;
    @Mock private StreamAdvisorChain streamChain;
    @Mock private ChatClientResponse response;

    @Test
    void nullAgentAndSession_passesRequestThroughUnchanged() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor(null, null);
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        ChatClientRequest forwarded = captor.getValue();
        assertThat(forwarded.context()).doesNotContainKey(AGENT);
        assertThat(forwarded.context()).doesNotContainKey(SESSION);
    }

    @Test
    void blankValues_passThroughWithoutInjection() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("  ", "\t");
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        ChatClientRequest forwarded = captor.getValue();
        assertThat(forwarded.context()).doesNotContainKey(AGENT);
        assertThat(forwarded.context()).doesNotContainKey(SESSION);
    }

    @Test
    void agentOnly_injectsAgentKeyOnly() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("agent-7", null);
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        Map<String, Object> ctx = captor.getValue().context();
        assertThat(ctx).containsEntry(AGENT, "agent-7");
        assertThat(ctx).doesNotContainKey(SESSION);
    }

    @Test
    void sessionOnly_injectsSessionKeyOnly() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor(null, "sess-42");
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        Map<String, Object> ctx = captor.getValue().context();
        assertThat(ctx).containsEntry(SESSION, "sess-42");
        assertThat(ctx).doesNotContainKey(AGENT);
    }

    @Test
    void bothSet_injectsBoth() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("agent-7", "sess-42");
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        Map<String, Object> ctx = captor.getValue().context();
        assertThat(ctx).containsEntry(AGENT, "agent-7");
        assertThat(ctx).containsEntry(SESSION, "sess-42");
    }

    @Test
    void preExistingKeys_areNotOverwritten() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("from-advisor", "from-advisor");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(AGENT, "from-caller");
        ctx.put(SESSION, "from-caller");
        ChatClientRequest in = requestWithContext(ctx);

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        Map<String, Object> forwarded = captor.getValue().context();
        assertThat(forwarded).containsEntry(AGENT, "from-caller");
        assertThat(forwarded).containsEntry(SESSION, "from-caller");
    }

    @Test
    void unrelatedContextKeys_arePreserved() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("agent-7", "sess-42");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("chat_memory_conversation_id", "pre-existing");
        ctx.put("custom_caller_marker", 123);
        ChatClientRequest in = requestWithContext(ctx);

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(callChain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, callChain);

        Map<String, Object> forwarded = captor.getValue().context();
        assertThat(forwarded).containsEntry("chat_memory_conversation_id", "pre-existing");
        assertThat(forwarded).containsEntry("custom_caller_marker", 123);
        assertThat(forwarded).containsEntry(AGENT, "agent-7");
        assertThat(forwarded).containsEntry(SESSION, "sess-42");
    }

    @Test
    void adviseStream_injectsSameKeysAsAdviseCall() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("agent-7", "sess-42");
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(streamChain.nextStream(captor.capture())).thenReturn(Flux.just(response));

        StepVerifier.create(advisor.adviseStream(in, streamChain))
                .expectNext(response)
                .verifyComplete();

        Map<String, Object> forwarded = captor.getValue().context();
        assertThat(forwarded).containsEntry(AGENT, "agent-7");
        assertThat(forwarded).containsEntry(SESSION, "sess-42");
    }

    @Test
    void orderAndName_lockedAtHighestPrecedence() {
        AgentIdInjectionAdvisor advisor = new AgentIdInjectionAdvisor("any", "any");
        // Must run BEFORE PIIAnonymizationAdvisor (order 10) and StatefulStreamingPIIAdvisor (order 15).
        assertThat(advisor.getOrder()).isLessThan(10);
        assertThat(advisor.getName()).isEqualTo("AgentIdInjectionAdvisor");
    }

    private static ChatClientRequest requestWithContext(Map<String, Object> ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("hi")))
                .context(ctx)
                .build();
    }
}
