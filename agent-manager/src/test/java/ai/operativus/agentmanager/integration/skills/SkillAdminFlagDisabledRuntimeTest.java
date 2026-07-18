package ai.operativus.agentmanager.integration.skills;

import ai.operativus.agentmanager.control.dto.SkillRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that with {@code agm.skills.enabled=false} (the production default) the
 * {@code SkillAdminController} bean is NOT registered, so all skill endpoints return 404
 * regardless of caller role. This is the §103 contract for flag-disabled features —
 * absence-as-404 rather than 503/feature-flag-leak.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.skills.enabled=false")
public class SkillAdminFlagDisabledRuntimeTest extends BaseIntegrationTest {

    @Test
    void listSkills_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("flag-list");
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "Flag-disabled controller must not register; 404 expected");
    }

    @Test
    void createSkill_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("flag-create");
        SkillRequest req = new SkillRequest("anything", null, null, Set.of("tool_a"), true);

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void getSkill_flagDisabled_returns404() {
        HttpHeaders auth = adminHeaders("flag-get");
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills/any-id"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-skill-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
