package ai.operativus.agentmanager.integration.knowledge;

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
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box edge-case coverage of the {@code /api/v1/knowledge-bases}
 *   surface that complements {@link KnowledgeBaseRuntimeTest} (happy create/delete/ingest)
 *   and {@link KnowledgeBaseTenantIsolationRuntimeTest} (cross-tenant 404s). Specifically:
 *   <ul>
 *     <li>PUT happy-path + unknown id → 404 (controller short-circuits via
 *         {@code findByIdAndOrgId().orElse(ResponseEntity.notFound())})</li>
 *     <li>DELETE unknown id → 404, no job enqueued</li>
 *     <li>GET /{id}/agents empty when no bindings + unknown KB → 404</li>
 *     <li>POST /{id}/agents/{agentId} unknown KB → 404; repeat-assign is idempotent
 *         (the service's {@code !contains} guard at
 *         {@code KnowledgeBaseService.assignAgentToKb} suppresses duplicates)</li>
 *     <li>DELETE /{id}/agents/{agentId} unknown KB → 404; remove of a binding that
 *         doesn't exist returns 204 silently (the service's
 *         {@code ifPresent + list.remove(...)} branch is a no-op when the id
 *         isn't in the JSONB array)</li>
 *   </ul>
 *
 * <p>Note: {@code KnowledgeBaseController} carries NO {@code @PreAuthorize} annotations
 *   (unlike {@code ModelController}), so this class deliberately has no ROLE_USER 403
 *   cases — any authenticated user can hit every endpoint.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class KnowledgeBaseEdgeCasesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // ─── PUT /api/v1/knowledge-bases/{id} ────────────────────────────────────

    @Test
    void updateKb_happyPath_persistsChangesAndOrgIdRemainsImmutable() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-put-happy");
        String kbId = createKbReturningId(auth, "edge-put-original", "original description");
        String originalOrgId = jdbc.queryForObject(
                "SELECT org_id FROM knowledge_bases WHERE id = ?::uuid", String.class, kbId);
        assertNotNull(originalOrgId, "fixture must have stamped org_id on create");

        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("name", "edge-put-renamed");
        updateBody.put("description", "renamed description");
        // The orgId field on the body must be ignored by the controller — pinned below.
        updateBody.put("orgId", "tenant-injection-attempt");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId),
                HttpMethod.PUT, new HttpEntity<>(updateBody, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "PUT happy-path must return 200 with the saved entity (controller has no "
                        + "@ResponseStatus). A 204 here would mean the controller flipped to a "
                        + "no-body convention; a 404 would mean findByIdAndOrgId stopped resolving.");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, description, org_id FROM knowledge_bases WHERE id = ?::uuid", kbId);
        assertEquals("edge-put-renamed", row.get("name"),
                "JDBC re-read must show the new name persisted (the deleted GET-singular endpoint "
                        + "means we cannot HTTP-round-trip the verification — mirror the Models PATCH "
                        + "pattern from ModelsRuntimeTest)");
        assertEquals("renamed description", row.get("description"));
        assertEquals(originalOrgId, row.get("org_id"),
                "org_id is immutable after creation — body's orgId 'tenant-injection-attempt' "
                        + "must NOT have rewritten the tenant ownership; if this flips, the "
                        + "controller's documented invariant 'orgId is immutable after creation' "
                        + "was lost");
    }

    @Test
    void updateKb_unknownId_returns404() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-put-404");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "irrelevant-" + UUID.randomUUID());
        body.put("description", "irrelevant");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + UUID.randomUUID()),
                HttpMethod.PUT, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PUT on an unknown id must return 404 via the controller's "
                        + "findByIdAndOrgId().orElse(notFound()) chain. A 200 here would mean "
                        + "the controller flipped to PUT-as-upsert semantics, which would silently "
                        + "create rows with caller-supplied UUIDs and no org_id audit trail");
    }

    // ─── DELETE /api/v1/knowledge-bases/{id} ─────────────────────────────────

    @Test
    void deleteKb_unknownId_returns404AndEnqueuesNoJob() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-del-404");

        Long jobsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'KNOWLEDGE_BASE_DELETION'",
                Long.class);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE on an unknown id must return 404 via the controller's "
                        + "existsByIdAndOrgId guard. A 202 here would mean a deletion job was "
                        + "enqueued for a non-existent row — the handler would later fail with "
                        + "an orphan KNOWLEDGE_BASE_DELETION job in DLQ");

        Long jobsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'KNOWLEDGE_BASE_DELETION'",
                Long.class);
        assertEquals(jobsBefore, jobsAfter,
                "no KNOWLEDGE_BASE_DELETION job may have been enqueued for the unknown id — "
                        + "controller must short-circuit before reaching jobQueueService.enqueue");
    }

    // ─── GET /api/v1/knowledge-bases/{id}/agents ─────────────────────────────

    @Test
    void getAssignedAgents_unknownKb_returns404() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-getagents-404");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + UUID.randomUUID() + "/agents"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /{id}/agents on an unknown KB must return 404. A 200 with empty list here "
                        + "would mean the controller skipped the existsByIdAndOrgId guard and "
                        + "leaked existence info via reverse-lookup against a phantom KB id");
    }

    @Test
    void getAssignedAgents_emptyWhenNoBindings_returns200() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-getagents-empty");
        String kbId = createKbReturningId(auth, "edge-empty-bindings", "no agents yet");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "GET /{id}/agents on an existing KB with no bindings must return 200 (not 204 "
                        + "and not 404) — empty list is a real response shape");
        assertNotNull(resp.getBody(), "body must be a (possibly empty) array, never null");
        assertTrue(resp.getBody().isEmpty(),
                "no bindings exist for a freshly-created KB; got " + resp.getBody());
    }

    // ─── POST /api/v1/knowledge-bases/{id}/agents/{agentId} ──────────────────

    @Test
    void assignAgent_unknownKb_returns404() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-assign-404");
        String agentId = seedAgentRow("edge-assign-404");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + UUID.randomUUID() + "/agents/" + agentId),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "POST /{id}/agents/{agentId} on an unknown KB must return 404 BEFORE the "
                        + "service's assignAgentToKb is invoked. A 204 here would mean the "
                        + "agent's knowledge_base_ids JSONB array silently grew with a "
                        + "dangling KB reference");

        String jsonb = jdbc.queryForObject(
                "SELECT knowledge_base_ids::text FROM agents WHERE id = ?", String.class, agentId);
        assertTrue(jsonb == null || "[]".equals(jsonb) || jsonb.isBlank(),
                "agent's knowledge_base_ids must remain empty — a 404 must not have mutated "
                        + "the agent row; got " + jsonb);
    }

    @Test
    void assignAgent_idempotentOnRepeat_doesNotDuplicateInJsonb() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-assign-idem");
        String kbId = createKbReturningId(auth, "edge-idempotent-assign", "repeat-assign fixture");
        String agentId = seedAgentRow("edge-idempotent-assign");

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Void> resp = rest.exchange(
                    url("/api/v1/knowledge-bases/" + kbId + "/agents/" + agentId),
                    HttpMethod.POST, new HttpEntity<>(auth), Void.class);
            assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                    "every repeat assignment must return 204 — the service's `if (!contains)` "
                            + "branch suppresses duplicates but the HTTP layer treats both "
                            + "states as success (idempotent PUT-style semantics on a POST)");
        }

        String jsonb = jdbc.queryForObject(
                "SELECT knowledge_base_ids::text FROM agents WHERE id = ?", String.class, agentId);
        assertNotNull(jsonb, "agent row must have a populated knowledge_base_ids after assign");
        // Count occurrences of the kb id substring — must appear exactly once even after 3 calls.
        int occurrences = countOccurrences(jsonb, kbId);
        assertEquals(1, occurrences,
                "kb id must appear exactly once in agents.knowledge_base_ids after 3 assignments "
                        + "— the `!contains` guard in KnowledgeBaseService.assignAgentToKb must "
                        + "have suppressed duplicates. Found " + occurrences + " occurrences in: "
                        + jsonb);
    }

    // ─── DELETE /api/v1/knowledge-bases/{id}/agents/{agentId} ────────────────

    @Test
    void removeAgent_unknownKb_returns404() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-remove-404");
        String agentId = seedAgentRow("edge-remove-404");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + UUID.randomUUID() + "/agents/" + agentId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE /{id}/agents/{agentId} on an unknown KB must return 404 BEFORE the "
                        + "service's removeAgentFromKb is invoked. A 204 here would let callers "
                        + "probe KB existence by attempting removal");
    }

    @Test
    void removeAgent_unboundAgent_returns204Silently() {
        HttpHeaders auth = authenticatedHeaders("kb-edge-remove-unbound");
        String kbId = createKbReturningId(auth, "edge-remove-unbound", "no-binding fixture");
        String agentId = seedAgentRow("edge-remove-unbound");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/knowledge-bases/" + kbId + "/agents/" + agentId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "DELETE of a non-existent binding (agent exists, KB exists, but agent's "
                        + "knowledge_base_ids does NOT contain the KB id) must return 204 "
                        + "silently — KnowledgeBaseService.removeAgentFromKb is a no-op when "
                        + "`agent.getKnowledgeBaseIds().remove(...)` returns false. A 404 here "
                        + "would mean the service flipped to strict-not-found semantics");

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(1L, rowCount,
                "agent row must still exist — a 204 on no-op removal must not have deleted it");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kb-edge-1234",
                List.of("ROLE_USER"));
    }

    private String createKbReturningId(HttpHeaders auth, String name, String description) {
        Map<String, Object> body = Map.of(
                "name", name + "-" + UUID.randomUUID(),
                "description", description);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/knowledge-bases"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "fixture precondition: createKb must succeed; got " + resp.getStatusCode());
        assertNotNull(resp.getBody(), "fixture precondition: createKb body must not be null");
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedAgentRow(String nameTag) {
        String agentId = "agent-" + nameTag + "-" + UUID.randomUUID();
        // Seed the gpt-4o-mini model row first (some test runs share schema state across
        // classes but truncateDatabase has wiped models). Use ON CONFLICT DO NOTHING so
        // sibling tests in the same run don't collide.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, description, model_id, knowledge_base_ids,
                                    is_reasoning_enabled, is_team, requires_pii_redaction,
                                    approved_for_production, maintenance_mode, active, enforce_json_output,
                                    instructions, org_id, created_at, updated_at)
                VALUES (?, ?, 'edge-case fixture', 'gpt-4o-mini', '[]'::jsonb,
                        false, false, false, false, false, true, false,
                        'Be helpful.', 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "Edge-Case Agent " + nameTag);
        return agentId;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
