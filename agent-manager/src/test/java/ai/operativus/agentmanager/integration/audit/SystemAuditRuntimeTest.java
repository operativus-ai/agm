package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.awaitility.Awaitility;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the generalized cross-cutting audit log
 *   — the {@code system_audits} table, the write paths (SystemAuditInterceptor for non-agent
 *   HTTP mutations + AuthController for LOGIN/LOGOUT/REGISTER), and the admin read surface at
 *   {@code /api/admin/system-audit-logs}. Exercises matrix §24 cases 1 (non-agent mutations),
 *   2 (auth events), and 6 (org-scoped visibility).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §24 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T046.
 *
 * Complementary to {@link AuditLogsRuntimeTest}, which pins the agent-specific {@code agent_audits}
 * surface driven by {@link ai.operativus.agentmanager.control.service.AgentAdminService#logAudit}.
 * Agent CRUD paths (/api/admin/agents/**) are explicitly SKIPPED by SystemAuditInterceptor so the
 * two tables never double-count.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // §24 — Case 1 (USER/UPDATE path): PUT /api/admin/users/{id} must also produce exactly one
    // system_audits row with resource_type=USER, action=UPDATE, resource_id={id}. Complements
    // AuditLogsRuntimeTest#nonAgentMutationsShouldAlsoProduceAuditRows which exercises CREATE.
    @Test
    void userUpdateWritesExactlyOneSystemAuditRow() {
        HttpHeaders adminAuth = defaultAdmin("update-admin");

        Map<String, Object> createReq = new HashMap<>();
        createReq.put("username", "update-target");
        createReq.put("email", "update-target@test.local");
        createReq.put("password", "pass-target-1234");
        createReq.put("roles", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(createReq, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String targetId = created.getBody().get("id").toString();

        Map<String, Object> updateReq = new HashMap<>();
        updateReq.put("email", "update-target-new@test.local");
        updateReq.put("roles", List.of("ROLE_USER"));
        updateReq.put("disabled", false);

        ResponseEntity<Map<String, Object>> updated = rest.exchange(
                url("/api/admin/users/" + targetId),
                HttpMethod.PUT,
                new HttpEntity<>(updateReq, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, updated.getStatusCode());

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long updates = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'USER'
                               AND action = 'UPDATE'
                               AND resource_id = ?
                               AND username = 'update-admin'
                            """, Long.class, targetId);
                    assertEquals(1L, updates == null ? 0L : updates,
                            "PUT /api/admin/users/{id} must produce exactly one UPDATE row keyed by {id}");
                });
    }

    // §24 — Case 1 (USER/DELETE path): DELETE /api/admin/users/{id} returns 204 and must still
    // produce a system_audits row with action=DELETE.
    @Test
    void userDeleteWritesSystemAuditRow() {
        HttpHeaders adminAuth = defaultAdmin("delete-admin");

        Map<String, Object> createReq = new HashMap<>();
        createReq.put("username", "delete-target");
        createReq.put("email", "delete-target@test.local");
        createReq.put("password", "pass-delete-1234");
        createReq.put("roles", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(createReq, adminAuth),
                JSON_MAP);
        String targetId = created.getBody().get("id").toString();

        ResponseEntity<Void> deleted = rest.exchange(
                url("/api/admin/users/" + targetId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode(),
                "DELETE /api/admin/users/{id} must return 204");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long deletes = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'USER'
                               AND action = 'DELETE'
                               AND resource_id = ?
                            """, Long.class, targetId);
                    assertEquals(1L, deletes == null ? 0L : deletes,
                            "DELETE must write exactly one DELETE row; 204 is still a 2xx success");
                });
    }

    // §24 — Case 3 (filter path): /api/admin/system-audit-logs returns a paginated list scoped
    // to the caller's org_id and filters by username/action/resourceType/resourceId.
    @Test
    void systemAuditLogEndpointFiltersAndPaginates() {
        HttpHeaders auth = defaultAdmin("filter-admin");

        // Seed two USER CREATE events by creating two downstream users.
        for (String target : List.of("filter-target-a", "filter-target-b")) {
            Map<String, Object> req = new HashMap<>();
            req.put("username", target);
            req.put("email", target + "@test.local");
            req.put("password", "pass-filter-1234");
            req.put("roles", List.of("ROLE_USER"));
            rest.exchange(url("/api/admin/users"), HttpMethod.POST,
                    new HttpEntity<>(req, auth), JSON_MAP);
        }

        // Wait for both to land.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long count = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'USER' AND action = 'CREATE'
                               AND username = 'filter-admin'
                            """, Long.class);
                    assertTrue(count != null && count >= 2,
                            "expected at least 2 USER CREATE rows; got " + count);
                });

        // Filter by action=CREATE + resource_type=USER.
        ResponseEntity<Map<String, Object>> page = rest.exchange(
                url("/api/admin/system-audit-logs?action=CREATE&resourceType=USER"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, page.getStatusCode(),
                "GET /api/admin/system-audit-logs must succeed; got " + page.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.getBody().get("content");
        assertTrue(content.size() >= 2,
                "filtered listing must include the 2 seeded USER CREATE rows; saw " + content.size());
        for (Map<String, Object> row : content) {
            assertEquals("CREATE", row.get("action"));
            assertEquals("USER", row.get("resourceType"));
        }
    }

    // §24 — Case 6 (matrix): admin of org A cannot see rows from org B. Two users in two orgs
    // each perform a mutation; each admin sees only their own row.
    @Test
    void systemAuditLogIsOrgScopedAdminOfOrgACannotSeeOrgBRows() {
        HttpHeaders orgA = registerLoginWithOrg("admin-a", "org-A");
        HttpHeaders orgB = registerLoginWithOrg("admin-b", "org-B");

        // admin-a performs a mutation
        createDownstreamUser(orgA, "downstream-a");
        // admin-b performs a mutation
        createDownstreamUser(orgB, "downstream-b");

        // Wait for both to land.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Long total = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM system_audits
                             WHERE resource_type = 'USER' AND action = 'CREATE'
                               AND username IN ('admin-a', 'admin-b')
                            """, Long.class);
                    assertEquals(2L, total == null ? 0L : total,
                            "both admins must have written one USER CREATE row each");
                });

        // admin-a lists → sees only admin-a's row (org-A scoped).
        ResponseEntity<Map<String, Object>> pageA = rest.exchange(
                url("/api/admin/system-audit-logs?resourceType=USER&action=CREATE"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentA = (List<Map<String, Object>>) pageA.getBody().get("content");
        assertTrue(contentA.stream().allMatch(r -> "admin-a".equals(r.get("username"))),
                "admin-a's listing must contain ONLY org-A rows (username=admin-a); got " + contentA);
        assertTrue(contentA.stream().allMatch(r -> "org-A".equals(r.get("orgId"))),
                "every row admin-a sees must be tagged org-A; got " + contentA);

        // admin-b lists → sees only admin-b's row (org-B scoped).
        ResponseEntity<Map<String, Object>> pageB = rest.exchange(
                url("/api/admin/system-audit-logs?resourceType=USER&action=CREATE"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contentB = (List<Map<String, Object>>) pageB.getBody().get("content");
        assertTrue(contentB.stream().allMatch(r -> "admin-b".equals(r.get("username"))),
                "admin-b's listing must contain ONLY org-B rows (username=admin-b); got " + contentB);
        assertTrue(contentB.stream().allMatch(r -> "org-B".equals(r.get("orgId"))),
                "every row admin-b sees must be tagged org-B; got " + contentB);
    }

    // §24 — system-audit-logs is admin-only. ROLE_USER callers must get 403.
    @Test
    void systemAuditLogEndpointRequiresAdmin() {
        HttpHeaders userAuth = authenticateAs("nonadmin", "nonadmin@test.local",
                "pass-nonadmin-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/system-audit-logs"),
                HttpMethod.GET,
                new HttpEntity<>(userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER must get 403 on /api/admin/system-audit-logs; got " + resp.getStatusCode());
    }

    // §24 — GET requests against mutation-capable paths must NOT produce system_audits rows.
    // SystemAuditInterceptor only fires on POST/PUT/PATCH/DELETE with a 2xx response.
    @Test
    void readOnlyRequestsDoNotWriteAuditRows() {
        HttpHeaders auth = defaultAdmin("read-admin");

        long before = jdbc.queryForObject("SELECT COUNT(*) FROM system_audits", Long.class);
        rest.exchange(url("/api/admin/users"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM system_audits", Long.class);
        assertEquals(before, after,
                "GET /api/admin/users must not produce system_audits rows; interceptor only writes on mutations");
    }

    // §2 (agm-left.md) — Global page-size cap: every controller injecting a bare Pageable is
    // clamped at MAX_PAGE_SIZE=200 via PaginationDefaultsConfig. Asserts on body.page.size,
    // which is the Spring Boot 4 / Spring Data 4 page envelope shape (`{content, page:{size,
    // number, totalElements, totalPages}}`). SystemAuditLogController.list() is the witness;
    // the cap is global and applies to every other Pageable-injecting controller as well.
    @Test
    void paginatedAdminListClampsAtGlobalMaxPageSize() {
        HttpHeaders auth = defaultAdmin("page-cap-admin");

        ResponseEntity<Map<String, Object>> honored = rest.exchange(
                url("/api/admin/system-audit-logs?size=50"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, honored.getStatusCode());
        assertEquals(50, pageSize(honored.getBody()),
                "size=50 is below the cap and must be honored as-is");

        ResponseEntity<Map<String, Object>> clamped = rest.exchange(
                url("/api/admin/system-audit-logs?size=10000"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, clamped.getStatusCode());
        assertEquals(200, pageSize(clamped.getBody()),
                "size=10000 must be clamped to the global MAX_PAGE_SIZE=200");
    }

    @SuppressWarnings("unchecked")
    private static int pageSize(Map<String, Object> body) {
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        return ((Number) pageMeta.get("size")).intValue();
    }

    // ─── helpers ───

    private HttpHeaders defaultAdmin(String username) {
        return authenticateAs(username, username + "@test.local", "pass-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private void createDownstreamUser(HttpHeaders asAdmin, String targetUsername) {
        Map<String, Object> req = new HashMap<>();
        req.put("username", targetUsername);
        req.put("email", targetUsername + "@test.local");
        req.put("password", "pass-downstream-1234");
        req.put("roles", List.of("ROLE_USER"));
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(req, asAdmin),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "downstream user creation must succeed; status=" + resp.getStatusCode());
    }
}
