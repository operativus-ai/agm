package com.operativus.agentmanager.integration.registry;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the two "registry" surfaces —
 *   {@link com.operativus.agentmanager.control.controller.RegistryController}
 *   (reads {@code AgentRepository}/{@code TeamRepository} directly and projects to
 *   {@code RegistryItemDTO}) and the
 *   {@link com.operativus.agentmanager.control.registry.DatabaseAgentRegistry}-backed
 *   {@link com.operativus.agentmanager.control.controller.AgentsController} endpoints
 *   ({@code GET /api/agents/{id}}, {@code POST /api/agents/cache/clear}).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §15 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T022.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link BaseIntegrationTest} imports {@code NoOpCacheConfig}, which replaces the
 *     production {@code RedisCacheManager} with a {@link org.springframework.cache.support.NoOpCacheManager}.
 *     That means {@link com.operativus.agentmanager.control.registry.DatabaseAgentRegistry}'s
 *     {@code @Cacheable("agents" | "allAgents")} is a no-op inside these tests, and
 *     {@link com.operativus.agentmanager.control.service.AgentAdminService}'s corresponding
 *     {@code @CacheEvict} is also a no-op. We still pin the HTTP contracts — the invariants we
 *     CAN observe are endpoint wiring, response shapes, filters (active/team), and the
 *     null-safety of {@code findById}. The "cache must be evicted" behavioral invariant is
 *     pinned separately in {@code AgentsCrudRuntimeTest} §5.1 indirectly via a live cache.
 *   - {@link com.operativus.agentmanager.control.controller.RegistryController#listCodeAgents}
 *     applies {@code active && !isTeam} as a Java-stream filter after {@code findAll()} — so
 *     rows with {@code active=false} or {@code is_team=true} are absent from the response even
 *     though they exist in the table. Tests rely on that filter explicitly (case a).
 *   - {@link com.operativus.agentmanager.control.registry.DatabaseAgentRegistry#findById}
 *     returns {@code AgentDefinition} (nullable), NOT {@code Optional}. The matrix's "empty
 *     Optional, not NPE" shorthand maps to: lookup of an unknown id yields {@code null} and
 *     the controller converts that to 404 via {@code ResponseEntity.notFound()} — tests pin
 *     that mapping at case (c).
 *   - {@code agents.model_id} has a NOT NULL FK to {@code models.id}. Seed the model row in
 *     {@code @BeforeEach} before any fixture INSERT or the INSERT fails with DataIntegrityViolation.
 *   - The {@code agents} table defaults {@code active=true}; {@link com.operativus.agentmanager.control.service.AgentAdminService#createAgent}
 *     ignores the inbound {@code active} flag on create (documented gap in T012). We seed
 *     inactive rows directly via JDBC so the filter can be exercised.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RegistryRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> REGISTRY_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        // agents.model_id → models.id FK (fk_agents_model_id). Required for every seeded agent
        // and for the POST /api/admin/agents path in case (d).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    // §15 — Case (a): GET /api/v1/registry/agents/code returns ONLY agents where
    // active=true AND is_team=false. Seeds three agents covering each filter branch and
    // asserts the controller's Java-stream filter correctly excludes the other two.
    @Test
    void listCodeAgentsReturnsOnlyActiveNonTeamAgents() {
        HttpHeaders auth = authenticatedHeaders("registry-list-reader");

        String activeAgentId = "agent-active-" + UUID.randomUUID();
        String inactiveAgentId = "agent-inactive-" + UUID.randomUUID();
        String teamAgentId = "agent-team-" + UUID.randomUUID();

        seedAgent(activeAgentId, "Active Non-Team", true, false);
        seedAgent(inactiveAgentId, "Inactive", false, false);
        seedAgent(teamAgentId, "Team Proxy", true, true);

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/registry/agents/code"),
                HttpMethod.GET, new HttpEntity<>(auth), REGISTRY_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "authenticated GET on the registry code-agents endpoint must return 200");
        List<Map<String, Object>> items = resp.getBody();
        assertNotNull(items, "response body must be a JSON array, not null");

        assertTrue(items.stream().anyMatch(i -> activeAgentId.equals(i.get("id"))),
                "active non-team agent must appear — filter regression would drop all rows");
        assertFalse(items.stream().anyMatch(i -> inactiveAgentId.equals(i.get("id"))),
                "active=false rows must be filtered out by RegistryController.listCodeAgents — leak here means the @Filter was skipped");
        assertFalse(items.stream().anyMatch(i -> teamAgentId.equals(i.get("id"))),
                "is_team=true rows must be routed to /teams/code, not /agents/code — leak here would confuse clients that assume type='AGENT' everywhere in the payload");

        // Every returned item must carry itemType=AGENT (the controller hard-codes it).
        // Field name is 'itemType' per RegistryItemDTO record component — Jackson serializes
        // records by component name, not the trailing comment's shorthand "type".
        items.stream()
                .filter(i -> activeAgentId.equals(i.get("id")))
                .findFirst()
                .ifPresent(i -> assertEquals("AGENT", i.get("itemType"),
                        "RegistryItemDTO.itemType must be 'AGENT' for the code-agents endpoint — a regression to 'TEAM' here would break the UI's icon/type-routing logic"));
    }

    // §15 — Case (b): GET /api/v1/registry/teams/code returns seeded team rows with
    // type='TEAM'. Pins the parallel team surface so a refactor that unifies agents+teams
    // into one endpoint must be deliberate.
    @Test
    void listCodeTeamsReturnsSeededTeamsWithTypeTeam() {
        HttpHeaders auth = authenticatedHeaders("registry-teams-reader");

        String teamId = "team-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO teams (id, name, description, team_mode, created_at, updated_at)
                VALUES (?, ?, ?, 'ROUTER', now(), now())
                """, teamId, "Support Swarm", "Front-line triage team");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/registry/teams/code"),
                HttpMethod.GET, new HttpEntity<>(auth), REGISTRY_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> items = resp.getBody();
        assertNotNull(items);

        Map<String, Object> team = items.stream()
                .filter(i -> teamId.equals(i.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "seeded team must appear in /teams/code response — TeamRepository.findAll() read-through regression"));
        assertEquals("Support Swarm", team.get("name"));
        assertEquals("Front-line triage team", team.get("description"));
        assertEquals("TEAM", team.get("itemType"),
                "RegistryItemDTO.itemType must be the literal 'TEAM' for the team endpoint — clients discriminate on this field");
    }

    // §15 — Case (c): GET /api/agents/{unknown-id} returns 404, NOT 500 or NPE. This pins
    // the null-safety contract of DatabaseAgentRegistry.findById (which returns a nullable
    // AgentDefinition, not Optional) and the AgentsController mapping that converts a null
    // registry result to ResponseEntity.notFound().
    @Test
    void getAgentByUnknownIdReturns404NotNpe() {
        HttpHeaders auth = authenticatedHeaders("registry-miss-reader");
        String unknownId = "agent-does-not-exist-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + unknownId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "unknown agent id must map to 404 — a 500 here would mean DatabaseAgentRegistry.findById threw NPE or the controller tried to deref a null, classic regression after a refactor from Optional<>");
    }

    // §15 — Case (d): An agent created via POST /api/admin/agents is immediately visible
    // through GET /api/v1/registry/agents/code. Pins the end-to-end wiring between the
    // admin-create surface and the registry-read surface. Under NoOpCacheConfig the
    // underlying @CacheEvict/@Cacheable are both no-ops, so what's pinned here is the
    // controller path (AgentAdminService.createAgent → agents table → RegistryController
    // read-through) rather than a cache-invalidation race. The live cache behavior is
    // pinned separately via AgentsCrudRuntimeTest §5.1.
    @Test
    void agentCreatedViaAdminApiIsImmediatelyVisibleInRegistryListing() {
        HttpHeaders auth = authenticatedHeaders("registry-writer");

        String agentId = "agent-registry-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Registry Fresh Agent");

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, create.getStatusCode(),
                "precondition: POST /api/admin/agents must return 201 before we can probe the registry surface");

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(
                url("/api/v1/registry/agents/code"),
                HttpMethod.GET, new HttpEntity<>(auth), REGISTRY_LIST);
        assertEquals(HttpStatus.OK, list.getStatusCode());

        boolean visible = list.getBody().stream()
                .anyMatch(i -> agentId.equals(i.get("id")));
        assertTrue(visible,
                "newly-created agent must surface on the next GET /api/v1/registry/agents/code — invisibility would indicate either the admin POST didn't commit before returning 201, or the RegistryController is reading from a stale cache layer (it should read AgentRepository directly)");
    }

    // §15 — Case (e): POST /api/agents/cache/clear returns 200. Pins the endpoint wiring
    // and authorization. With NoOpCacheConfig active the underlying @CacheEvict is a
    // semantic no-op here, so the test DOES NOT assert on cache state — only on the HTTP
    // contract. A regression that breaks the endpoint (e.g. wrong mapping, auth drop) would
    // still fail this test.
    @Test
    void clearAgentCacheEndpointReturnsOkForAuthenticatedCaller() {
        HttpHeaders auth = authenticatedHeaders("registry-cache-clearer");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/cache/clear"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "POST /api/agents/cache/clear must return 200 for any authenticated caller — a 404 here means the mapping drifted; a 401/403 means auth config regressed");
        assertNotNull(resp.getBody(),
                "endpoint returns a human-readable confirmation string; empty body indicates the controller method body was accidentally stripped");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-registry-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    /**
     * JDBC-seeds an agent row with the minimum columns required for the
     * {@code /api/v1/registry/agents/code} filter (id, name, active, is_team) and the
     * {@code model_id} FK. All other columns fall to DB defaults. We bypass the service
     * layer deliberately: {@link com.operativus.agentmanager.control.service.AgentAdminService#createAgent}
     * ignores the inbound {@code active} flag and always writes {@code true} (documented gap
     * from T012), which would make case (a)'s inactive fixture impossible via the API.
     */
    private void seedAgent(String id, String name, boolean active, boolean isTeam) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, is_team, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, ?, now(), now())
                """, id, name, active, isTeam);
    }

    /**
     * Builds the minimal AgentDefinition body accepted by {@code POST /api/admin/agents}.
     * Mirrors the pattern in {@code AgentsCrudRuntimeTest#minimalAgentBody} — every
     * {@code @NotBlank} field populated, every primitive boolean defaulted so Jackson's
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} doesn't 500 before validation runs.
     */
    private Map<String, Object> minimalAgentBody(String agentId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "registry-test-agent");
        body.put("instructions", "You are a test agent for the registry suite.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        return body;
    }
}
