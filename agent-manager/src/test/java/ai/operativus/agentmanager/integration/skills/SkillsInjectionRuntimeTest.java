package ai.operativus.agentmanager.integration.skills;

import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the Skills runtime injection path. Exercises the full chain:
 * {@code POST /api/agents/{id}/runs} → {@code AgentClientFactory.buildChatClient} →
 * {@code SkillInjector.inject} → {@code ChatClient} → {@code FakeChatModel}, then asserts
 * on the Prompt's system message that the skill snippets and section headers landed in
 * the actual LLM input.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.skills.enabled=true")
public class SkillsInjectionRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetFake() {
        fakeChatModel.reset();
    }

    @Test
    void agentWithTwoAttachedSkills_systemPromptCarriesBothSnippetsWithHeaders() {
        seedDefaultFakeModel();
        String agentId = "skills-agent-" + UUID.randomUUID().toString().substring(0, 8);
        seedAgent(agentId, "Base agent instructions.");

        // Two skills with distinct snippets; no overlapping tools (PR-1d unit test covers dedup)
        String skill1Id = seedSkill("alpha-skill", "Use alpha rules when the user mentions alpha.");
        String skill2Id = seedSkill("beta-skill", "Always cite beta sources when the topic is beta.");
        attachSkill(agentId, skill1Id, 10);
        attachSkill(agentId, skill2Id, 20);

        fakeChatModel.respondWith("ok");

        HttpHeaders auth = registerLoginWithOrg("skill-runtime-runner", ORG);
        auth.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "message", "Hello, alpha & beta.",
                "sessionId", "session-" + UUID.randomUUID());

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "sync run must succeed end-to-end for the system-prompt assertion to be meaningful");

        // Inspect the prompt(s) the production code shipped to the fake LLM
        List<Prompt> prompts = fakeChatModel.receivedPrompts();
        assertFalse(prompts.isEmpty(), "FakeChatModel must have received at least one prompt");

        String systemMessage = extractSystemMessage(prompts.get(0));
        assertAll("Skill-injection wiring lands snippets in the LLM input",
                () -> assertTrue(systemMessage.contains("## Skill: alpha-skill"),
                        "system prompt missing alpha section header: " + systemMessage),
                () -> assertTrue(systemMessage.contains("## Skill: beta-skill"),
                        "system prompt missing beta section header: " + systemMessage),
                () -> assertTrue(systemMessage.contains("Use alpha rules"),
                        "alpha snippet body did not propagate"),
                () -> assertTrue(systemMessage.contains("Always cite beta sources"),
                        "beta snippet body did not propagate"),
                () -> assertTrue(systemMessage.contains("Base agent instructions."),
                        "agent's original instructions must still be present"));
    }

    @Test
    void agentWithFlagOnButNoSkillsAttached_systemPromptHasNoSkillHeader() {
        seedDefaultFakeModel();
        String agentId = "noskill-agent-" + UUID.randomUUID().toString().substring(0, 8);
        seedAgent(agentId, "Plain instructions.");
        // Intentionally NO skills attached — verifies that a flag-on context with zero
        // attached skills does NOT pollute the prompt with skill markers (regression guard
        // for the "no-skill" path).

        fakeChatModel.respondWith("ok");

        HttpHeaders auth = registerLoginWithOrg("skill-runtime-zero", ORG);
        auth.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "message", "Hi.",
                "sessionId", "session-" + UUID.randomUUID());

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        String systemMessage = extractSystemMessage(fakeChatModel.receivedPrompts().get(0));
        assertFalse(systemMessage.contains("## Skill:"),
                "Flag-on + zero attached skills must produce NO skill section header");
        assertTrue(systemMessage.contains("Plain instructions."),
                "agent's original instructions must remain intact");
    }

    // ---------------------------------------------------------------------
    // Fixture helpers — JDBC inserts to bypass orgId-context complexities of
    // calling SkillService from a non-request thread. The unit tests in PR-1b
    // already cover the service-level logic.
    // ---------------------------------------------------------------------

    private void seedAgent(String agentId, String instructions) {
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, security_tier, compliance_tier)
                VALUES (?, ?, ?, 'skills-runtime fixture', ?, 'gpt-4o-mini', true, false, false, 1, 'TIER_1_STANDARD')
                ON CONFLICT (id) DO NOTHING
                """, agentId, ORG, "skills runtime agent", instructions);
    }

    private void seedDefaultFakeModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private String seedSkill(String name, String snippet) {
        String id = "skill-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO skills (id, org_id, name, description, system_prompt_snippet, active,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 'runtime fixture', ?, true, now(), now())
                """, id, ORG, name, snippet);
        return id;
    }

    private void attachSkill(String agentId, String skillId, int priority) {
        jdbc.update("""
                INSERT INTO agent_skills (agent_id, skill_id, priority, created_at)
                VALUES (?, ?, ?, now())
                """, agentId, skillId, priority);
    }

    private static String extractSystemMessage(Prompt prompt) {
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                return m.getText();
            }
        }
        return "";
    }
}
