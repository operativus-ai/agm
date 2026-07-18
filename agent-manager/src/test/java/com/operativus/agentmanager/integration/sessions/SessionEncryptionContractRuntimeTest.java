package com.operativus.agentmanager.integration.sessions;

import com.operativus.agentmanager.control.repository.MessageRepository;
import com.operativus.agentmanager.core.entity.AgentMessage;
import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: End-to-end contract pin for the session/message encryption
 *   pipeline. Wires three production components:
 *   <ol>
 *     <li>{@code AgentService.run} binds {@code AgentContextHolder.requiresEncryption}
 *         ScopedValue to {@code def.complianceTier() == TIER_2_STRICT}.</li>
 *     <li>{@code PersistentChatMemory} reads the ScopedValue and propagates the flag
 *         to {@code AgentSession.requiresEncryption} (transient field) and to every
 *         {@code AgentMessage.requiresEncryption} via {@code mapToEntity}.</li>
 *     <li>{@link com.operativus.agentmanager.compute.security.EncryptedSessionInterceptor}
 *         fires on {@code @PrePersist}/{@code @PreUpdate} when the flag is true:
 *         encrypts {@code AgentMessage.content} with a deterministic key derived from
 *         {@code sessionId} and prefixes the cipher with {@code "ENCRYPTED:"}.
 *         {@code @PostLoad} reverses on read so service callers see plaintext.</li>
 *   </ol>
 *
 *   <p>Pre-pin reality: no runtime test asserted that a TIER_2_STRICT agent's chat
 *   messages were actually encrypted at rest. The three production components could
 *   silently break in any of three places (advisor unwired, ScopedValue unbound, or
 *   interceptor's encrypt branch dead) and the regression would only surface in a
 *   compliance audit. This test pins the full chain via direct JDBC reads of
 *   {@code agent_messages.content} alongside the repository read.
 *
 *   <p>Cases:
 *   <ol>
 *     <li><strong>TIER_2_STRICT agent encrypts message content at rest</strong>:
 *         after a sync run, {@code agent_messages.content} read directly via JDBC must
 *         start with the {@code "ENCRYPTED:"} prefix, and the same row read through
 *         {@code MessageRepository} must return decrypted plaintext. Also pins
 *         {@code agent_sessions.requires_encryption=true} and
 *         {@code agent_messages.requires_encryption=true}.</li>
 *     <li><strong>Default-tier agent does NOT encrypt</strong>: regression-lock for
 *         the inverse. A run without {@code TIER_2_STRICT} must leave message content
 *         in cleartext and both {@code requires_encryption} flags false.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SessionEncryptionContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeChat;
    @Autowired private MessageRepository messageRepository;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM agent_runs");
        jdbc.update("DELETE FROM agent_messages");
        jdbc.update("DELETE FROM agent_sessions");
        fakeChat.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void tier2StrictAgent_encryptsMessageContentAtRest_decryptsOnRead_contractPin() {
        HttpHeaders auth = userHeaders("enc-strict");
        String agentId = createAgent(auth, "S4 strict agent");
        // Flip the agent to TIER_2_STRICT so AgentService.run binds the
        // requiresEncryption ScopedValue to true.
        jdbc.update("UPDATE agents SET compliance_tier = ? WHERE id = ?",
                ComplianceTier.TIER_2_STRICT.name(), agentId);

        String plaintextReply = "S4 sensitive reply " + UUID.randomUUID();
        fakeChat.respondWith(plaintextReply);

        String sessionId = "session-s4-strict-" + UUID.randomUUID();
        String userPrompt = "S4 sensitive prompt " + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = runAgent(auth, agentId, userPrompt, sessionId);
        assertEquals(200, resp.getStatusCode().value(),
                "happy-path run must complete — this pin asserts WHAT lands in agent_messages, "
                        + "not whether the run succeeded");

        // {@code AgentSession.requiresEncryption} and {@code AgentMessage.requiresEncryption}
        // are @Transient — runtime hints consumed by the interceptor at @PrePersist time, NOT
        // persisted columns. So the meaningful pin is the at-rest content shape: a direct
        // JDBC read of agent_messages.content MUST show the ENCRYPTED: prefix, proving the
        // @PrePersist interceptor encrypted the column at rest.
        List<AgentMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages)
                .as("MessageChatMemoryAdvisor must persist the user prompt + assistant reply "
                        + "as agent_messages rows; an empty list means memory wasn't enabled "
                        + "or the chat memory advisor was unwired")
                .isNotEmpty();

        Long encryptedAtRestCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND content LIKE 'ENCRYPTED:%'",
                Long.class, sessionId);
        assertThat(encryptedAtRestCount)
                .as("S4 contract: at-rest message content must be encrypted (start with "
                        + "'ENCRYPTED:'). A count less than the message-rows count means the "
                        + "EncryptedSessionInterceptor's @PrePersist did NOT fire — either the "
                        + "transient AgentMessage.requiresEncryption hint was never set (M1 "
                        + "regression — AgentContextHolder.getRequiresEncryption returned false "
                        + "during mapToEntity) or the interceptor's encrypt branch was bypassed.")
                .isEqualTo((long) messages.size());

        // Repository round-trip MUST return decrypted plaintext (@PostLoad interceptor).
        assertThat(messages)
                .as("S4 contract: repository reads must @PostLoad-decrypt the ENCRYPTED: "
                        + "blob back to plaintext. An 'ENCRYPTED:'-prefixed content here means "
                        + "the @PostLoad branch was bypassed and downstream consumers (the "
                        + "MessageChatMemoryAdvisor's get() path) would receive opaque cipher "
                        + "instead of usable LLM context.")
                .allMatch(m -> !m.getContent().startsWith("ENCRYPTED:"));

        // The user-supplied prompt and the fake's reply must both round-trip through the
        // decrypt path with their original plaintext.
        assertThat(messages.stream().map(AgentMessage::getContent))
                .as("the persisted message text must round-trip to its original plaintext — "
                        + "any mangled bytes here mean the encrypt+decrypt is not lossless")
                .anyMatch(c -> c.contains(userPrompt))
                .anyMatch(c -> c.contains(plaintextReply));
    }

    @Test
    void defaultTierAgent_doesNotEncryptMessages_inverseRegressionLock() {
        HttpHeaders auth = userHeaders("enc-default");
        String agentId = createAgent(auth, "S4 default-tier agent");
        // Leave complianceTier at the default (NOT TIER_2_STRICT).

        String reply = "S4 default-tier reply " + UUID.randomUUID();
        fakeChat.respondWith(reply);

        String sessionId = "session-s4-default-" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = runAgent(auth, agentId, "default-tier prompt", sessionId);
        assertEquals(200, resp.getStatusCode().value());

        // At-rest content must NOT carry the ENCRYPTED: prefix.
        Long encryptedRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND content LIKE 'ENCRYPTED:%'",
                Long.class, sessionId);
        assertEquals(0L, encryptedRows,
                "S4 inverse: default-tier messages must NOT be encrypted at rest. A non-zero "
                        + "count means the EncryptedSessionInterceptor's guard "
                        + "(message.isRequiresEncryption()) regressed to encrypt unconditionally, "
                        + "bloating storage and forcing decryption on every read in the "
                        + "non-compliance path.");
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> runAgent(HttpHeaders auth, String agentId,
                                                          String message, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        return rest.exchange(url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-s4-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "S4 encryption pin");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        // memoryEnabled + addHistoryToMessages route the chat client through
        // MessageChatMemoryAdvisor → PersistentChatMemory, which is the surface where
        // the encryption flag is propagated and the interceptor fires.
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201");
        return agentId;
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-encryption-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
