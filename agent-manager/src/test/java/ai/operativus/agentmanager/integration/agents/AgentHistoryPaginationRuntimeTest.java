package ai.operativus.agentmanager.integration.agents;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/history} — paginated
 *   {@code AgentRun} history for a specific agent. Tenant-scoped via
 *   {@code agentRepository.existsByIdAndOrgId}; ordered by created_at DESC.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentHistoryPaginationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void agentWithMultipleRuns_returnsPagedResults_orderedByCreatedAtDesc() {
        String orgId = "org-hist-paged-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("hist-paged", orgId);
        String agentId = seedAgent(orgId);
        String oldestId = seedRun(agentId, orgId, LocalDateTime.now().minusHours(3));
        String middleId = seedRun(agentId, orgId, LocalDateTime.now().minusHours(2));
        String newestId = seedRun(agentId, orgId, LocalDateTime.now().minusHours(1));

        Map<String, Object> body = getHistory(auth, agentId, 0, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertAll("history pagination contract",
                () -> assertEquals(3, content.size(), "all 3 runs in single page"),
                () -> assertEquals(3L, totalElements(body)),
                () -> assertEquals(newestId, content.get(0).get("id"),
                        "newest run first (DESC); got " + content.get(0).get("id")),
                () -> assertEquals(middleId, content.get(1).get("id")),
                () -> assertEquals(oldestId, content.get(2).get("id")));
    }

    @Test
    void emptyHistory_returns200WithEmptyPage_notNullOrError() {
        String orgId = "org-hist-empty-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("hist-empty", orgId);
        String agentId = seedAgent(orgId);

        Map<String, Object> body = getHistory(auth, agentId, 0, 20);

        assertEquals(0L, totalElements(body),
                "agent with no runs must return totalElements=0 (not 404, not null)");
    }

    @Test
    void unknownAgentId_returns404() {
        String orgId = "org-hist-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("hist-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/history"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "history on unknown agent id must return 404");
    }

    @Test
    void crossTenantAgentId_returns404_noHistoryLeak() {
        String orgA = "org-hist-cross-A-" + UUID.randomUUID();
        String orgB = "org-hist-cross-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("hist-cross-a", orgA);
        registerLoginWithOrg("hist-cross-b", orgB);

        String foreignAgent = seedAgent(orgB);
        seedRun(foreignAgent, orgB, LocalDateTime.now());

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/history"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant history must 404 — must not leak run rows from other orgs");
    }

    @Test
    void pageSizeParameter_isHonored() {
        String orgId = "org-hist-page-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("hist-page", orgId);
        String agentId = seedAgent(orgId);
        for (int i = 0; i < 5; i++) {
            seedRun(agentId, orgId, LocalDateTime.now().minusMinutes(i));
        }

        Map<String, Object> body = getHistory(auth, agentId, 0, 2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertEquals(2, content.size(), "size=2 must clip to 2 items per page");
        assertEquals(5L, totalElements(body),
                "totalElements reports the FULL count, not the page slice");
    }

    @SuppressWarnings("unchecked")
    private static long totalElements(Map<String, Object> body) {
        // Spring's new Page serialization (Spring Boot 3.2+) wraps page metadata as:
        //   { "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }
        Object page = body.get("page");
        if (page instanceof Map<?, ?> pageMap) {
            Object n = pageMap.get("totalElements");
            if (n instanceof Number num) return num.longValue();
        }
        // Fallback for the legacy direct-mode serialization where totalElements is top-level.
        Object n = body.get("totalElements");
        if (n instanceof Number num) return num.longValue();
        throw new AssertionError("totalElements not found in either page.totalElements or "
                + "top-level position; body=" + body);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String seedAgent(String orgId) {
        String agentId = "agent-hist-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "History test agent", orgId);
        return agentId;
    }

    private String seedRun(String agentId, String orgId, LocalDateTime createdAt) {
        String runId = "run-hist-" + UUID.randomUUID();
        String sessionId = "sess-hist-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'hist-user', ?, ?, now(), now())
                """, sessionId, orgId, agentId);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'hist-user', ?, 'history-test', 'COMPLETED', 0, ?, ?)
                """, runId, agentId, sessionId, orgId, createdAt, createdAt);
        return runId;
    }

    private Map<String, Object> getHistory(HttpHeaders auth, String agentId, int page, int size) {
        ResponseEntity<String> raw = rest.exchange(
                url("/api/admin/agents/" + agentId + "/history?page=" + page + "&size=" + size),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        if (raw.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("history failed; status=" + raw.getStatusCode()
                    + " body=" + raw.getBody());
        }
        try {
            return json.readValue(raw.getBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new AssertionError("response not parseable as Map; body=" + raw.getBody(), e);
        }
    }
}
