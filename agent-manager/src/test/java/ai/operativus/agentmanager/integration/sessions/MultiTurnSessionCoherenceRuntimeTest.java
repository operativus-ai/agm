package ai.operativus.agentmanager.integration.sessions;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins that the chat-memory advisor assembles the prior turns
 *   of a conversation into the prompt that hits the LLM boundary on every subsequent
 *   turn. Existing tests (e.g. {@code SyncRunsRuntimeTest},
 *   {@code StreamingRunsRuntimeTest}, {@code ChatMemoryRuntimeTest}) prove the
 *   {@code agent_messages} rows accumulate per turn but DO NOT verify the per-turn
 *   prompt actually sees prior history — the in-context history assembly is the
 *   contract the model sees, not the row persistence.
 *
 *   <p>This test captures the production prompts via {@code FakeChatModel.receivedPrompts()}
 *   and asserts that the second + third turn's prompt window includes the prior
 *   user/assistant messages. A regression in the {@code MessageChatMemoryAdvisor}
 *   wiring or the agent's {@code addHistoryToMessages} flag would silently degrade
 *   chat coherence — the user would notice ("the bot forgot what I said") but the
 *   automated tests would all pass on row counts.
 *
 *   <p>Three pins:
 *   <ol>
 *     <li>Turn 1 prompt contains the first user message and nothing else (no
 *         pre-existing history to draw from).</li>
 *     <li>Turn 2 prompt contains BOTH the first user message AND the first assistant
 *         reply, plus the new user message. Proves the advisor pulled history from
 *         persistence on the second turn.</li>
 *     <li>Turn 3 prompt contains messages from turns 1 and 2 plus the new user
 *         message. Catches a window-size regression where history is truncated
 *         after the first turn.</li>
 *   </ol>
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class MultiTurnSessionCoherenceRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        fakeModel.reset();
    }

    // Pin: three sequential turns on the same sessionId — each subsequent prompt
    // sent to the model must include prior turns' user + assistant messages. This is
    // the assertion that the chat-memory advisor is actually doing its job, not just
    // that rows are persisting.
    @Test
    void threeTurnsOnSameSessionId_secondAndThirdPromptsIncludePriorUserAndAssistantTurns() {
        HttpHeaders auth = authenticatedHeaders("multi-turn-coherence");
        String agentId = createAgentViaApi(auth, "Multi-Turn Coherence Agent");

        fakeModel.respondWith("Nice to meet you, Scott.")
                .respondWith("You said you were Scott.")
                .respondWith("Confirmed: I remember you, Scott.");

        String sessionId = "session-multi-turn-" + UUID.randomUUID();

        // ── Turn 1 ────────────────────────────────────────────────
        postRun(auth, agentId, sessionId, "Hi I am Scott.");
        awaitRunCount(sessionId, 1L);

        Prompt firstPrompt = fakeModel.receivedPrompts().get(0);
        List<Message> firstMessages = firstPrompt.getInstructions();
        assertTrue(messagesContainText(firstMessages, "Hi I am Scott."),
                "turn-1 prompt must include the first user message");
        assertEquals(countUserMessages(firstMessages), 1,
                "turn-1 prompt must have exactly ONE user message — no prior history existed");

        // ── Turn 2 ────────────────────────────────────────────────
        postRun(auth, agentId, sessionId, "What did I say?");
        awaitRunCount(sessionId, 2L);

        Prompt secondPrompt = fakeModel.receivedPrompts().get(1);
        List<Message> secondMessages = secondPrompt.getInstructions();
        assertTrue(messagesContainText(secondMessages, "Hi I am Scott."),
                "turn-2 prompt must include the turn-1 user message — proves MessageChatMemoryAdvisor "
                        + "pulled the prior turn from agent_messages. Missing here = chat forgets the "
                        + "very last turn even though rows persisted.");
        assertTrue(messagesContainText(secondMessages, "Nice to meet you, Scott."),
                "turn-2 prompt must include the turn-1 assistant reply — same advisor contract "
                        + "but on the assistant side; missing here = single-sided history");
        assertTrue(messagesContainText(secondMessages, "What did I say?"),
                "turn-2 prompt must include the new turn-2 user message");

        // ── Turn 3 ────────────────────────────────────────────────
        postRun(auth, agentId, sessionId, "Confirm please.");
        awaitRunCount(sessionId, 3L);

        Prompt thirdPrompt = fakeModel.receivedPrompts().get(2);
        List<Message> thirdMessages = thirdPrompt.getInstructions();
        assertTrue(messagesContainText(thirdMessages, "Hi I am Scott."),
                "turn-3 prompt must STILL include turn-1's user message. Missing here means "
                        + "the chat-memory window is too narrow (1 turn) — chat coherence "
                        + "breaks for any 3+ turn conversation.");
        assertTrue(messagesContainText(thirdMessages, "Nice to meet you, Scott."),
                "turn-3 prompt must include turn-1's assistant reply for full bidirectional context");
        assertTrue(messagesContainText(thirdMessages, "What did I say?"),
                "turn-3 prompt must include turn-2's user message");
        assertTrue(messagesContainText(thirdMessages, "You said you were Scott."),
                "turn-3 prompt must include turn-2's assistant reply");
        assertTrue(messagesContainText(thirdMessages, "Confirm please."),
                "turn-3 prompt must include the new turn-3 user message");

        // Sanity: exactly 3 prompts captured (no retries leaked).
        assertEquals(3, fakeModel.receivedPrompts().size(),
                "exactly 3 prompts must have been issued — one per turn");
    }

    // ─── helpers ───

    private void postRun(HttpHeaders auth, String agentId, String sessionId, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "each turn POST /runs must return 200 — failures here race the prompt-capture "
                        + "assertions");
    }

    private void awaitRunCount(String sessionId, long expected) {
        // The chat-memory write-through happens inside the run pipeline and races with the
        // HTTP return. Awaitility lets the row settle before the next turn issues its POST.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_runs WHERE session_id = ? AND status = 'COMPLETED'",
                    Long.class, sessionId);
            assertEquals(expected, count,
                    "expected " + expected + " COMPLETED runs before next turn");
        });
    }

    private static boolean messagesContainText(List<Message> messages, String needle) {
        for (Message m : messages) {
            String text = m.getText();
            if (text != null && text.contains(needle)) return true;
        }
        return false;
    }

    private static int countUserMessages(List<Message> messages) {
        int count = 0;
        for (Message m : messages) {
            if ("USER".equalsIgnoreCase(m.getMessageType().name())) count++;
        }
        return count;
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Multi-turn coherence fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        // Memory advisor + addHistoryToMessages are the wiring under test — opt in
        // explicitly so the agent is configured for multi-turn chat (the FE chat path
        // always sets these).
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-multi-turn-1234",
                // ROLE_ADMIN required to create the fixture agent via /api/admin/agents (gated since #969).
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
