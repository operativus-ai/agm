package com.operativus.agentmanager.integration.sessions;

import com.operativus.agentmanager.control.repository.MessageRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.control.service.PersistentChatMemory;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentMessage;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the {@link PersistentChatMemory} contract that
 *   {@code MessageChatMemoryAdvisor} relies on — Spring AI's standard
 *   {@code @EventListener}-style advisor wired in {@code AgentClientFactory:482} for any
 *   agent with persistent memory enabled. The advisor calls {@code add(conversationId, …)}
 *   per turn and {@code get(conversationId, lastN)} before the next LLM call.
 *
 *   <p>Test cases:
 *   <ol>
 *     <li><strong>Auto-created session captures the real orgId, not userId</strong>
 *         (M1 production fix). The fix-shipped-in-this-PR: line ~74 of
 *         {@code PersistentChatMemory.ensureSessionExists} now reads orgId from
 *         {@link AgentContextHolder#getOrgId()} instead of writing the userId. Before:
 *         two users in the same org created sessions with DIFFERENT orgIds (each set to
 *         their own username) — every tenant-scoped query on
 *         {@code agent_sessions.org_id} returned wrong rows.</li>
 *     <li><strong>add then get round-trips messages in chronological order</strong>.
 *         {@code MessageChatMemoryAdvisor}'s per-turn cycle relies on this — if the order
 *         flips, the LLM sees its own historical reply as the user prompt for the next
 *         turn (catastrophic).</li>
 *     <li><strong>Cross-session isolation</strong>: messages added under conversationId
 *         "A" must NOT appear in {@code get("B")}. A leak would mean every authenticated
 *         user's conversation history bleeds into other users' agent contexts.</li>
 *     <li><strong>{@code clear()} is intentionally a no-op</strong>: pinned as the
 *         documented Spring AI contract override (audit-trail safety).</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ChatMemoryRuntimeTest extends BaseIntegrationTest {

    @Autowired private PersistentChatMemory persistentChatMemory;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MessageRepository messageRepository;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agent_messages");
        jdbc.update("DELETE FROM agent_sessions");
    }

    @Test
    void autoCreatedSession_orgIdMatchesAgentContextHolder_M1ProductionFix() {
        // ScopedValue chain mirrors what AgentService.run binds before the
        // MessageChatMemoryAdvisor calls add() on the chat memory.
        String orgId = "org-m1-fix-" + UUID.randomUUID();
        String sessionId = "session-m1-fix-" + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, orgId).run(() -> {
            persistentChatMemory.add(sessionId, List.of(new UserMessage("kickoff")));
        });

        AgentSession persisted = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(orgId, persisted.getOrgId(),
                "M1 fix: auto-created session must capture orgId from AgentContextHolder, "
                        + "not from userId. A userId value here means PersistentChatMemory "
                        + "regressed to the pre-fix shape and every tenant-scoped query on "
                        + "agent_sessions.org_id returns wrong rows.");
        assertThat(persisted.getOrgId())
                .as("orgId must NOT equal the resolved userId — that was the pre-fix bug")
                .isNotEqualTo(persisted.getUserId());
    }

    @Test
    void addThenGet_roundTripsMessagesInChronologicalOrder() {
        String orgId = "org-m1-roundtrip-" + UUID.randomUUID();
        String sessionId = "session-m1-roundtrip-" + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, orgId).run(() -> {
            persistentChatMemory.add(sessionId, List.of(
                    new UserMessage("user turn 1"),
                    new AssistantMessage("assistant turn 1"),
                    new UserMessage("user turn 2"),
                    new AssistantMessage("assistant turn 2")));
        });

        List<Message> retrieved = persistentChatMemory.get(sessionId, 10);
        // 4 messages added; with no prior SystemMessage summary, get returns them in order.
        assertThat(retrieved).hasSize(4);
        assertThat(retrieved.get(0).getText()).isEqualTo("user turn 1");
        assertThat(retrieved.get(1).getText()).isEqualTo("assistant turn 1");
        assertThat(retrieved.get(2).getText()).isEqualTo("user turn 2");
        assertThat(retrieved.get(3).getText()).isEqualTo("assistant turn 2");
    }

    @Test
    void crossSessionIsolation_messagesScopedToConversationId() {
        String orgId = "org-m1-isolation-" + UUID.randomUUID();
        String sessionA = "session-m1-A-" + UUID.randomUUID();
        String sessionB = "session-m1-B-" + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, orgId).run(() -> {
            persistentChatMemory.add(sessionA, List.of(
                    new UserMessage("alpha message — must NOT bleed into session B"),
                    new AssistantMessage("alpha reply")));
            persistentChatMemory.add(sessionB, List.of(
                    new UserMessage("bravo message — must NOT bleed into session A")));
        });

        List<Message> aHistory = persistentChatMemory.get(sessionA, 10);
        List<Message> bHistory = persistentChatMemory.get(sessionB, 10);

        assertThat(aHistory).hasSize(2);
        assertThat(aHistory.stream().map(Message::getText))
                .as("session A history must contain ONLY session A's messages — a B leak "
                        + "would mean every user's conversation bleeds across sessions")
                .allMatch(t -> t.contains("alpha"))
                .noneMatch(t -> t.contains("bravo"));

        assertThat(bHistory).hasSize(1);
        assertThat(bHistory.get(0).getText())
                .as("session B history must contain ONLY session B's message")
                .contains("bravo")
                .doesNotContain("alpha");

        // DB-level invariant: total messages = 3 across both sessions; each session row has
        // its own session_id FK. A non-3 count here would mean the messages table is being
        // double-inserted or a UNIQUE constraint regressed.
        long totalMessages = messageRepository.findAll().size();
        assertEquals(3L, totalMessages,
                "exactly 3 agent_messages rows must persist — 2 for session A + 1 for B");
    }

    @Test
    void clear_isIntentionallyNoOp_pinAsShipped() {
        // Spring AI's ChatMemory.clear contract: implementations may purge. PersistentChatMemory
        // intentionally leaves it as a no-op for audit-trail safety (documented at the method
        // body). Pin this: clear() does NOT touch agent_messages.
        String orgId = "org-m1-clear-" + UUID.randomUUID();
        String sessionId = "session-m1-clear-" + UUID.randomUUID();

        ScopedValue.where(AgentContextHolder.orgId, orgId).run(() -> {
            persistentChatMemory.add(sessionId, List.of(
                    new UserMessage("must survive clear()"),
                    new AssistantMessage("must also survive")));
        });

        long beforeClear = messageRepository.findAll().size();
        persistentChatMemory.clear(sessionId);
        long afterClear = messageRepository.findAll().size();

        assertEquals(beforeClear, afterClear,
                "PersistentChatMemory.clear is intentionally a no-op for audit safety; "
                        + "if this assertion flips (count decreases), a future refactor "
                        + "implemented clear() — verify that change against the documented "
                        + "audit-trail contract before approving");

        AgentMessage stillPresent = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).get(0);
        assertThat(stillPresent.getContent()).isEqualTo("must survive clear()");
    }
}
