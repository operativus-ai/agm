package com.operativus.agentmanager.integration.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.operativus.agentmanager.control.dto.SkillRequest;
import com.operativus.agentmanager.control.dto.SkillResponse;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link com.operativus.agentmanager.control.controller.SkillAdminController}.
 * Covers admin CRUD happy paths, cross-tenant 404, non-admin 403, and request validation 400.
 * Attach/detach happy path is left for the e2e runtime test in PR-1e (requires fixturing an
 * Agent entity); attach/detach 404 sad paths are covered here because they don't require fixture.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.skills.enabled=true")
public class SkillAdminControllerRuntimeTest extends BaseIntegrationTest {

    // --- CRUD happy paths ---

    @Test
    void createSkill_admin_returns201AndBody() {
        HttpHeaders auth = adminHeaders("skadmin-create");
        SkillRequest req = new SkillRequest("weather", "Weather lookup skill",
                "Use the weather_lookup tool when asked about weather.",
                Set.of("weather_lookup"), true);

        ResponseEntity<SkillResponse> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), SkillResponse.class);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("weather", resp.getBody().name());
        assertNotNull(resp.getBody().id());
        assertEquals("DEFAULT_SYSTEM_ORG", resp.getBody().orgId());
    }

    @Test
    void getSkill_admin_returns200() {
        HttpHeaders auth = adminHeaders("skadmin-get");
        String id = createSkillAndReturnId(auth, "weather-get", Set.of("weather_lookup"));

        ResponseEntity<SkillResponse> resp = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.GET, new HttpEntity<>(auth), SkillResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("weather-get", resp.getBody().name());
    }

    @Test
    void listSkills_admin_returnsPageWithCreatedSkill() throws Exception {
        HttpHeaders auth = adminHeaders("skadmin-list");
        createSkillAndReturnId(auth, "weather-list-1", Set.of("weather_lookup"));
        createSkillAndReturnId(auth, "weather-list-2", Set.of("weather_lookup"));

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = json.readTree(resp.getBody());
        assertTrue(body.has("content"), "Page wire shape must include 'content' array");
        assertTrue(body.get("content").size() >= 2,
                "List must include both newly-created skills; got size=" + body.get("content").size());
    }

    @Test
    void updateSkill_admin_returns200WithUpdatedFields() {
        HttpHeaders auth = adminHeaders("skadmin-update");
        String id = createSkillAndReturnId(auth, "weather-upd", Set.of("weather_lookup"));

        SkillRequest update = new SkillRequest("weather-upd", "Updated description",
                "Updated snippet", Set.of("weather_lookup", "geo_locate"), false);

        ResponseEntity<SkillResponse> resp = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.PUT, new HttpEntity<>(update, auth), SkillResponse.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Updated description", resp.getBody().description());
        assertFalse(resp.getBody().active());
        assertEquals(2, resp.getBody().allowedTools().size());
    }

    @Test
    void deleteSkill_admin_returns204AndSubsequentGetIs404() {
        HttpHeaders auth = adminHeaders("skadmin-del");
        String id = createSkillAndReturnId(auth, "weather-del", Set.of("weather_lookup"));

        ResponseEntity<Void> del = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());

        ResponseEntity<String> get = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.NOT_FOUND, get.getStatusCode());
    }

    // --- Cross-tenant 404 (§79 RBAC pattern) ---

    @Test
    void getSkill_crossTenant_returns404NotForbidden() {
        // Create a skill in ORG-A
        HttpHeaders orgA = registerLoginWithOrg("skill-org-a", "ORG-A");
        String id = createSkillAndReturnId(orgA, "secret-a", Set.of("tool_a"));

        // Try to read it from ORG-B — must be 404 (not 403) to avoid leaking tenant membership
        HttpHeaders orgB = registerLoginWithOrg("skill-org-b", "ORG-B");
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.GET, new HttpEntity<>(orgB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "Cross-tenant read must return 404, not 403");
    }

    @Test
    void updateSkill_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("skill-upd-a", "ORG-UPD-A");
        String id = createSkillAndReturnId(orgA, "to-update", Set.of("tool_a"));

        HttpHeaders orgB = registerLoginWithOrg("skill-upd-b", "ORG-UPD-B");
        SkillRequest update = new SkillRequest("hijacked", null, null, Set.of("tool_a"), true);
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.PUT, new HttpEntity<>(update, orgB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteSkill_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("skill-del-a", "ORG-DEL-A");
        String id = createSkillAndReturnId(orgA, "to-delete", Set.of("tool_a"));

        HttpHeaders orgB = registerLoginWithOrg("skill-del-b", "ORG-DEL-B");
        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills/" + id),
                HttpMethod.DELETE, new HttpEntity<>(orgB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // --- Non-admin 403 ---

    @Test
    void createSkill_nonAdmin_returns403() {
        HttpHeaders userOnly = authenticateAs("skill-user",
                "skill-user@test.local", "pass-skill-1234", List.of("ROLE_USER"));
        SkillRequest req = new SkillRequest("rejected", null, null, Set.of("tool_a"), true);

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "Non-admin must be rejected at the @PreAuthorize gate with 403");
    }

    @Test
    void listSkills_nonAdmin_returns403() {
        HttpHeaders userOnly = authenticateAs("skill-listuser",
                "skill-listuser@test.local", "pass-skill-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.GET, new HttpEntity<>(userOnly), String.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    // --- Validation 400 ---

    @Test
    void createSkill_invalidToolName_returns400() {
        HttpHeaders auth = adminHeaders("skadmin-badtool");
        SkillRequest req = new SkillRequest("badtool", null, null, Set.of("Bad Tool Name"), true);

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void createSkill_blankName_returns400() {
        HttpHeaders auth = adminHeaders("skadmin-blank");
        SkillRequest req = new SkillRequest("", "desc", null, Set.of("tool_a"), true);

        ResponseEntity<String> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // --- Attach/detach 404 sad paths ---

    @Test
    void attachSkill_unknownAgent_returns404() {
        HttpHeaders auth = adminHeaders("skadmin-attach-noag");
        String skillId = createSkillAndReturnId(auth, "attach-no-agent", Set.of("tool_a"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/skills/" + skillId + "/agents/nonexistent-agent"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void detachSkill_unknownAgent_returns404() {
        HttpHeaders auth = adminHeaders("skadmin-detach-noag");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/skills/some-skill/agents/nonexistent-agent"),
                HttpMethod.DELETE, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // --- helpers ---

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local",
                "pass-skill-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createSkillAndReturnId(HttpHeaders auth, String name, Set<String> tools) {
        SkillRequest req = new SkillRequest(name, "desc", "snippet", tools, true);
        ResponseEntity<SkillResponse> resp = rest.exchange(url("/api/v1/skills"),
                HttpMethod.POST, new HttpEntity<>(req, auth), SkillResponse.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "Setup-helper createSkill must succeed; got " + resp.getStatusCode());
        return resp.getBody().id();
    }
}
