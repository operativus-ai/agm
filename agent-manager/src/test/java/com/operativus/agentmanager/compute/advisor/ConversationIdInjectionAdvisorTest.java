package com.operativus.agentmanager.compute.advisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ConversationIdInjectionAdvisor} — the workaround for Spring AI 2.0-SNAPSHOT's
 * broken propagation of {@code chat_memory_conversation_id} via
 * {@code defaultAdvisors(Consumer).spec.param(...)}.
 *
 * <p>Cases:
 * <ol>
 *   <li>Null/blank sessionId — request passes through unchanged.</li>
 *   <li>Empty context — the key gets injected with the sessionId value.</li>
 *   <li>Pre-existing key in context — DO NOT overwrite (caller-set wins).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ConversationIdInjectionAdvisorTest {

    @Mock private CallAdvisorChain chain;
    @Mock private ChatClientResponse response;

    @Test
    void nullSessionId_passesThrough() {
        ConversationIdInjectionAdvisor advisor = new ConversationIdInjectionAdvisor(null);
        ChatClientRequest in = requestWithContext(new HashMap<>());
        when(chain.nextCall(in)).thenReturn(response);

        assertThat(advisor.adviseCall(in, chain)).isSameAs(response);
    }

    @Test
    void blankSessionId_passesThrough() {
        ConversationIdInjectionAdvisor advisor = new ConversationIdInjectionAdvisor("  ");
        ChatClientRequest in = requestWithContext(new HashMap<>());
        when(chain.nextCall(in)).thenReturn(response);

        assertThat(advisor.adviseCall(in, chain)).isSameAs(response);
    }

    @Test
    void missingKey_isInjected() {
        ConversationIdInjectionAdvisor advisor = new ConversationIdInjectionAdvisor("sess-42");
        ChatClientRequest in = requestWithContext(new HashMap<>());

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(chain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, chain);

        ChatClientRequest forwarded = captor.getValue();
        assertThat(forwarded.context()).containsEntry("chat_memory_conversation_id", "sess-42");
    }

    @Test
    void preExistingKey_isNotOverwritten() {
        ConversationIdInjectionAdvisor advisor = new ConversationIdInjectionAdvisor("from-advisor");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("chat_memory_conversation_id", "from-caller");
        ChatClientRequest in = requestWithContext(ctx);

        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        when(chain.nextCall(captor.capture())).thenReturn(response);

        advisor.adviseCall(in, chain);

        assertThat(captor.getValue().context())
                .containsEntry("chat_memory_conversation_id", "from-caller");
    }

    @Test
    void ordering_isHighestPrecedence() {
        ConversationIdInjectionAdvisor advisor = new ConversationIdInjectionAdvisor("any");
        assertThat(advisor.getOrder()).isLessThan(Integer.MIN_VALUE / 2);
        assertThat(advisor.getName()).isEqualTo("ConversationIdInjectionAdvisor");
    }

    private static ChatClientRequest requestWithContext(Map<String, Object> ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("hi")))
                .context(ctx)
                .build();
    }
}
