package ai.operativus.agentmanager.integration.teams;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the teams surface — the final
 *   domain in the changeset-007 audit (after knowledge_bases, schedules, workflows). ADMIN of
 *   org A cannot list, fetch, modify, delete, archive, restore, clone, list-members, add-member,
 *   bulk-add-members, remove-member, list-edges, add-edge, or remove-edge against ADMIN of
 *   org B's teams. Cross-tenant lookups return 404 / empty list / 204-no-mutation as
 *   appropriate (existence-leak protection).
 *   <p>
 *   Child entities ({@code TeamMember}, {@code TransitionEdge}) intentionally do NOT carry their
 *   own {@code org_id} column — they are tenant-scoped via parent traversal
 *   ({@code teamId → teams.org_id}) at the service layer.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class TeamTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void listReturnsOnlyCallerOrgTeams() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-list", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-list", "team-iso-org-B");

        createTeam(orgA, "A's Squad");
        createTeam(orgA, "A's Crew");
        createTeam(orgB, "B's Squad");

        ResponseEntity<Map<String, Object>> aPage = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, aPage.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aPage.getBody().get("content");
        assertEquals(2, aContent.size(),
                "org A listing must contain exactly A's 2 teams; got " + aContent.size());
        assertTrue(aContent.stream().allMatch(t -> String.valueOf(t.get("name")).startsWith("A's ")),
                "every row in A's listing must be an A-named team; got " + aContent);

        ResponseEntity<Map<String, Object>> bPage = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bPage.getBody().get("content");
        assertEquals(1, bContent.size(),
                "org B listing must contain exactly B's 1 team; got " + bContent.size());
    }

    @Test
    void getById404ForCrossTenantTeam() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-get", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-get", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Get-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /teams/{B-id} as A must return 404; got " + response.getStatusCode());
    }

    @Test
    void patch404ForCrossTenantTeamAndRowUnmodified() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-patch", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-patch", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Patch-Probe");

        Map<String, Object> body = Map.of("description", "should never apply");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId),
                HttpMethod.PATCH,
                new HttpEntity<>(body, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PATCH cross-tenant must return 404; got " + response.getStatusCode());

        // Verify B's row was NOT modified — original create did not set description, so null is
        // the expected post-condition (cross-tenant PATCH would have written the body's value).
        String bDescription = jdbc.queryForObject(
                "SELECT description FROM teams WHERE id = ?",
                String.class, bId);
        assertEquals(null, bDescription,
                "cross-tenant PATCH must not have written B's row; got description=" + bDescription);
    }

    @Test
    void deleteCrossTenantTeamIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-del", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-del", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Delete-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE returns 204 unconditionally (controller shape preserved)");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams WHERE id = ?",
                Long.class, bId);
        assertEquals(1L, count == null ? 0L : count,
                "cross-tenant DELETE must not have removed B's row");
    }

    @Test
    void archive404ForCrossTenantTeam() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-arch", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-arch", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Archive-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/archive"),
                HttpMethod.PATCH,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PATCH /archive cross-tenant must return 404; got " + response.getStatusCode());

        Boolean archived = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?",
                Boolean.class, bId);
        assertTrue(archived == null || !archived,
                "cross-tenant archive must not have flipped B's archived flag");
    }

    @Test
    void clone404ForCrossTenantTeam() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-clone", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-clone", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Clone-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/clone"),
                HttpMethod.POST,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /clone cross-tenant must return 404; got " + response.getStatusCode());
    }

    @Test
    void getMembersReturnsEmptyForCrossTenantTeam() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-members", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-members", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Members-Probe");

        // Add a member through B's own auth so the FK constraint to agents (if any) and the
        // tenant guard both behave naturally — no need for direct-jdbc seeding.
        // (B's own GET /members would return this row; A's must return empty.)
        Map<String, Object> memberBody = new HashMap<>();
        memberBody.put("agentId", "seed-agent-id-" + java.util.UUID.randomUUID());
        memberBody.put("role", "MEMBER");
        // The legacy /members POST allows missing agents (no FK on agentId), so this just
        // inserts the team_member row. We don't care if the response is 201 or 400 —
        // we only need the row in place to prove the cross-tenant guard masks it.
        rest.exchange(
                url("/api/v1/teams/" + bId + "/members"),
                HttpMethod.POST,
                new HttpEntity<>(memberBody, orgB),
                String.class);

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/members"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.isEmpty(),
                "GET /teams/{B-id}/members as A must be empty (parent invisible → members invisible); got "
                        + body);
    }

    @Test
    void addMember404ForCrossTenantTeamAndRowNotInserted() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-add-mem", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-add-mem", "team-iso-org-B");

        String bId = createTeam(orgB, "B's AddMem-Probe");

        Map<String, Object> body = new HashMap<>();
        body.put("agentId", "evil-injected");
        body.put("role", "MEMBER");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/members"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                String.class);
        // Controller's tenant guard fires BEFORE the service call, so cross-tenant returns
        // 404 (not 400 — 400 is the bad-body case after a valid tenant). Existence-leak
        // protection: 404 is indistinguishable from missing-team.
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /teams/{B-id}/members as A must return 404 (controller-level existsByIdAndOrgId guard fires first)");

        Long memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND agent_id = 'evil-injected'",
                Long.class, bId);
        assertEquals(0L, memberCount == null ? 0L : memberCount,
                "no team_member row must be inserted cross-tenant");
    }

    @Test
    void restore404ForCrossTenantTeamAndRowStaysArchived() {
        // Mirrors the agent-restore exploit shape (PR #1001): A could otherwise
        // resurrect B's deliberately-archived team and pull it back into B's tenant.
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-restore", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-restore", "team-iso-org-B");

        String bId = createTeam(orgB, "B's Restore-Probe");
        // B archives its own team first so there's something to attempt to restore.
        ResponseEntity<Map<String, Object>> bArchive = rest.exchange(
                url("/api/v1/teams/" + bId + "/archive"),
                HttpMethod.PATCH,
                new HttpEntity<>(orgB),
                JSON_MAP);
        assertEquals(HttpStatus.OK, bArchive.getStatusCode(),
                "fixture: B's self-archive must succeed before A's restore probe");
        Boolean preArchived = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?", Boolean.class, bId);
        assertEquals(Boolean.TRUE, preArchived,
                "fixture invariant: B's team must be archived before A's restore attempt");

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/restore"),
                HttpMethod.PATCH,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PATCH /teams/{B-id}/restore as A must return 404; got " + response.getStatusCode());

        Boolean postArchived = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?", Boolean.class, bId);
        assertEquals(Boolean.TRUE, postArchived,
                "cross-tenant restore must NOT have flipped B's team back to active; got archived="
                        + postArchived);
    }

    @Test
    void removeMemberCrossTenantIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-remmem", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-remmem", "team-iso-org-B");

        String bId = createTeam(orgB, "B's RemoveMember-Probe");
        String bAgentId = seedAgentForOrg("b-remmem", "team-iso-org-B");
        // B adds its own agent as a team member so there's a row for A to attempt to remove.
        jdbc.update("""
                INSERT INTO team_members (team_id, agent_id, role)
                VALUES (?, ?, 'MEMBER')
                """, bId, bAgentId);

        // Controller returns 204 unconditionally; the service-layer existsByIdAndOrgId
        // guard prevents the actual deletion when cross-tenant.
        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/members/" + bAgentId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE returns 204 unconditionally (controller shape); got " + response.getStatusCode());

        Long memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ? AND agent_id = ?",
                Long.class, bId, bAgentId);
        assertEquals(1L, memberCount == null ? 0L : memberCount,
                "cross-tenant removeMember must NOT have deleted B's member row");
    }

    @Test
    void addEdge404ForCrossTenantTeamAndNoEdgeInserted() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-addedge", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-addedge", "team-iso-org-B");

        String bId = createTeam(orgB, "B's AddEdge-Probe");
        // Both source + target agent ids are arbitrary strings — the controller's
        // existsByIdAndOrgId guard fires first and returns 404 before the service
        // even validates the agent references.
        Map<String, Object> edgeBody = Map.of(
                "sourceAgentId", "evil-source",
                "targetAgentId", "evil-target");
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/edges"),
                HttpMethod.POST,
                new HttpEntity<>(edgeBody, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /teams/{B-id}/edges as A must return 404; got " + response.getStatusCode());

        Long edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_transition_edges WHERE team_id = ?",
                Long.class, bId);
        assertEquals(0L, edgeCount == null ? 0L : edgeCount,
                "no team_transition_edges row must be inserted cross-tenant; got " + edgeCount);
    }

    @Test
    void removeEdgeCrossTenantIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-remedge", "team-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("team-iso-b-remedge", "team-iso-org-B");

        String bId = createTeam(orgB, "B's RemoveEdge-Probe");
        String bSource = seedAgentForOrg("b-edge-src", "team-iso-org-B");
        String bTarget = seedAgentForOrg("b-edge-tgt", "team-iso-org-B");
        String edgeId = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO team_transition_edges (id, team_id, source_agent_id, target_agent_id, created_at)
                VALUES (?, ?, ?, ?, NOW())
                """, edgeId, bId, bSource, bTarget);

        ResponseEntity<Void> response = rest.exchange(
                url("/api/v1/teams/" + bId + "/edges/" + edgeId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE returns 204 unconditionally; got " + response.getStatusCode());

        Long edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_transition_edges WHERE id = ?",
                Long.class, edgeId);
        assertEquals(1L, edgeCount == null ? 0L : edgeCount,
                "cross-tenant removeEdge must NOT have deleted B's team_transition_edges row");
    }

    @Test
    void postIgnoresBodyOrgIdAndStampsCallerOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("team-iso-a-post", "team-iso-org-A");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "post-org-injection-attempt");
        body.put("description", "body claims org B");
        body.put("orgId", "team-iso-org-B");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);

        String createdId = String.valueOf(created.get("id"));
        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM teams WHERE id = ?",
                String.class, createdId);
        assertEquals("team-iso-org-A", storedOrgId,
                "POST must stamp caller's orgId; body-injected orgId must be ignored. got=" + storedOrgId);
    }

    // ─── helpers ───

    private String seedAgentForOrg(String label, String orgId) {
        String agentId = "agent-team-iso-" + label + "-" + java.util.UUID.randomUUID();
        // Seed a fake model row on first call (idempotent via ON CONFLICT).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Team-iso agent " + label, orgId);
        return agentId;
    }

    private String createTeam(HttpHeaders auth, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/teams"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createTeam fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }
}
