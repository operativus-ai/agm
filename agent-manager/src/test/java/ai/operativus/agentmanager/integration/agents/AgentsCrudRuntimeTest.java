package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Agent CRUD surface —
 *   {@code POST/PUT/DELETE /api/admin/agents} (handled by
 *   {@link ai.operativus.agentmanager.control.controller.AgentAdminController}
 *   → {@link ai.operativus.agentmanager.control.service.AgentAdminService}) and the
 *   registry-backed {@code GET /api/agents} listing (handled by
 *   {@link ai.operativus.agentmanager.control.controller.AgentsController}
 *   → {@link ai.operativus.agentmanager.control.registry.DatabaseAgentRegistry}).
 *   Verifies persistence, validation contracts, optimistic-locking version bumps,
 *   and the live cache-eviction wiring between {@code AgentAdminService} writes and
 *   {@code DatabaseAgentRegistry}'s {@code @Cacheable("allAgents")}.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §5.1, §5.2, §5.6, §5.7, §5.8.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link ai.operativus.agentmanager.core.model.definitions.AgentDefinition} declares
 *     {@code @NotBlank} on {@code id}, {@code name}, {@code description}, {@code instructions},
 *     and {@code modelId}. Missing required fields are surfaced through
 *     {@code GlobalExceptionHandler.handleValidationExceptions} as RFC-7807 400s.
 *   - {@code AgentAdminService.createAgent} is annotated
 *     {@code @CacheEvict(value = {"agents", "allAgents"}, allEntries = true)} so the registry
 *     cache primed at {@code ApplicationReadyEvent} stays consistent. Test §5.1 below pins
 *     that wiring by reading through {@code GET /api/agents}.
 *   - {@code AgentEntity.version} carries {@code @Version} → optimistic locking. After a single
 *     update, the column moves from {@code 0} → {@code 1} (verified via {@code JdbcTemplate}).
 *   - <b>Soft-delete authorization:</b> {@code AgentAdminService.deleteAgent} is annotated
 *     {@code @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'delete')")}. The app
 *     registers {@code AgentPermissionEvaluator}, which enforces org-scoped rules:
 *     caller must hold {@code ROLE_ADMIN} AND the agent's {@code orgId} must equal the
 *     caller's {@code orgId} (both null matches, representing the global unscoped tenant).
 *     §5.8 pins both paths: positive (same-org admin → 204 + {@code active=false}) and
 *     negative (admin caller whose {@code orgId} does not match the agent's → 403, row
 *     untouched).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentsCrudRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> AGENT_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> AGENT_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Truncate before-test in addition to after-test: the FIRST test of the class boots into
     * a DB still populated by the Liquibase seed ({@code 002-seed-data.sql} inserts 4 agents),
     * which would otherwise leak into the registry-listing assertions below. Also re-seeds a
     * minimal {@code models} row so the {@code agents.model_id → models.id} FK is satisfied
     * for any test that POSTs an agent.
     */
    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
    }

    /**
     * Inserts a minimal {@code models} row so creates against {@code /api/admin/agents} clear
     * the {@code fk_agents_model_id} FK. Required NOT NULL columns are taken from
     * {@link ai.operativus.agentmanager.core.entity.ModelEntity}: id, name, provider,
     * supports_tools, supports_vision, supports_system_instructions, model_type.
     */
    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §5.1 — POST /api/admin/agents creates the row, returns 201, and the @CacheEvict on
    // createAgent invalidates DatabaseAgentRegistry's "allAgents" cache so GET /api/agents
    // (the registry-backed listing) immediately reflects the new agent.
    @Test
    void createAgentWithRequiredFieldsReturns201AndAppearsInRegistryListing() {
        HttpHeaders auth = authenticatedHeaders("agent-creator", "creator@test.local");

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Procurement Helper");

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);

        assertEquals(HttpStatus.CREATED, create.getStatusCode(), "POST must return 201 Created");
        Map<String, Object> created = create.getBody();
        assertNotNull(created, "201 response must carry the created definition body");
        assertEquals(agentId, created.get("agentId"), "agentId field is JSON-serialized via @JsonProperty(\"agentId\")");
        assertEquals("Procurement Helper", created.get("name"));

        Long persisted = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(1L, persisted, "row must exist in the agents table");

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(auth), AGENT_LIST_TYPE);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        boolean visibleInRegistry = list.getBody().stream()
                .anyMatch(a -> agentId.equals(a.get("agentId")));
        assertTrue(visibleInRegistry,
                "DatabaseAgentRegistry's @Cacheable(\"allAgents\") cache must be evicted by AgentAdminService.createAgent so the new row is observable through GET /api/agents");
    }

    // §5.2 — Missing modelId trips @NotBlank("Model ID is required") on AgentDefinition.
    // GlobalExceptionHandler converts MethodArgumentNotValidException → 400 RFC-7807, and
    // the row is NOT created.
    @Test
    void createAgentMissingModelIdReturns400AndDoesNotPersist() {
        HttpHeaders auth = authenticatedHeaders("agent-validator", "validator@test.local");

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = minimalAgentBody(agentId, "Missing Model");
        body.remove("model"); // AgentDefinition exposes modelId as @JsonProperty("model")

        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), AGENT_TYPE);

        assertEquals(HttpStatus.BAD_REQUEST, create.getStatusCode(),
                "missing required field must surface as 400, not 500");

        Long persisted = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(0L, persisted, "validation failure must short-circuit before the repository save");
    }

    // §5.7 — PUT /api/admin/agents/{id} writes the dto fields back, optimistic-locking @Version
    // column moves from 0 → 1, and the new name is observable via JDBC after the write.
    @Test
    void updateAgentPersistsChangesAndIncrementsOptimisticLockVersion() {
        HttpHeaders auth = authenticatedHeaders("agent-updater", "updater@test.local");

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> createBody = minimalAgentBody(agentId, "Original Name");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(createBody, auth), AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        Integer versionBefore = jdbc.queryForObject(
                "SELECT version FROM agents WHERE id = ?", Integer.class, agentId);
        assertEquals(0, versionBefore, "fresh row starts at @Version = 0");

        Map<String, Object> updateBody = minimalAgentBody(agentId, "Renamed Agent");
        updateBody.put("description", "now with revised description");
        ResponseEntity<Map<String, Object>> updated = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.PUT, new HttpEntity<>(updateBody, auth), AGENT_TYPE);

        assertEquals(HttpStatus.OK, updated.getStatusCode(), "PUT must return 200 with the new state");
        assertEquals("Renamed Agent", updated.getBody().get("name"));
        assertEquals("now with revised description", updated.getBody().get("description"));

        Integer versionAfter = jdbc.queryForObject(
                "SELECT version FROM agents WHERE id = ?", Integer.class, agentId);
        assertEquals(1, versionAfter,
                "single update must bump @Version from 0 to 1 — this is the optimistic-locking guard for concurrent edits");

        String reread = jdbc.queryForObject(
                "SELECT name FROM agents WHERE id = ?", String.class, agentId);
        assertEquals("Renamed Agent", reread,
                "the renamed name must be persisted to the agents table");
    }

    // §5.8 — soft-delete via REST now works because AgentPermissionEvaluator is registered.
    // The evaluator enforces org-scoped rules: caller must hold ROLE_ADMIN AND the agent's
    // orgId must equal the caller's orgId (both null matches, representing the global
    // unscoped tenant). This is the positive path: ROLE_ADMIN caller with null orgId deletes
    // an agent with null orgId, soft-delete branch runs (active=false).
    @Test
    void deleteAgentSucceedsForSameOrgAdmin() {
        HttpHeaders auth = adminHeaders("agent-deleter", "deleter@test.local");

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> createBody = minimalAgentBody(agentId, "Doomed Agent");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(createBody, auth), AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        ResponseEntity<String> deleted = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "ROLE_ADMIN caller with matching orgId (both null) must pass the evaluator and reach the soft-delete branch");

        Boolean stillActive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
        assertEquals(Boolean.FALSE, stillActive,
                "soft-delete must set active=false on the row; hard deletion is not expected");
    }

    // §5.8 — cross-tenant DELETE surfaces as 404 (existence-leak protection, matching
    // the knowledge/schedules/workflows/teams tenant-isolation surfaces). The evaluator
    // gates on ROLE_ADMIN only; service-body findByIdAndOrgId throws ResourceNotFoundException
    // when the row exists but is owned by another org.
    @Test
    void deleteAgentReturnsNotFoundForDifferentOrgAdmin() {
        HttpHeaders auth = adminHeaders("agent-deleter-cross-org", "deleter-xo@test.local");

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> createBody = minimalAgentBody(agentId, "Tenant-Owned Agent");
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(createBody, auth), AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        jdbc.update("UPDATE agents SET org_id = ? WHERE id = ?", "org-other", agentId);

        ResponseEntity<String> deleted = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE, new HttpEntity<>(auth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, deleted.getStatusCode(),
                "caller with null orgId must not be able to delete an agent bound to org-other");

        Boolean stillActive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
        assertEquals(Boolean.TRUE, stillActive,
                "the row must remain active=true because the service threw before the soft-delete branch ran");
    }

    // ─── helpers ───

    /**
     * Returns the minimal AgentDefinition body that satisfies all five {@code @NotBlank}
     * fields on the record AND every primitive {@code boolean} component. Jackson's
     * {@code DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES} is enabled, so any missing
     * primitive boolean trips a 500 before validation runs — defaults must be supplied here.
     * Returned as a mutable {@link HashMap} so individual tests can remove or override
     * fields to simulate validation failures.
     */
    private Map<String, Object> minimalAgentBody(String agentId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId); // @JsonProperty on AgentDefinition.id
        body.put("name", name);
        body.put("description", "Created from AgentsCrudRuntimeTest");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini"); // @JsonProperty on AgentDefinition.modelId
        // Primitive boolean components — required to satisfy FAIL_ON_NULL_FOR_PRIMITIVES.
        body.put("isReasoningEnabled", false); // @JsonProperty on AgentDefinition.monitoringEnabled
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        return body;
    }

    private HttpHeaders authenticatedHeaders(String username, String email) {
        // ROLE_ADMIN required for agent CRUD via /api/admin/agents (gated since #969).
        return authenticateAs(username, email, "pass-crud-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders adminHeaders(String username, String email) {
        return authenticateAs(username, email, "pass-crud-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
