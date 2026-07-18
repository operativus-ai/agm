package ai.operativus.agentmanager.integration.teams;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime coverage for the Teams lifecycle surface — the
 *   non-orchestrator concerns of {@link ai.operativus.agentmanager.control.controller.TeamsController}
 *   and {@link ai.operativus.agentmanager.control.service.TeamService}. Pins
 *   the as-shipped CRUD round-trip, FK CASCADE behaviour for member and edge
 *   tables, the archive / restore soft-delete flow, the deep-clone shape, and the
 *   add-disabled-agent gap surfaced via the health endpoint.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T038
 * (Teams lifecycle, ≤5 cases). Orchestrator strategies are covered by T026–T033;
 * transition / tier validators by T034. T038 is reserved for everything else.
 *
 * Notes on as-shipped behaviour pinned here:
 *   - {@link ai.operativus.agentmanager.control.controller.TeamsController} ships
 *     with NO {@code @PreAuthorize} annotations on any endpoint. Any authenticated
 *     principal can mutate any team. Same RBAC-gap flavor pinned by T021 Memory,
 *     T024 Settings, T035 Workflows, T036 Approvals, T037 Schedules. Case 1
 *     surfaces this as a verification line, not a hard pin (the JWT is
 *     ROLE_USER and the create succeeds).
 *   - {@code teams} entity has no {@code org_id} column — multi-tenancy is not
 *     enforced at the team level. Documented for the cross-cutting Phase 5 work.
 *   - {@code team_members.agent_id} → {@code agents.id} has NO {@code ON DELETE
 *     CASCADE}, while {@code team_transition_edges} cascades on both source and
 *     target agent ids. Asymmetry pinned via case 2 (delete via team, not via
 *     agent — agent-side cascade behaviour is a separate concern).
 *   - {@code TeamService.addTeamMember} accepts an inactive agent without
 *     complaint. The discrepancy only surfaces via {@code GET /{id}/health} where
 *     {@code activeMemberCount < memberCount}. Case 3 pins this.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class TeamsLifecycleRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> PAGE_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModelBeforeTest() {
        // agents.model_id FK — same one-row seed pattern as T034/T035/T036/T037.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // T038-1 — POST /api/v1/teams round-trips through GET /api/v1/teams/{id} and
    // persists with the supplied fields. Uses a ROLE_USER principal to verify the
    // controller has no method-level RBAC — which is the as-shipped state.
    @Test
    void createTeam_persistsAndRoundTripsViaGet_anyAuthenticatedRolePasses() {
        HttpHeaders headers = authHeaders("t038-1");

        Map<String, Object> body = Map.of(
                "name", "lifecycle-team-1",
                "description", "T038 case 1 round-trip team",
                "teamMode", "SEQUENTIAL",
                "instructions", "Coordinate the daily summary",
                "memoryEnabled", false
        );

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST, new HttpEntity<>(body, headers), JSON_MAP);

        String id = (String) created.getBody().get("id");
        ResponseEntity<Map<String, Object>> fetched = rest.exchange(
                url("/api/v1/teams/" + id), HttpMethod.GET, new HttpEntity<>(headers), JSON_MAP);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, description, team_mode, archived FROM teams WHERE id = ?", id);

        assertAll("create + get + persistence",
                () -> assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                        "POST /teams must return 201 — controller writes HttpStatus.CREATED explicitly"),
                () -> assertNotNull(id, "create response must carry a generated id"),
                () -> assertEquals(HttpStatus.OK, fetched.getStatusCode(),
                        "GET by id must succeed immediately after create"),
                () -> assertEquals("lifecycle-team-1", fetched.getBody().get("name"),
                        "round-tripped name must match"),
                () -> assertEquals("SEQUENTIAL", fetched.getBody().get("teamMode"),
                        "team mode must round-trip verbatim"),
                () -> assertEquals("lifecycle-team-1", row.get("name"),
                        "row must persist with the submitted name"),
                () -> assertEquals(Boolean.FALSE, row.get("archived"),
                        "newly-created team must default to archived=false"),
                () -> assertEquals(Boolean.FALSE, fetched.getBody().get("archived"),
                        "DTO must surface the archived flag for clients"));
    }

    // T038-2 — Asserts the FK CASCADE behaviour declared in 001-schema.sql §288–345:
    // deleting a team must cascade to {@code team_members} and {@code team_transition_edges}.
    // Members add no other rows; edges seed via the controller so we exercise both
    // the duplicate-edge guard and the cascade.
    @Test
    void deleteTeam_cascadesToMembersAndTransitionEdges() {
        HttpHeaders headers = authHeaders("t038-2");
        String agentA = seedAgentRow("t038-2-a", true);
        String agentB = seedAgentRow("t038-2-b", true);
        String teamId = createTeamViaApi(headers, "lifecycle-team-2", "SEQUENTIAL");

        addMemberViaApi(headers, teamId, agentA, "MEMBER");
        addMemberViaApi(headers, teamId, agentB, "MEMBER");
        addEdgeViaApi(headers, teamId, agentA, agentB);

        Integer membersBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ?", Integer.class, teamId);
        Integer edgesBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_transition_edges WHERE team_id = ?",
                Integer.class, teamId);

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/v1/teams/" + teamId), HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);

        Integer teamRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM teams WHERE id = ?", Integer.class, teamId);
        Integer membersAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ?", Integer.class, teamId);
        Integer edgesAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_transition_edges WHERE team_id = ?",
                Integer.class, teamId);
        Integer agentsStillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE id IN (?, ?)",
                Integer.class, agentA, agentB);

        assertAll("delete cascades to member + edge tables",
                () -> assertEquals(2, membersBefore, "preconditions: 2 member rows seeded"),
                () -> assertEquals(1, edgesBefore, "preconditions: 1 edge seeded"),
                () -> assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                        "DELETE /teams/{id} must return 204"),
                () -> assertEquals(0, teamRows, "teams row must be removed"),
                () -> assertEquals(0, membersAfter,
                        "team_members FK must cascade — children removed by DB"),
                () -> assertEquals(0, edgesAfter,
                        "team_transition_edges FK must cascade — DAG state must not outlive parent"),
                () -> assertEquals(2, agentsStillThere,
                        "team delete must NOT cascade to agents — they are independent rows"));
    }

    // T038-3 — TeamService.addTeamMember does no agent.active check before insert.
    // The activity surfaces only via the health endpoint where activeMemberCount
    // is computed from agent.active. Pin both: the POST succeeds with an inactive
    // agent, and GET /health reports the discrepancy.
    @Test
    void addMember_acceptsInactiveAgent_andHealthSurfacesActiveCountGap() {
        HttpHeaders headers = authHeaders("t038-3");
        String activeAgent = seedAgentRow("t038-3-active", true);
        String inactiveAgent = seedAgentRow("t038-3-inactive", false);
        String teamId = createTeamViaApi(headers, "lifecycle-team-3", "COORDINATOR");

        ResponseEntity<Map<String, Object>> activeAdd =
                addMemberViaApi(headers, teamId, activeAgent, "MEMBER");
        ResponseEntity<Map<String, Object>> inactiveAdd =
                addMemberViaApi(headers, teamId, inactiveAgent, "MEMBER");

        ResponseEntity<Map<String, Object>> health = rest.exchange(
                url("/api/v1/teams/" + teamId + "/health"), HttpMethod.GET,
                new HttpEntity<>(headers), JSON_MAP);

        Integer membershipRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ?", Integer.class, teamId);

        Map<String, Object> healthBody = health.getBody();
        Number memberCount = (Number) healthBody.get("memberCount");
        Number activeCount = (Number) healthBody.get("activeMemberCount");

        assertAll("add accepts inactive + health reports gap",
                () -> assertEquals(HttpStatus.CREATED, activeAdd.getStatusCode(),
                        "POST /members must return 201 for an active agent"),
                () -> assertEquals(HttpStatus.CREATED, inactiveAdd.getStatusCode(),
                        "as-shipped: POST /members succeeds even when agent.active=false " +
                                "— TeamService.addTeamMember performs no active-flag validation"),
                () -> assertEquals(2, membershipRows,
                        "both memberships must persist regardless of agent activity"),
                () -> assertEquals(HttpStatus.OK, health.getStatusCode(),
                        "GET /health must return 200"),
                () -> assertEquals(2, memberCount.intValue(),
                        "memberCount counts every team_members row"),
                () -> assertEquals(1, activeCount.intValue(),
                        "activeMemberCount filters by agents.active — surfaces the gap " +
                                "between membership and runnable members"));
    }

    // T038-4 — Archive flips the soft-delete flag on the row, the default list
    // call (showArchived omitted → false) excludes the team, opting in surfaces
    // it again, and restore flips the flag back to false.
    @Test
    void archiveAndRestore_flipsArchivedFlag_andDefaultListExcludesArchived() {
        HttpHeaders headers = authHeaders("t038-4");
        String teamId = createTeamViaApi(headers, "lifecycle-team-4", "PLANNER");

        ResponseEntity<Map<String, Object>> archived = rest.exchange(
                url("/api/v1/teams/" + teamId + "/archive"), HttpMethod.PATCH,
                new HttpEntity<>(headers), JSON_MAP);
        Boolean rowAfterArchive = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?", Boolean.class, teamId);

        ResponseEntity<Map<String, Object>> defaultList = rest.exchange(
                url("/api/v1/teams"), HttpMethod.GET, new HttpEntity<>(headers), PAGE_MAP);
        ResponseEntity<Map<String, Object>> withArchivedList = rest.exchange(
                url("/api/v1/teams?showArchived=true"), HttpMethod.GET,
                new HttpEntity<>(headers), PAGE_MAP);

        ResponseEntity<Map<String, Object>> restored = rest.exchange(
                url("/api/v1/teams/" + teamId + "/restore"), HttpMethod.PATCH,
                new HttpEntity<>(headers), JSON_MAP);
        Boolean rowAfterRestore = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?", Boolean.class, teamId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> defaultPage =
                (List<Map<String, Object>>) defaultList.getBody().get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> withArchivedPage =
                (List<Map<String, Object>>) withArchivedList.getBody().get("content");

        boolean defaultContainsArchived = defaultPage.stream()
                .anyMatch(t -> teamId.equals(t.get("id")));
        boolean optedInContainsArchived = withArchivedPage.stream()
                .anyMatch(t -> teamId.equals(t.get("id")));

        assertAll("archive + list filter + restore",
                () -> assertEquals(HttpStatus.OK, archived.getStatusCode(),
                        "PATCH /archive must return 200"),
                () -> assertEquals(Boolean.TRUE, archived.getBody().get("archived"),
                        "archive response must carry archived=true"),
                () -> assertEquals(Boolean.TRUE, rowAfterArchive,
                        "row must reflect archived=true on disk"),
                () -> assertTrue(!defaultContainsArchived,
                        "default list (showArchived=false) must exclude archived teams"),
                () -> assertTrue(optedInContainsArchived,
                        "explicit showArchived=true must surface the archived team"),
                () -> assertEquals(HttpStatus.OK, restored.getStatusCode(),
                        "PATCH /restore must return 200"),
                () -> assertEquals(Boolean.FALSE, restored.getBody().get("archived"),
                        "restore response must carry archived=false"),
                () -> assertEquals(Boolean.FALSE, rowAfterRestore,
                        "restore must persist the flag flip"));
    }

    // T038-5 — POST /clone produces a brand-new team id, deep-copies members and
    // transition edges, and resets archived=false on the clone (independent of the
    // source's archived state).
    @Test
    void cloneTeam_deepCopiesMembersAndEdges_andResetsArchivedFalse() {
        HttpHeaders headers = authHeaders("t038-5");
        String agentA = seedAgentRow("t038-5-a", true);
        String agentB = seedAgentRow("t038-5-b", true);
        String sourceId = createTeamViaApi(headers, "lifecycle-team-5-source", "SEQUENTIAL");

        addMemberViaApi(headers, sourceId, agentA, "LEADER");
        addMemberViaApi(headers, sourceId, agentB, "MEMBER");
        addEdgeViaApi(headers, sourceId, agentA, agentB);

        // Archive the source first to prove the clone is born un-archived even when
        // copying from an archived parent.
        rest.exchange(url("/api/v1/teams/" + sourceId + "/archive"), HttpMethod.PATCH,
                new HttpEntity<>(headers), JSON_MAP);

        ResponseEntity<Map<String, Object>> cloneResponse = rest.exchange(
                url("/api/v1/teams/" + sourceId + "/clone"), HttpMethod.POST,
                new HttpEntity<>(headers), JSON_MAP);

        String cloneId = (String) cloneResponse.getBody().get("id");

        Integer cloneMemberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_members WHERE team_id = ?", Integer.class, cloneId);
        Integer cloneEdgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_transition_edges WHERE team_id = ?",
                Integer.class, cloneId);
        Boolean cloneArchived = jdbc.queryForObject(
                "SELECT archived FROM teams WHERE id = ?", Boolean.class, cloneId);

        ResponseEntity<List<Map<String, Object>>> cloneMembers = rest.exchange(
                url("/api/v1/teams/" + cloneId + "/members"), HttpMethod.GET,
                new HttpEntity<>(headers), JSON_LIST);

        assertAll("clone deep-copies + un-archives",
                () -> assertEquals(HttpStatus.CREATED, cloneResponse.getStatusCode(),
                        "POST /clone must return 201"),
                () -> assertNotNull(cloneId, "clone must have a fresh id"),
                () -> assertNotEquals(sourceId, cloneId,
                        "clone id must differ from source id"),
                () -> assertEquals(2, cloneMemberCount,
                        "clone must duplicate every team_members row"),
                () -> assertEquals(1, cloneEdgeCount,
                        "clone must duplicate every team_transition_edges row"),
                () -> assertEquals(Boolean.FALSE, cloneArchived,
                        "clone must reset archived=false even when source is archived"),
                () -> assertEquals(2, cloneMembers.getBody().size(),
                        "GET /members on the clone must surface both copies"));
    }

    // ─── helpers ───

    private HttpHeaders authHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pw-t038-1234", List.of("ROLE_USER"));
    }

    private String seedAgentRow(String label, boolean active) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, now(), now())
                """, agentId, "T038 agent " + label, active);
        return agentId;
    }

    private String createTeamViaApi(HttpHeaders headers, String name, String mode) {
        Map<String, Object> body = Map.of("name", name, "teamMode", mode);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST,
                new HttpEntity<>(body, headers), JSON_MAP);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map<String, Object>> addMemberViaApi(
            HttpHeaders headers, String teamId, String agentId, String role) {
        Map<String, Object> body = Map.of("agentId", agentId, "role", role);
        return rest.exchange(
                url("/api/v1/teams/" + teamId + "/members"), HttpMethod.POST,
                new HttpEntity<>(body, headers), JSON_MAP);
    }

    private void addEdgeViaApi(HttpHeaders headers, String teamId,
                               String sourceAgentId, String targetAgentId) {
        Map<String, Object> body = Map.of(
                "sourceAgentId", sourceAgentId, "targetAgentId", targetAgentId);
        rest.exchange(
                url("/api/v1/teams/" + teamId + "/edges"), HttpMethod.POST,
                new HttpEntity<>(body, headers), JSON_MAP);
    }
}
