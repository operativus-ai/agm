package ai.operativus.agentmanager.integration.skills;

import ai.operativus.agentmanager.control.dto.SkillRequest;
import ai.operativus.agentmanager.control.dto.SkillResponse;
import ai.operativus.agentmanager.control.repository.AgentSkillRepository;
import ai.operativus.agentmanager.core.entity.AgentSkill;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: pins {@code GET /api/v1/skills/{skillId}/agents} — the skill-centric
 *   binding-list endpoint that powers the admin "manage agents" UI. Covers:
 *
 *   <ul>
 *     <li>bindings are returned priority ASC (the SkillInjector application order)</li>
 *     <li>unknown / cross-tenant skillId → 404 (existence-leak protection; both flow through
 *         {@code skillRepository.existsByIdAndOrgId})</li>
 *     <li>ROLE_USER → 403 (controller is class-level hasRole('ADMIN'))</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.skills.enabled=true")
public class SkillAgentListEndpointRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired private AgentSkillRepository agentSkillRepository;

    @Test
    void list_returnsBindingsOrderedByPriority() {
        HttpHeaders auth = adminHeaders("sk-aglist");
        String skillId = createSkillAndReturnId(auth, "list-skill", Set.of("tool_a"));
        // Saved out of priority order; endpoint must return them priority ASC.
        agentSkillRepository.save(new AgentSkill("agent-hi", skillId, 50));
        agentSkillRepository.save(new AgentSkill("agent-lo", skillId, 10));

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/skills/" + skillId + "/agents"), HttpMethod.GET,
                new HttpEntity<>(auth), LIST_TYPE);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> body = resp.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());
        assertEquals("agent-lo", body.get(0).get("agentId"));
        assertEquals(10, ((Number) body.get(0).get("priority")).intValue());
        assertEquals("agent-hi", body.get(1).get("agentId"));
        assertEquals(50, ((Number) body.get(1).get("priority")).intValue());
    }

    @Test
    void list_emptyWhenNoBindings() {
        HttpHeaders auth = adminHeaders("sk-agempty");
        String skillId = createSkillAndReturnId(auth, "empty-skill", Set.of("tool_a"));

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/skills/" + skillId + "/agents"), HttpMethod.GET,
                new HttpEntity<>(auth), LIST_TYPE);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(List.of(), resp.getBody());
    }

    @Test
    void list_unknownSkill_returns404() {
        // Cross-tenant follows the same path (existsByIdAndOrgId false → 404).
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/skills/nonexistent/agents"), HttpMethod.GET,
                new HttpEntity<>(adminHeaders("sk-ag404")), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void list_roleUser_returns403() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/skills/some-skill/agents"), HttpMethod.GET,
                new HttpEntity<>(userHeaders("sk-aguser")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // --- helpers ---

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-skill-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-skill-1234", List.of("ROLE_USER"));
    }

    private String createSkillAndReturnId(HttpHeaders auth, String name, Set<String> tools) {
        SkillRequest req = new SkillRequest(name, "desc", "snippet", tools, true);
        ResponseEntity<SkillResponse> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), SkillResponse.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return resp.getBody().id();
    }
}
