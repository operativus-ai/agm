package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.core.registry.MemoryOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AgenticMemoryAdvisor}. Sits at order 50 (post-PII boundary by
 * deliberate product choice — memories are "curated agent context", not subject
 * to user-input redaction; see {@code AdvisorPiiBoundaryContractTest} M3 for the
 * documented "memory ingested with user PII reaches LLM unredacted" tradeoff).
 *
 * <p>Cases pin:
 * <ol>
 *   <li>Null/blank userId → request passes through, memory store NEVER queried.</li>
 *   <li>Memory store returns empty/null → request passes through unchanged.</li>
 *   <li>Happy path: rules injected as bulleted block in a unified SystemMessage with
 *       "LEARNED MEMORY RULES (STRICT COMPLIANCE REQUIRED)" header and closing rule.</li>
 *   <li>Multiple SystemMessages unified into ONE (Google GenAI compat).</li>
 *   <li>Memory store exception is swallowed: chain receives ORIGINAL request.</li>
 *   <li>User message preserved verbatim through augmentation.</li>
 *   <li>order==50, name pinned.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AgenticMemoryAdvisorTest {

    @Mock private MemoryOperations memoryOperations;
    @Mock private CallAdvisorChain chain;

    @Test
    void nullUserId_passesRequestThrough_memoryStoreNeverQueried() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, null);
        ChatClientRequest in = requestWith(List.of(new UserMessage("hi")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
        verifyNoInteractions(memoryOperations);
    }

    @Test
    void blankUserId_passesRequestThrough_memoryStoreNeverQueried() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "   ");
        ChatClientRequest in = requestWith(List.of(new UserMessage("hi")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
        verifyNoInteractions(memoryOperations);
    }

    @Test
    void emptyMemoryList_passesRequestUnchanged() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1")).thenReturn(List.of());
        ChatClientRequest in = requestWith(List.of(new SystemMessage("base"), new UserMessage("hi")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
    }

    @Test
    void nullMemoryList_passesRequestUnchanged() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1")).thenReturn(null);
        ChatClientRequest in = requestWith(List.of(new UserMessage("hi")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
    }

    @Test
    void happyPath_injectsRulesAsBulletedSystemBlock() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1"))
                .thenReturn(List.of("Prefers concise answers", "Speaks Portuguese"));
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(requestWith(List.of(
                new SystemMessage("base persona"),
                new UserMessage("hello"))), chain);

        Prompt augmented = captureAugmentedPrompt();
        long systemCount = augmented.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM).count();
        assertThat(systemCount).as("must unify to one SystemMessage").isEqualTo(1L);

        String system = augmented.getInstructions().get(0).getText();
        assertThat(system).contains("base persona");
        assertThat(system).contains("LEARNED MEMORY RULES (STRICT COMPLIANCE REQUIRED)");
        assertThat(system).contains("- Prefers concise answers");
        assertThat(system).contains("- Speaks Portuguese");
        assertThat(system).contains("----------------------------------------------------------");
    }

    @Test
    void multipleSystemMessages_areUnifiedIntoOne() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1")).thenReturn(List.of("Rule A"));
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(requestWith(List.of(
                new SystemMessage("persona-A"),
                new SystemMessage("persona-B"),
                new UserMessage("q"))), chain);

        Prompt augmented = captureAugmentedPrompt();
        long systemCount = augmented.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM).count();
        assertThat(systemCount).isEqualTo(1L);
        String system = augmented.getInstructions().get(0).getText();
        assertThat(system).contains("persona-A").contains("persona-B").contains("Rule A");
    }

    @Test
    void memoryOperationsThrows_isSwallowed_chainReceivesOriginalRequest() {
        // The advisor must not break the request pipeline if the memory store is
        // down. Failure mode: log + pass through the ORIGINAL (unaugmented) request.
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1"))
                .thenThrow(new RuntimeException("memory store unreachable"));
        ChatClientRequest in = requestWith(List.of(new UserMessage("hi")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
        // No augmented call.
        verify(chain, never()).nextCall(org.mockito.ArgumentMatchers.argThat(
                r -> r != null && r != in));
    }

    @Test
    void userMessageText_isPreservedVerbatim() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        when(memoryOperations.searchUserMemories("user-1")).thenReturn(List.of("Rule"));
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(requestWith(List.of(new UserMessage("verbatim user text"))), chain);

        Prompt augmented = captureAugmentedPrompt();
        Message user = augmented.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .findFirst().orElseThrow();
        assertThat(user.getText()).isEqualTo("verbatim user text");
    }

    @Test
    void orderingAndName_arePinned() {
        AgenticMemoryAdvisor advisor = new AgenticMemoryAdvisor(memoryOperations, "user-1");
        assertThat(advisor.getOrder()).isEqualTo(50);
        assertThat(advisor.getName()).isEqualTo("AgenticMemoryAdvisor");
    }

    private Prompt captureAugmentedPrompt() {
        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(captor.capture());
        return captor.getValue().prompt();
    }

    private static ChatClientRequest requestWith(List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(new HashMap<>())
                .build();
    }

    private static ChatClientResponse emptyResponse() {
        return ChatClientResponse.builder().build();
    }
}
