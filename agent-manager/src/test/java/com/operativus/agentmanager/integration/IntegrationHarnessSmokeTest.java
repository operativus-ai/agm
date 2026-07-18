package com.operativus.agentmanager.integration;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Phase 1 gate. Proves the integration harness boots end-to-end —
 *   real Spring context, Testcontainer Postgres+pgvector, real Liquibase, real Security,
 *   real HTTP — and that every Phase 1 support utility is wired correctly.
 * State: Stateless (uses {@link BaseIntegrationTest} fixtures).
 *
 * What this test guards (one assertion per support utility):
 *   - {@link BaseIntegrationTest#authenticateAs} round-trips through real /api/auth.
 *   - {@link TestData} persists fixture rows via real repositories.
 *   - {@link FakeChatModelConfig} replaces the {@link ChatClient} so production code
 *     calling the LLM boundary stays in-process.
 *   - {@link BaseIntegrationTest#truncateDatabase} clears row state between tests.
 *
 * If this class goes red, every downstream integration test in Phases 2–7 will go red
 * for the same reason — fix the harness here first.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, TestData.class})
public class IntegrationHarnessSmokeTest extends BaseIntegrationTest {

    @Autowired private TestData testData;
    @Autowired private FakeChatModel fakeChatModel;
    @Autowired private ChatClient.Builder chatClientBuilder;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;

    @Test
    void contextBootsAndJwtRoundTripsThroughRealSecurity() {
        HttpHeaders auth = authenticateAs("smoke-user", "smoke@test.local", "smoke-pass-123", List.of("ROLE_USER"));

        assertNotNull(auth.getFirst("Authorization"), "authenticateAs() must produce a Bearer header");
        assertTrue(auth.getFirst("Authorization").startsWith("Bearer "), "header must be a Bearer token");

        ResponseEntity<String> response = authorizedGet("/api/agents", auth, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "JWT must clear the security filter chain");

        Optional<User> persisted = userRepository.findByUsername("smoke-user");
        assertTrue(persisted.isPresent(), "register endpoint must persist via real UserRepository");
    }

    @Test
    void testDataBuildersPersistFixturesViaRealRepositories() {
        AgentEntity agent = testData.agent("smoke-agent");

        assertNotNull(agent.getId(), "TestData.agent() must return a saved entity with an ID");
        assertTrue(agentRepository.findById(agent.getId()).isPresent(), "fixture row must be queryable post-save");
    }

    @Test
    void fakeChatModelReplacesProductionChatClient() {
        fakeChatModel.reset();
        fakeChatModel.respondWith("scripted-response");

        String reply = chatClientBuilder.build()
                .prompt()
                .user("ping")
                .call()
                .content();

        assertEquals("scripted-response", reply, "ChatClient.Builder must yield the FakeChatModel-backed client");
        assertFalse(fakeChatModel.receivedPrompts().isEmpty(), "FakeChatModel must record prompts shipped to it");
    }

    @Test
    void truncateRunsBetweenTestsSoFixturesDoNotLeak() {
        assertTrue(userRepository.findAll().isEmpty(), "@AfterEach truncate must clear users between tests");
        assertTrue(agentRepository.findAll().isEmpty(), "@AfterEach truncate must clear agents between tests");
    }
}
