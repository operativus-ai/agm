package ai.operativus.agentmanager.compute.advisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Pins {@link ConversationIdResponseAdvisor} — the response-side companion to
 * {@link ConversationIdInjectionAdvisor}. Bug #2 turn 2: Spring AI 2.0-SNAPSHOT's terminal
 * call advisor builds a {@code ChatClientResponse} without propagating request context, so
 * {@code MessageChatMemoryAdvisor.after()} resolves {@code chat_memory_conversation_id} from
 * an empty response context and trips {@code Assert.notNull("conversationId cannot be null")}.
 *
 * <p>Cases:
 * <ol>
 *   <li>Conv id present in request, missing in response — copied through.</li>
 *   <li>Conv id present on both sides — caller-set response value wins (no overwrite).</li>
 *   <li>No conv id in request context — response passed through untouched.</li>
 *   <li>Chain returns {@code null} response — returned as-is (no NPE).</li>
 *   <li>Order + name pinned so silent reorders fail visibly (Bug #2-class regression guard).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ConversationIdResponseAdvisorTest {

    private static final String KEY = "chat_memory_conversation_id";

    @Mock private CallAdvisorChain chain;

    private final ConversationIdResponseAdvisor advisor = new ConversationIdResponseAdvisor();

    @Test
    void convIdInRequest_andResponseMissing_isCopiedThrough() {
        Map<String, Object> reqCtx = new HashMap<>();
        reqCtx.put(KEY, "sess-42");
        ChatClientRequest in = requestWithContext(reqCtx);
        ChatClientResponse downstream = responseWithContext(new HashMap<>());
        when(chain.nextCall(in)).thenReturn(downstream);

        ChatClientResponse out = advisor.adviseCall(in, chain);

        assertThat(out).isNotNull();
        assertThat(out.context()).containsEntry(KEY, "sess-42");
    }

    @Test
    void convIdInResponseAlready_isNotOverwritten() {
        Map<String, Object> reqCtx = new HashMap<>();
        reqCtx.put(KEY, "from-request");
        ChatClientRequest in = requestWithContext(reqCtx);
        Map<String, Object> respCtx = new HashMap<>();
        respCtx.put(KEY, "from-response");
        ChatClientResponse downstream = responseWithContext(respCtx);
        when(chain.nextCall(in)).thenReturn(downstream);

        ChatClientResponse out = advisor.adviseCall(in, chain);

        assertThat(out).isSameAs(downstream);
        assertThat(out.context()).containsEntry(KEY, "from-response");
    }

    @Test
    void noConvIdInRequest_responsePassedThrough() {
        ChatClientRequest in = requestWithContext(new HashMap<>());
        ChatClientResponse downstream = responseWithContext(new HashMap<>());
        when(chain.nextCall(in)).thenReturn(downstream);

        ChatClientResponse out = advisor.adviseCall(in, chain);

        assertThat(out).isSameAs(downstream);
        assertThat(out.context()).doesNotContainKey(KEY);
    }

    @Test
    void nullResponseFromChain_returnsNullSafely() {
        Map<String, Object> reqCtx = new HashMap<>();
        reqCtx.put(KEY, "sess-42");
        ChatClientRequest in = requestWithContext(reqCtx);
        when(chain.nextCall(in)).thenReturn(null);

        assertThat(advisor.adviseCall(in, chain)).isNull();
    }

    @Test
    void ordering_sitsAfterMcm_andNameIsStable() {
        // MessageChatMemoryAdvisor.getOrder() returns HIGHEST_PRECEDENCE + 1000;
        // this advisor must run AFTER it on the unwind so MCM.after() sees the key.
        assertThat(advisor.getOrder()).isEqualTo(Integer.MIN_VALUE + 1500);
        assertThat(advisor.getOrder()).isGreaterThan(Integer.MIN_VALUE + 1000);
        assertThat(advisor.getName()).isEqualTo("ConversationIdResponseAdvisor");
    }

    private static ChatClientRequest requestWithContext(Map<String, Object> ctx) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("hi")))
                .context(ctx)
                .build();
    }

    private static ChatClientResponse responseWithContext(Map<String, Object> ctx) {
        return ChatClientResponse.builder()
                .context(ctx)
                .build();
    }
}
