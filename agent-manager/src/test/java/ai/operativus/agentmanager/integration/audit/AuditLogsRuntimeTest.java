package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.control.repository.AgentAuditRepository;
import ai.operativus.agentmanager.core.entity.AgentAuditEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.SchedulerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the audit-log surface — the
 *   {@code agent_audits} table, the write path on
 *   {@link ai.operativus.agentmanager.control.service.AgentAdminService#logAudit},
 *   the read path on {@link ai.operativus.agentmanager.control.controller.AuditLogController}
 *   (via {@link ai.operativus.agentmanager.control.service.AuditLogService}), and the
 *   retention sweep on {@link ai.operativus.agentmanager.control.service.DataRetentionService}.
 *   Pins the positive CRUD-produces-audit-row path, the filter + pagination shape, and
 *   four current-contract gaps that matrix §24 calls for but the code does not yet enforce
 *   (immutability, retention-purge, audit-on-non-agent mutations, auth-event logging,
 *   org-scoping).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §24 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T046.
 *
 * Implementation notes / gaps these tests pin:
 *   - Audit rows are ONLY produced by {@link ai.operativus.agentmanager.control.service.AgentAdminService#logAudit}
 *     on agent-CRUD actions (CREATE, UPDATE, DELETE, RESTORE, CLONE, IMPORT, ROLLBACK).
 *     No other mutation path writes to {@code agent_audits}: user-admin actions, model
 *     CRUD, knowledge-base mutations, team lifecycle, schedule edits, etc. all leave no
 *     audit row. Matrix §24 case 1 ("every controller mutation writes exactly one audit
 *     row") is aspirational; case (a) here pins the positive half (agent CRUD DOES produce
 *     exactly one row per action) and {@link #nonAgentMutationsShouldAlsoProduceAuditRows}
 *     stays {@code @Disabled} for the full-coverage ideal.
 *   - {@link ai.operativus.agentmanager.control.controller.AuthController} writes
 *     LOGIN_SUCCESS, LOGIN_FAILURE, and LOGOUT rows to {@code system_audits} via
 *     {@link ai.operativus.agentmanager.control.service.SystemAuditService#record} directly
 *     (interceptor cannot see failures). Matrix §24 case 2 is active in
 *     {@link #authEventsShouldProduceAuditRows}.
 *   - {@link ai.operativus.agentmanager.control.controller.AuditLogController} supports
 *     {@code username}, {@code action}, and {@code agentId} query filters with Spring Data
 *     pagination. It does NOT expose a date-range filter; matrix §24 case 3 calls for one.
 *     Case (b) pins what works; the date-range gap stays inside the Javadoc here rather
 *     than as a separate {@code @Disabled} test.
 *   - {@code agent_audits} is append-only: the
 *     {@code trg_agent_audits_immutable} trigger (changeset 029) rejects raw UPDATE and
 *     DELETE unless the caller set {@code agm.audit_immutability_bypass='true'} on the
 *     current transaction. Matrix §24 case 4 is active in {@link #auditRowsAreImmutable};
 *     retention purge + GDPR erasure are the only authorized mutation paths (both toggle
 *     the bypass before writing).
 *   - {@link ai.operativus.agentmanager.control.service.DataRetentionService#enforceRetentionPolicies}
 *     DOES NOT actually purge audit rows — the method reads {@code auditRetentionDays}
 *     but the body only touches {@code sessionRepository} and {@code alertEventRepository}.
 *     Matrix §24 case 5 (retention purge) is aspirational; case (d) pins that an audit
 *     row dated past the retention window survives the sweep.
 *   - Matrix §24 case 6 (org-scoped visibility) is now enforced via an EXISTS subquery
 *     through {@code agents.org_id} (Fix B — no schema change needed). See
 *     {@link #auditLogShouldBeOrgScoped} and {@link #exportAuditLogsCsvShouldBeOrgScoped}.
 *   - {@link ai.operativus.agentmanager.control.controller.AuditLogController} now carries
 *     class-level {@code @PreAuthorize("hasRole('ADMIN')")} (Fix A prerequisite included in
 *     Fix B). See {@link ai.operativus.agentmanager.integration.admin.AdminEndpointAuthzRuntimeTest}
 *     for the RBAC matrix coverage.
 *   - {@link ai.operativus.agentmanager.control.service.AgentAdminService#deleteAgent}
 *     is gated by {@code @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'delete')")}.
 *     With {@link ai.operativus.agentmanager.control.security.AgentPermissionEvaluator}
 *     registered, a {@code ROLE_ADMIN} caller in the same org passes the gate and the
 *     soft-delete branch reaches {@code logAudit}, producing a DELETE row with
 *     {@code changeset="{}"} via the fallback. Exercised in
 *     {@link #deleteAgentShouldAlsoProduceAuditRow}. Case (a) still pins CREATE + UPDATE
 *     separately to isolate the UPDATE-changeset shape.
 *   - {@code agent_audits.agent_id} has a FK to {@code agents(id)} with no cascade
 *     (001-schema.sql:187). Audit-row seeding via the repository must insert a matching
 *     {@code agents} row first; {@link #seedAuditRow} handles the pre-seed.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
public class AuditLogsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> AGENT_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired private AgentAuditRepository auditRepository;
    @Autowired private SchedulerTestSupport scheduler;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("fake-audit-model");
    }

    // §24 — Case (a): Agent CRUD drives AgentAdminService.logAudit, which writes one row
    // per action into agent_audits. Exercises POST + PUT through the real HTTP surface and
    // asserts (1) two rows exist, (2) each carries the authenticated username (not the
    // SYSTEM_PRINCIPAL fallback), (3) the action strings match the AuditActionType enum
    // values, and (4) the changeset column is non-null.
    //
    // DELETE is exercised separately in {@link #deleteAgentShouldAlsoProduceAuditRow} so
    // this case keeps its focus on the UPDATE-changeset shape (CREATE + UPDATE capture
    // before/after state). DELETE produces its own row via the same logAudit helper.
    @Test
    void agentCrudProducesOneAuditRowPerAction() {
        // /api/admin/agents is ROLE_ADMIN-gated; authenticatedHeaders() grants only ROLE_USER
        // (→403). Register as admin here, matching nonAgentMutationsShouldAlsoProduceAuditRows.
        HttpHeaders auth = authenticateAs("audit-crud-actor",
                "audit-crud-actor@test.local", "pass-audit-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        String agentId = "agent-audit-" + shortUuid();

        // CREATE
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(minimalAgentBody(agentId, "Auditing Me"), auth),
                AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        // UPDATE
        Map<String, Object> updateBody = minimalAgentBody(agentId, "Auditing Me Renamed");
        updateBody.put("description", "updated for audit");
        ResponseEntity<Map<String, Object>> updated = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, auth),
                AGENT_TYPE);
        assertEquals(HttpStatus.OK, updated.getStatusCode());

        // Exactly 2 audit rows for this agent (CREATE + UPDATE).
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, username, changeset IS NULL AS changeset_null
                FROM agent_audits
                WHERE agent_id = ?
                ORDER BY created_at
                """, agentId);
        assertEquals(2, rows.size(),
                "agent CREATE + UPDATE through /api/admin/agents must produce exactly 2 audit rows; got " + rows.size()
                        + " (DELETE is intentionally omitted — see class Javadoc)");

        List<String> actions = rows.stream().map(r -> (String) r.get("action")).toList();
        assertTrue(actions.contains("CREATE"), "audit rows must include CREATE action; got " + actions);
        assertTrue(actions.contains("UPDATE"), "audit rows must include UPDATE action; got " + actions);

        assertTrue(rows.stream().allMatch(r -> "audit-crud-actor".equals(r.get("username"))),
                "every audit row must record the authenticated username — SecurityContextHolder.getName() fallback to SYSTEM_PRINCIPAL would indicate the auth context was lost before logAudit fired");

        rows.forEach(r -> assertFalse((Boolean) r.get("changeset_null"),
                "changeset column must be non-null (AgentAdminService.logAudit writes '{}' via the objectMapper fallback when the changeset object is null)"));
    }

    // §24 — Case (b): /api/admin/audit-logs supports three filters (username, action,
    // agentId) plus Spring Data pagination. Seeds a predictable set of rows via JDBC,
    // then exercises each filter plus a narrow page window to prove the Page<> shape.
    // NB: the controller does NOT support a date-range filter (matrix §24 case 3 calls
    // for one — see class Javadoc). That gap is documented but not pinned as a separate
    // @Disabled test to keep this suite focused on the filters that exist.
    @Test
    void listAuditLogsFiltersByUsernameActionAgentIdWithPagination() {
        // registerLoginWithOrg binds orgId to the principal so resolveCallerOrgId() returns "org-A".
        HttpHeaders auth = registerLoginWithOrg("audit-reader-A", "org-A");

        seedAuditRow("agent-alpha", "CREATE", "alice", "org-A");
        seedAuditRow("agent-alpha", "UPDATE", "alice", "org-A");
        seedAuditRow("agent-alpha", "DELETE", "alice", "org-A");
        seedAuditRow("agent-bravo", "CREATE", "bob", "org-A");
        seedAuditRow("agent-bravo", "UPDATE", "bob", "org-A");
        // org-B seeds — must be invisible to org-A admin.
        seedAuditRow("agent-charlie", "CREATE", "carol", "org-B");
        seedAuditRow("agent-delta",   "CREATE", "dave",  "org-B");

        // All rows for org-A (no filter) — 5 rows; 2 org-B rows must be excluded.
        assertEquals(5, totalElements(fetchAuditPage(auth, "")),
                "unfiltered listing must see all 5 org-A rows; the 2 org-B rows must be excluded by the EXISTS org-scope filter");

        // Filter by username = alice → 3 org-A rows.
        assertEquals(3, totalElements(fetchAuditPage(auth, "?username=alice")),
                "username=alice filter must return the 3 org-A alpha rows");

        // Filter by action = CREATE → 2 org-A rows (alice + bob; carol/dave are in org-B).
        assertEquals(2, totalElements(fetchAuditPage(auth, "?action=CREATE")),
                "action=CREATE filter must return the 2 org-A create rows (alice + bob); org-B creates must be excluded");

        // Filter by agentId = agent-bravo → 2 org-A rows.
        assertEquals(2, totalElements(fetchAuditPage(auth, "?agentId=agent-bravo")),
                "agentId=agent-bravo filter must return the 2 org-A bravo rows");

        // Pagination: size=2 over 5 org-A rows → 3 pages.
        // Boot 4 Page serialization shape: {content:[...], page:{size,number,totalElements,totalPages}}.
        Map<String, Object> pagedBody = fetchAuditPage(auth, "?size=2");
        Map<String, Object> pageMeta = pageMeta(pagedBody);
        assertEquals(2, ((Number) pageMeta.get("size")).intValue(),
                "Pageable size=2 must round-trip through the response");
        assertEquals(3, ((Number) pageMeta.get("totalPages")).intValue(),
                "5 org-A rows at size=2 must yield 3 pages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) pagedBody.get("content");
        assertEquals(2, content.size(), "first page must carry 2 rows");
    }

    // §24 case 3 (admin export) — GET /api/admin/audit-logs/export streams text/csv with
    // a Content-Disposition: attachment header and a body that begins with the schema
    // header row. Same filter semantics as the JSON list endpoint; ROLE_ADMIN-gated via
    // controller-level @PreAuthorize. Pins the operator-facing forensic-export contract.
    @Test
    void exportAuditLogsCsv_returnsAttachmentWithFilteredRows() {
        HttpHeaders adminAuth = registerLoginWithOrg("audit-export-admin", "export-org");

        seedAuditRow("agent-export-a", "CREATE", "carol", "export-org");
        seedAuditRow("agent-export-a", "UPDATE", "carol", "export-org");
        seedAuditRow("agent-export-b", "CREATE", "dave",  "export-org");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/audit-logs/export?username=carol"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        String contentType = response.getHeaders().getFirst("Content-Type");
        assertNotNull(contentType, "export must declare a Content-Type");
        assertTrue(contentType.startsWith("text/csv"),
                "export must serve text/csv; got " + contentType);

        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition, "export must set Content-Disposition for save-as");
        assertTrue(disposition.contains("attachment"),
                "Content-Disposition must mark response as attachment; got " + disposition);
        assertTrue(disposition.contains("audit-logs-"),
                "Content-Disposition filename must follow audit-logs-<date>.csv pattern; got "
                        + disposition);

        String body = response.getBody();
        assertNotNull(body, "CSV body must not be null");
        String[] lines = body.split("\\r\\n");
        assertEquals("createdAt,id,agentId,action,username,versionNumber,changeset", lines[0],
                "first line must be the schema header (RFC-4180, CRLF-terminated)");
        // username=carol filter → 2 data rows + 1 header = 3 lines.
        assertEquals(3, lines.length,
                "username=carol filter must yield exactly 2 data rows under the header");
        assertTrue(body.contains("carol"), "CSV must include the carol-filtered rows");
        assertFalse(body.contains("dave"),
                "CSV with username=carol filter must NOT include dave's row");
    }

    // (Gap pin removed — agent_audits is now append-only via trg_agent_audits_immutable
    // (changeset 029). The active assertion lives in auditRowsAreImmutable below.)

    // (Gap pin removed — DataRetentionService now purges audit rows via
    // deleteByCreatedAtBefore(auditCutoff). The ideal assertion lives in
    // dataRetentionShouldPurgeOldAuditRows below.)

    // §24 — Case 1 ideal (matrix): every controller mutation (not just agent CRUD) produces
    // exactly one audit row. Covered by the cross-cutting SystemAuditInterceptor which writes
    // to the generalized {@code system_audits} table on every successful POST/PUT/PATCH/DELETE
    // against non-agent admin paths. Here we exercise the USER surface as a canonical non-agent
    // case; {@code SystemAuditRuntimeTest} covers the broader breakdown (LOGOUT, org-scoping,
    // resource-type coverage, RBAC).
    @Test
    void nonAgentMutationsShouldAlsoProduceAuditRows() {
        // /api/admin/users is ROLE_ADMIN-gated; authenticatedHeaders() alone only grants
        // ROLE_USER, which returns 403. Register directly as an admin here.
        HttpHeaders auth = authenticateAs("cross-cutting-admin",
                "cross-cutting-admin@test.local", "pass-audit-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        Map<String, Object> createReq = new HashMap<>();
        createReq.put("username", "nonagent-target");
        createReq.put("email", "nonagent-target@test.local");
        createReq.put("password", "pass-target-1234");
        createReq.put("roles", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(createReq, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "POST /api/admin/users must create the user; status was " + created.getStatusCode());

        // afterCompletion runs after the response commits and opens a REQUIRES_NEW audit
        // transaction — spin briefly for the row to land.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'USER'
                               AND action = 'CREATE'
                               AND username = 'cross-cutting-admin'
                            """, Long.class);
                    assertEquals(1L, count == null ? 0L : count,
                            "SystemAuditInterceptor must write exactly one system_audits row per non-agent mutation (USER/CREATE by cross-cutting-admin)");
                });
    }

    // §24 — Case 2 (matrix): authentication events (login success/fail, logout) produce audit
    // rows. Covered by {@link ai.operativus.agentmanager.control.controller.AuthController},
    // which calls {@code SystemAuditService.record} on all three branches directly — the
    // interceptor cannot see login failures because {@code authenticate()} throws before any
    // HandlerInterceptor#afterCompletion fires on the /api/auth/login handler. Success + failure
    // coverage lives here; LOGOUT has its own case in {@code SystemAuditRuntimeTest}.
    @Test
    void authEventsShouldProduceAuditRows() {
        // Success: authenticatedHeaders() calls POST /api/auth/register then POST /api/auth/login,
        // so a LOGIN_SUCCESS row must exist for the registered user.
        authenticatedHeaders("auth-event-user");

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long ok = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'AUTH'
                               AND action = 'LOGIN_SUCCESS'
                               AND username = 'auth-event-user'
                            """, Long.class);
                    assertEquals(1L, ok == null ? 0L : ok,
                            "POST /api/auth/login (success) must write a LOGIN_SUCCESS row to system_audits");
                });

        // Failure: same username, wrong password → 401 → LOGIN_FAILURE row, no principal bound.
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        Map<String, Object> badLogin = new HashMap<>();
        badLogin.put("username", "auth-event-user");
        badLogin.put("password", "wrong-password");
        ResponseEntity<String> failed = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(badLogin, jsonHeaders),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, failed.getStatusCode(),
                "bad password must return 401; got " + failed.getStatusCode());

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(50))
                .untilAsserted(() -> {
                    // Updated for username-sanitization: LOGIN_FAILURE rows now record the
                    // literal '<authentication-failed>' to defeat enumeration. See
                    // LoginFailureUsernameSanitizationRuntimeTest for the focused pin.
                    Long failures = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'AUTH'
                               AND action = 'LOGIN_FAILURE'
                               AND username = '<authentication-failed>'
                            """, Long.class);
                    assertEquals(1L, failures == null ? 0L : failures,
                            "bad-password login attempt must write a LOGIN_FAILURE row to system_audits keyed by the sanitized placeholder");
                });
    }

    // §24 — Case 4: agent_audits is append-only. The trg_agent_audits_immutable trigger
    // (changeset 029) raises a restrict_violation on any UPDATE or DELETE from a caller
    // that did not set agm.audit_immutability_bypass='true' on the current transaction.
    // Retention purge (DataRetentionService) and GDPR redaction (AuditErasureHandler) are
    // the only authorized mutation paths — both tested below and in ConfigRuntimeTest.
    @Test
    void auditRowsAreImmutable() {
        String agentId = "agent-immutable-" + shortUuid();
        seedAuditRow(agentId, "CREATE", "before-mutation");

        DataAccessException updateEx = assertThrows(DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE agent_audits SET username = ? WHERE agent_id = ?",
                        "after-mutation", agentId),
                "UPDATE on agent_audits must be rejected by trg_agent_audits_immutable");
        assertTrue(updateEx.getMostSpecificCause().getMessage().contains("append-only"),
                "trigger error message must mention append-only; got: " + updateEx.getMostSpecificCause().getMessage());

        String usernameAfter = jdbc.queryForObject(
                "SELECT username FROM agent_audits WHERE agent_id = ?", String.class, agentId);
        assertEquals("before-mutation", usernameAfter,
                "row must retain its original username after the rejected UPDATE");

        DataAccessException deleteEx = assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM agent_audits WHERE agent_id = ?", agentId),
                "DELETE on agent_audits must be rejected by trg_agent_audits_immutable");
        assertTrue(deleteEx.getMostSpecificCause().getMessage().contains("append-only"),
                "trigger error message must mention append-only; got: " + deleteEx.getMostSpecificCause().getMessage());

        Long surviving = jdbc.queryForObject(
                "SELECT count(*) FROM agent_audits WHERE agent_id = ?", Long.class, agentId);
        assertEquals(1L, surviving,
                "row must survive the rejected DELETE");
    }

    // §24 — Case 5 ideal (flipped): DataRetentionService.enforceRetentionPolicies() must purge
    // agent_audits rows older than app.retention.audit-days (default 365) and report the count
    // in the returned `purged` map. We seed a 400-day-old row (past the cutoff) and a fresh row
    // (inside the window), tick retention via SchedulerTestSupport, and assert only the stale
    // row was removed.
    @SuppressWarnings("deprecation") // retention test bypasses API; org_id irrelevant for cutoff sweep
    @Test
    void dataRetentionShouldPurgeOldAuditRows() {
        String agentId = "agent-retention-" + shortUuid();
        seedAgentRow(agentId);

        // Retention deletes by created_at, not org_id — the deprecated 4-arg ctor is fine here.
        AgentAuditEntity stale = new AgentAuditEntity(agentId, "CREATE", "stale-actor", "{\"seeded\":true}");
        stale.setCreatedAt(LocalDateTime.now().minusDays(400));
        auditRepository.save(stale);

        AgentAuditEntity fresh = new AgentAuditEntity(agentId, "CREATE", "fresh-actor", "{\"seeded\":true}");
        fresh.setCreatedAt(LocalDateTime.now().minusDays(1));
        auditRepository.save(fresh);

        assertTrue(auditRepository.findById(stale.getId()).isPresent(),
                "precondition: 400-day-old audit row must exist before the retention sweep");
        assertTrue(auditRepository.findById(fresh.getId()).isPresent(),
                "precondition: fresh 1-day-old audit row must exist before the retention sweep");

        scheduler.tickDataRetention();

        assertTrue(auditRepository.findById(stale.getId()).isEmpty(),
                "post-condition: 400-day-old audit row must be purged by DataRetentionService");
        assertTrue(auditRepository.findById(fresh.getId()).isPresent(),
                "post-condition: 1-day-old audit row must survive the retention sweep (within window)");
    }

    // §24 case 1 DELETE half — AgentAdminService.deleteAgent now passes the
    // AgentPermissionEvaluator for a same-org admin caller and the soft-delete path reaches
    // logAudit. The audit row carries action="DELETE" and the changeset="{}" fallback
    // because no prior state is captured on delete.
    @Test
    void deleteAgentShouldAlsoProduceAuditRow() {
        HttpHeaders auth = adminHeaders("audit-delete-actor");

        String agentId = "agent-audit-" + shortUuid();

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(minimalAgentBody(agentId, "Audit Delete Me"), auth),
                AGENT_TYPE);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        ResponseEntity<String> deleted = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "same-org admin must reach the soft-delete branch after the PermissionEvaluator check");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, username, changeset
                FROM agent_audits
                WHERE agent_id = ?
                ORDER BY created_at
                """, agentId);
        assertEquals(2, rows.size(),
                "agent CREATE + DELETE must produce exactly 2 audit rows");

        List<String> actions = rows.stream().map(r -> (String) r.get("action")).toList();
        assertTrue(actions.contains("CREATE"), "audit rows must include CREATE action; got " + actions);
        assertTrue(actions.contains("DELETE"), "audit rows must include DELETE action; got " + actions);

        Map<String, Object> deleteRow = rows.stream()
                .filter(r -> "DELETE".equals(r.get("action")))
                .findFirst()
                .orElseThrow();
        assertEquals("audit-delete-actor", deleteRow.get("username"),
                "DELETE audit row must record the authenticated username, not the SYSTEM_PRINCIPAL fallback");
        assertNotNull(deleteRow.get("changeset"),
                "DELETE audit row must carry a non-null changeset (the logAudit fallback writes '{}')");
    }

    // §24 — Case 6 (matrix): audit log of org A never visible to admin of org B. Fix B
    // enforces this via an EXISTS subquery through agents.org_id — no schema migration needed.
    @Test
    void auditLogShouldBeOrgScoped() {
        HttpHeaders orgAAuth = registerLoginWithOrg("audit-scope-admin-A", "org-A");
        HttpHeaders orgBAuth = registerLoginWithOrg("audit-scope-admin-B", "org-B");

        seedAuditRow("agent-org-a-1", "CREATE", "alice", "org-A");
        seedAuditRow("agent-org-a-2", "UPDATE", "alice", "org-A");
        seedAuditRow("agent-org-b-1", "CREATE", "bob",   "org-B");

        assertEquals(2, totalElements(fetchAuditPage(orgAAuth, "")),
                "org-A admin must see exactly 2 org-A audit rows — the 1 org-B row must be invisible");

        assertEquals(1, totalElements(fetchAuditPage(orgBAuth, "")),
                "org-B admin must see exactly 1 org-B audit row — the 2 org-A rows must be invisible");
    }

    // §24 — Case 6 CSV: same org isolation must hold on the /export endpoint.
    @Test
    void exportAuditLogsCsvShouldBeOrgScoped() {
        HttpHeaders orgAAuth = registerLoginWithOrg("export-scope-admin-A", "org-A");
        HttpHeaders orgBAuth = registerLoginWithOrg("export-scope-admin-B", "org-B");

        seedAuditRow("agent-export-a", "CREATE", "alice", "org-A");
        seedAuditRow("agent-export-b", "CREATE", "bob",   "org-B");

        ResponseEntity<String> csvA = rest.exchange(
                url("/api/admin/audit-logs/export"),
                HttpMethod.GET,
                new HttpEntity<>(orgAAuth),
                String.class);
        assertEquals(HttpStatus.OK, csvA.getStatusCode(),
                "org-A export must succeed; status was " + csvA.getStatusCode());
        assertTrue(csvA.getBody().contains("agent-export-a"),
                "org-A CSV must contain the org-A agent row");
        assertFalse(csvA.getBody().contains("agent-export-b"),
                "org-A CSV must not contain the org-B agent row");

        ResponseEntity<String> csvB = rest.exchange(
                url("/api/admin/audit-logs/export"),
                HttpMethod.GET,
                new HttpEntity<>(orgBAuth),
                String.class);
        assertEquals(HttpStatus.OK, csvB.getStatusCode(),
                "org-B export must succeed; status was " + csvB.getStatusCode());
        assertFalse(csvB.getBody().contains("agent-export-a"),
                "org-B CSV must not contain the org-A agent row");
        assertTrue(csvB.getBody().contains("agent-export-b"),
                "org-B CSV must contain the org-B agent row");
    }

    // Fix C — T009: backfill correctness invariant. Pinned for the lifetime of the suite —
    // every agent_audits row whose agent_id references a still-existing agent must carry a
    // populated org_id (matching that agent's org_id). Catches future regressions where any
    // migration or write-path bug could re-introduce NULL-org_id rows for live agents.
    //
    // Note on orphan-row contract: in production an "orphan" row would be one whose agent
    // was hard-deleted after the audit row was written. Since agent_audits.agent_id has an
    // FK to agents(id) (no cascade), this can only happen via an explicit bypass path
    // (DataRetentionService / AuditErasureHandler) or pre-existing inconsistent rows from
    // before the FK was added. Simulating that state in a runtime test would require FK
    // manipulation, which is out of scope. The spec's Edge Case for orphan rows is satisfied
    // by the migration's UPDATE ... FROM excluding non-matching rows (verified by inspecting
    // changeset 052) and the API listing predicate (a.orgId = :orgId) excluding NULL-org rows.
    @Test
    void backfillCorrectnessInvariant_noLiveAgentHasNullOrgIdAuditRow() {
        // Seed 3 agents — 2 in org-A and 1 in org-B — plus audit rows through the new
        // 5-arg ctor (Fix C write path).
        seedAuditRow("agent-bf-a1", "CREATE", "alice", "org-A");
        seedAuditRow("agent-bf-a1", "UPDATE", "alice", "org-A");
        seedAuditRow("agent-bf-a2", "CREATE", "alice", "org-A");
        seedAuditRow("agent-bf-b1", "CREATE", "bob",   "org-B");

        // Backfill correctness — every audit row whose parent agent still exists has a
        // non-null org_id. Enforces the Fix C migration's central invariant.
        Integer liveNullCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_audits aa
                 WHERE aa.org_id IS NULL
                   AND aa.agent_id IN (SELECT id FROM agents)
                """, Integer.class);
        assertEquals(0, liveNullCount,
                "Fix C invariant: every agent_audits row whose parent agent still exists must have org_id populated");

        // Per-row org_id matches the parent agent's org_id (no transposition or NULL writes).
        Integer mismatchCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_audits aa
                 INNER JOIN agents a ON a.id = aa.agent_id
                 WHERE aa.org_id IS DISTINCT FROM a.org_id
                """, Integer.class);
        assertEquals(0, mismatchCount,
                "Fix C invariant: every audit row's org_id must match the parent agent's org_id");

        // Org-isolation through the API holds for the seeded rows.
        HttpHeaders orgAAuth = registerLoginWithOrg("backfill-admin-a", "org-A");
        HttpHeaders orgBAuth = registerLoginWithOrg("backfill-admin-b", "org-B");
        assertEquals(3, totalElements(fetchAuditPage(orgAAuth, "")),
                "org-A admin must see all 3 org-A audit rows");
        assertEquals(1, totalElements(fetchAuditPage(orgBAuth, "")),
                "org-B admin must see exactly 1 org-B audit row");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-audit-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-audit-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pageMeta(Map<String, Object> body) {
        Map<String, Object> page = (Map<String, Object>) body.get("page");
        assertNotNull(page, "Spring Boot 4 Page response must carry a 'page' metadata object; body was " + body.keySet());
        return page;
    }

    private int totalElements(Map<String, Object> body) {
        return ((Number) pageMeta(body).get("totalElements")).intValue();
    }

    private Map<String, Object> fetchAuditPage(HttpHeaders auth, String queryString) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/audit-logs" + queryString),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "GET /api/admin/audit-logs" + queryString + " must succeed; got " + resp.getStatusCode());
        assertNotNull(resp.getBody(), "response body must be present");
        return resp.getBody();
    }

    /**
     * Seeds an audit row referencing {@code agentId} without an {@code org_id}. Use only
     * for tests that never query via the list API (e.g. immutability and retention tests
     * that assert directly against the DB or repository). The agent row will be excluded
     * by the EXISTS org-scope filter in the API path.
     */
    @SuppressWarnings("deprecation") // intentional: orphan-row simulation (NULL org_id)
    private void seedAuditRow(String agentId, String action, String username) {
        seedAgentRow(agentId);
        // Deliberately uses the deprecated 4-arg ctor — leaves org_id NULL to simulate
        // orphan / pre-Fix-C rows. Used only by tests that bypass the org-scoped API path
        // (retention sweep, immutability assertions on direct repo access).
        AgentAuditEntity row = new AgentAuditEntity(agentId, action, username, "{\"seeded\":true}");
        auditRepository.save(row);
    }

    /**
     * Seeds an audit row with an org-scoped agent + org-stamped audit row (Fix C).
     * Use this for API-facing tests where the {@code /api/admin/audit-logs} endpoint's
     * direct-equality predicate ({@code a.orgId = :orgId}) must match.
     */
    private void seedAuditRow(String agentId, String action, String username, String orgId) {
        seedAgentRowWithOrg(agentId, orgId);
        AgentAuditEntity row = new AgentAuditEntity(agentId, orgId, action, username, "{\"seeded\":true}");
        auditRepository.save(row);
    }

    private void seedAgentRow(String agentId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'fake-audit-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "Audit Test Agent " + agentId);
    }

    private void seedAgentRowWithOrg(String agentId, String orgId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'fake-audit-model', true, ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "Audit Test Agent " + agentId, orgId);
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    /**
     * Minimal AgentDefinition body. Same shape as {@code AgentsCrudRuntimeTest#minimalAgentBody}.
     */
    private Map<String, Object> minimalAgentBody(String agentId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Audit test agent");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "fake-audit-model");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        return body;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
