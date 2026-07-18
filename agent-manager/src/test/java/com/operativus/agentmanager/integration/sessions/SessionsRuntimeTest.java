package com.operativus.agentmanager.integration.sessions;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Sessions HTTP surface
 *   ({@code /api/sessions}, {@code /api/sessions/{id}}, {@code /api/sessions/{id}/runs},
 *   DELETE {@code /api/sessions/{id}}). Pins: (a) implicit session creation on first sync
 *   run, (b) 24-hour TTL cutoff on read + list, (c) FK cascade behavior on delete, and
 *   (d) the currently-broken tenant-filter isolation (known Boot 4 regression — same one
 *   pinned by {@code DashboardRuntimeTest}).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} §6.9 (Sessions, T019).
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link com.operativus.agentmanager.control.service.SessionService#SESSION_TTL_HOURS}
 *     is a hardcoded 24h cutoff. Both {@code listSessions} (filter on {@code updated_at >
 *     now() - 24h}) and {@code getSession} (throws {@link com.operativus.agentmanager.core.exception.BusinessValidationException}
 *     on stale {@code updated_at}) are wired to it. Case (c) pins the getSession side,
 *     case (d) pins the list-filter side — if the TTL becomes configurable, both tests
 *     will need the property-source override.
 *   - Sessions are created implicitly by
 *     {@link com.operativus.agentmanager.control.service.PersistentChatMemory} on the
 *     first ChatClient call inside a run. {@code orgId} is now read from
 *     {@link com.operativus.agentmanager.core.callback.AgentContextHolder#getOrgId()} so
 *     two users in the same org get sessions with the SAME orgId (M1 fix). The pre-fix
 *     shape — where orgId was set to userId — is pinned-against-regression by
 *     {@code ChatMemoryRuntimeTest.autoCreatedSession_orgIdMatchesAgentContextHolder_M1ProductionFix}.
 *   - {@code agent_messages.session_id} has {@code ON DELETE CASCADE} (changelog
 *     {@code 001-schema.sql:480}). {@code agent_runs.session_id} has a PLAIN FK
 *     ({@code fk_agent_runs_session}, added in {@code 016-performance-fks-and-indexes.sql:108-109})
 *     WITHOUT {@code ON DELETE CASCADE}. Net effect: DELETE /api/sessions/{id} returns
 *     500 (DataIntegrityViolation) whenever an {@code agent_runs} row still references
 *     the session — {@code SessionService.deleteSession} calls {@code deleteById} without
 *     pre-cleaning runs. Case (f) pins the clean-delete happy path (no runs → 204 + messages
 *     cascade-gone). The FK-blocks-delete-when-runs-exist gap is a separate concern worth
 *     fixing (cascade the runs or return 409 with a sensible message), but pinning a 500
 *     here would freeze the current bug into a spec — intentionally left uncovered.
 *   - {@code SessionService} now enforces org ownership on all read/delete paths via
 *     explicit {@code findBy…OrgId…} repository methods. GET /api/sessions/{id} returns
 *     404 when the session belongs to a different org. Case (g) was previously pinned at
 *     the broken (200-leaking) behavior and has been flipped to the correct 404 assertion
 *     now that the fix is in place.
 *   - {@link FakeChatModelConfig}/{@link FakeModelProviderConfig}/{@link NoOpReflectionServiceConfig}
 *     are imported ONLY for case (a) (sync run triggers a real ChatModel call). The
 *     jdbc-seeded cases don't exercise the model but sharing the same config keeps the
 *     Spring context cached across methods.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SessionsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    /**
     * Inserts a minimal agent row directly so session/run FK constraints (fk_agent_sessions_agent,
     * added in changelog 016-performance-fks-and-indexes.sql) don't reject our jdbc-seeded
     * sessions. Going through /api/admin/agents here would work but adds a lot of noise for a
     * test that only needs the row to exist for the FK target.
     */
    private void seedAgent(String agentId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, agentId);
    }

    // §6.9 — Case (a): a sync run creates an agent_sessions row via PersistentChatMemory
    // and GET /api/sessions/{id} returns it with agentId + userId populated. Pins the
    // implicit-creation contract — clients never POST /api/sessions explicitly.
    @Test
    void syncRunImplicitlyCreatesSessionRowAndDetailEndpointReturnsIt() {
        HttpHeaders auth = authenticatedHeaders("sess-implicit-runner");
        String agentId = createAgentViaApi(auth, "Session Implicit Agent");

        fakeModel.respondWith("Session-bound reply.");

        String sessionId = "session-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("message", "Kick a session.", "sessionId", sessionId);

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, runResp.getStatusCode(),
                "sync run must succeed so PersistentChatMemory creates the session — a 4xx here means the run path broke, not the session path");

        ResponseEntity<Map<String, Object>> sessionResp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, sessionResp.getStatusCode(),
                "GET /api/sessions/{id} must return 200 for the session just created by the sync run — 404 here means PersistentChatMemory did not persist the row");
        Map<String, Object> payload = sessionResp.getBody();
        assertNotNull(payload, "session detail body must be populated");
        assertEquals(sessionId, payload.get("id"),
                "AgentSession serializes session_id as 'id' via @JsonProperty — pin prevents a refactor from renaming the JSON field and breaking UI clients");
        assertEquals(agentId, payload.get("agentId"),
                "agent binding must be persisted — a null here breaks the 'sessions for agent X' listing filter");
        assertNotNull(payload.get("userId"),
                "userId must be populated from SecurityContextUtils.resolveCurrentUserId() — missing means PersistentChatMemory could not resolve the authenticated principal");
        assertNotNull(payload.get("createdAt"));
        assertNotNull(payload.get("updatedAt"));
    }

    // §6.9 — Case (b): unknown sessionId → 404. Pins that SessionController maps the
    // empty Optional from SessionService.getSession to a plain 404, not a 500 or an
    // empty 200 body.
    @Test
    void getSessionDetailReturns404ForUnknownSessionId() {
        HttpHeaders auth = authenticatedHeaders("sess-unknown-runner");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/sessions/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "missing session → 404 — a 200 with empty body would break the UI's 'create vs load' branch, a 500 would mask the Optional.empty → notFound path");
    }

    // §6.9 — Case (c): a session whose updated_at is older than the 24h TTL throws
    // BusinessValidationException from SessionService.getSession, which GlobalExceptionHandler
    // maps to 400. Pins the TTL-read-path semantics — clients see "session expired" as
    // a validation error, not a 404 (which would conflict with the actual not-found case).
    @Test
    void getSessionDetailReturns400WhenUpdatedAtExceedsTwentyFourHourTtl() {
        String username = "sess-stale-runner";
        HttpHeaders auth = authenticatedHeaders(username);

        String agentId = "agent-stale-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-stale-" + UUID.randomUUID();
        insertSessionRow(sessionId, username, agentId, minutesAgo(60 * 25)); // 25h ago → stale

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "stale session must surface BusinessValidationException as 400 — 404 would conflate 'never existed' with 'expired', 200 would leak stale rows past the TTL guard");
    }

    // §6.9 — Case (d): GET /api/sessions applies the same 24h TTL cutoff in its
    // repository queries (findByUserIdAndUpdatedAtAfter). Seed 2 fresh + 1 stale for the
    // same userId, filter with ?userId=, assert exactly 2 returned in the Page content.
    // Also pins: the response is a Spring Data Page JSON (content + totalElements +
    // pageable), not a bare array.
    @Test
    void listSessionsFiltersByUserIdAndAppliesTwentyFourHourTtlCutoff() {
        String username = "sess-list-runner";
        HttpHeaders auth = authenticatedHeaders(username);

        String agentA = "agent-list-a-" + UUID.randomUUID();
        String agentB = "agent-list-b-" + UUID.randomUUID();
        String agentC = "agent-list-c-" + UUID.randomUUID();
        seedAgent(agentA);
        seedAgent(agentB);
        seedAgent(agentC);
        String freshA = "session-fresh-a-" + UUID.randomUUID();
        String freshB = "session-fresh-b-" + UUID.randomUUID();
        String stale = "session-stale-" + UUID.randomUUID();
        insertSessionRow(freshA, username, agentA, minutesAgo(5));
        insertSessionRow(freshB, username, agentB, minutesAgo(10));
        insertSessionRow(stale, username, agentC, minutesAgo(60 * 30)); // 30h ago

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/sessions?userId=" + username + "&size=50"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> page = resp.getBody();
        assertNotNull(page, "page body must be non-null");
        assertTrue(page.containsKey("content"),
                "response must be Spring Data Page shape — a bare array would break UI paginators expecting totalElements/totalPages");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertEquals(2, content.size(),
                "only fresh sessions within 24h cutoff must return — a 3 here means the TTL was dropped from listSessions and stale sessions leak through list endpoints (the Get path would still guard but list is the more common UI flow)");

        List<String> ids = content.stream().map(s -> (String) s.get("id")).toList();
        assertTrue(ids.contains(freshA) && ids.contains(freshB),
                "both fresh sessions must be present regardless of ordering");
        assertTrue(!ids.contains(stale),
                "stale session must be filtered out — listing it would violate the TTL invariant getSession enforces");
    }

    // §6.9 — Case (e): GET /api/sessions/{id}/runs returns the runs bound to the session
    // in ascending createdAt order (RunRepository.findBySessionIdOrderByCreatedAtAsc).
    // Seed two runs with explicit created_at values, confirm order.
    @Test
    void getSessionRunsReturnsRunsInCreatedAtAscendingOrder() {
        String username = "sess-runs-runner";
        HttpHeaders auth = authenticatedHeaders(username);
        String agentId = createAgentViaApi(auth, "Session Runs Agent");

        String sessionId = "session-runs-" + UUID.randomUUID();
        insertSessionRow(sessionId, username, agentId, minutesAgo(5));

        String earlierRunId = "run-earlier-" + UUID.randomUUID();
        String laterRunId = "run-later-" + UUID.randomUUID();
        insertRunRow(earlierRunId, agentId, sessionId, username, "COMPLETED", minutesAgo(10));
        insertRunRow(laterRunId, agentId, sessionId, username, "COMPLETED", minutesAgo(2));

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/sessions/" + sessionId + "/runs"),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> runs = resp.getBody();
        assertNotNull(runs);
        assertEquals(2, runs.size(),
                "both seeded runs must surface — fewer means the sessionId filter drifted, more means cross-session runs leaked (agent_runs has no FK to agent_sessions so a bug here is plausible)");
        assertEquals(earlierRunId, runs.get(0).get("id"),
                "ascending createdAt order must put the older run first — a reversal breaks transcript UIs that render top-down chronologically");
        assertEquals(laterRunId, runs.get(1).get("id"));
    }

    // §6.9 — Case (f): DELETE /api/sessions/{id} on a session with NO runs returns 204,
    // cascades to agent_messages (schema FK ON DELETE CASCADE), and wipes the session row.
    // Companion case (f2) pins the with-runs path now that migration 024 promoted
    // fk_agent_runs_session to ON DELETE CASCADE.
    @Test
    void deleteSessionWithoutRunsReturns204AndCascadesAgentMessages() {
        String username = "sess-delete-runner";
        HttpHeaders auth = authenticatedHeaders(username);

        String agentId = "agent-del-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-delete-" + UUID.randomUUID();
        insertSessionRow(sessionId, username, agentId, minutesAgo(5));
        insertMessageRow(sessionId, "USER", "hello");
        insertMessageRow(sessionId, "ASSISTANT", "hi back");

        ResponseEntity<Void> deleteResp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode(),
                "DELETE on a session with no runs returns 204 per SessionController — pin so a refactor doesn't silently flip to 200 or 404-on-missing");

        Long sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(0L, sessionRows, "session row must be gone after DELETE");

        Long messageRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ?", Long.class, sessionId);
        assertEquals(0L, messageRows,
                "agent_messages must cascade-delete (FK ON DELETE CASCADE, changelog 001-schema.sql:480) — a non-zero here means the FK constraint was dropped or altered");
    }

    // §6.9 — Case (f2): DELETE /api/sessions/{id} on a session WITH runs returns 204
    // and the dependent agent_runs rows are removed by the FK cascade introduced in
    // migration 024. Prior to that migration this path returned 500 (plain FK + no
    // cascade-in-service); pinning the cascade behaviour here guards against a future
    // revert that drops the cascade clause.
    @Test
    void deleteSessionWithRunsCascadesAgentRunsRows() {
        String username = "sess-delete-with-runs";
        HttpHeaders auth = authenticatedHeaders(username);

        String agentId = "agent-del-runs-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-delete-runs-" + UUID.randomUUID();
        insertSessionRow(sessionId, username, agentId, minutesAgo(5));

        String runId = "run-" + UUID.randomUUID();
        insertRunRow(runId, agentId, sessionId, username, "COMPLETED", minutesAgo(3));

        ResponseEntity<Void> deleteResp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode(),
                "DELETE on a session WITH runs returns 204 once fk_agent_runs_session has ON DELETE CASCADE (migration 024)");

        Long sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(0L, sessionRows, "session row must be gone after DELETE");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE session_id = ?", Long.class, sessionId);
        assertEquals(0L, runRows,
                "agent_runs rows must cascade-delete via fk_agent_runs_session ON DELETE CASCADE (migration 024) — non-zero means the cascade was dropped");
    }

    // §6.9 — Case (g): cross-tenant GET /api/sessions/{id} must return 404.
    // SessionService filters by caller orgId via existsBySessionIdAndOrgId — ownership
    // mismatch returns Optional.empty(), which the controller maps to 404 (existence-leak
    // protection). The intruder is in DEFAULT_SYSTEM_ORG (from authenticatedHeaders); the
    // session is explicitly seeded into a foreign org so the lookup must miss.
    @Test
    void crossUserGetSessionByIdIsBlockedAfterTenantScopeFix() {
        HttpHeaders intruderAuth = authenticatedHeaders("sess-intruder");

        String agentId = "agent-leak-" + UUID.randomUUID();
        seedAgent(agentId);
        String sessionId = "session-leak-" + UUID.randomUUID();
        insertSessionRow(sessionId, "sess-owner", "FOREIGN_ORG", agentId, minutesAgo(5));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/sessions/" + sessionId),
                HttpMethod.GET, new HttpEntity<>(intruderAuth), JSON_MAP);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant GET /api/sessions/{id} must return 404 — SessionService now filters by caller orgId so sessions in another org are invisible (existence-leak protection)");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-sess-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private Timestamp minutesAgo(long minutes) {
        return Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes));
    }

    private void insertSessionRow(String sessionId, String userId, String agentId, Timestamp updatedAt) {
        insertSessionRow(sessionId, userId, "DEFAULT_SYSTEM_ORG", agentId, updatedAt);
    }

    /**
     * Overload that takes an explicit org_id. Required by the cross-tenant test where
     * the row must live in a different org than the caller; the no-org overload defaults
     * to DEFAULT_SYSTEM_ORG to match the orgId that {@link BaseIntegrationTest#authenticateAs}
     * stamps on self-registered users.
     */
    private void insertSessionRow(String sessionId, String userId, String orgId,
                                  String agentId, Timestamp updatedAt) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                sessionId, userId, orgId, agentId, updatedAt, updatedAt);
    }

    private void insertMessageRow(String sessionId, String type, String content) {
        jdbc.update("""
                INSERT INTO agent_messages (id, session_id, message_type, content, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, now())
                """, sessionId, type, content);
    }

    private void insertRunRow(String runId, String agentId, String sessionId, String userId,
                              String status, Timestamp createdAt) {
        // Same orgId reasoning as insertSessionRow — agent_runs queries
        // (RunsController.listRuns, /sessions/{id}/runs) are tenant-scoped.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'test', 'test', ?, ?)
                """, runId, agentId, sessionId, userId, "DEFAULT_SYSTEM_ORG", status, createdAt, createdAt);
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Sessions runtime test agent");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before /runs references it");
        return agentId;
    }
}
