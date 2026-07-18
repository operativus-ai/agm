package com.operativus.agentmanager.integration.admin;

import tools.jackson.databind.JsonNode;
import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box HTTP coverage for {@code GET /api/admin/system-audit-logs}.
 *   Verifies that the endpoint returns audit rows scoped to the caller's org, honours the
 *   {@code action} filter param, enforces tenant isolation between orgs, and handles
 *   out-of-bounds pagination gracefully. Authz gate (401/403) is covered by the project-wide
 *   {@link com.operativus.agentmanager.integration.security.AdminEndpointAuthzRuntimeTest}.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SystemAuditLogRuntimeTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> listAuditLogs(HttpHeaders auth, String queryString) {
        String path = "/api/admin/system-audit-logs" + (queryString != null ? "?" + queryString : "");
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
    }

    // --- tests ---

    @Test
    void listReturnsLoginSuccessRowForCallerOrg() {
        String orgId = UUID.randomUUID().toString();
        // registerLoginWithOrg generates a LOGIN_SUCCESS audit row on the login call
        HttpHeaders auth = registerLoginWithOrg("audit-happy", orgId);

        ResponseEntity<JsonNode> response = listAuditLogs(auth, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertTrue(body.path("page").path("totalElements").asLong() >= 1,
                "Expected at least one audit row (LOGIN_SUCCESS) for the caller's org");

        JsonNode firstRow = body.path("content").get(0);
        assertEquals("LOGIN_SUCCESS", firstRow.path("action").asText(),
                "Most-recent row should be LOGIN_SUCCESS from the login call");
        assertEquals("audit-happy", firstRow.path("username").asText());
    }

    @Test
    void actionFilterReturnsOnlyMatchingRows() {
        String orgId = UUID.randomUUID().toString();
        HttpHeaders auth = registerLoginWithOrg("audit-filter", orgId);

        // Seed a second row with a distinct action so we can confirm the filter excludes it
        jdbc.update("""
                INSERT INTO system_audits (id, org_id, username, action, resource_type)
                VALUES (gen_random_uuid()::text, ?, 'audit-filter', 'AGENT_CREATED', 'AGENT')
                """, orgId);

        // Unfiltered — should see both rows
        ResponseEntity<JsonNode> unfilteredResp = listAuditLogs(auth, null);
        assertEquals(HttpStatus.OK, unfilteredResp.getStatusCode());
        long totalUnfiltered = unfilteredResp.getBody().path("page").path("totalElements").asLong();
        assertTrue(totalUnfiltered >= 2,
                "Expected at least 2 rows (LOGIN_SUCCESS + AGENT_CREATED) before filtering");

        // Filtered to LOGIN_SUCCESS only
        ResponseEntity<JsonNode> filteredResp = listAuditLogs(auth, "action=LOGIN_SUCCESS");
        assertEquals(HttpStatus.OK, filteredResp.getStatusCode());
        JsonNode filteredBody = filteredResp.getBody();
        long totalFiltered = filteredBody.path("page").path("totalElements").asLong();
        assertTrue(totalFiltered < totalUnfiltered,
                "Filtered result should have fewer rows than unfiltered");
        filteredBody.path("content").forEach(row ->
                assertEquals("LOGIN_SUCCESS", row.path("action").asText(),
                        "Every row in filtered response must have action=LOGIN_SUCCESS"));
    }

    @Test
    void crossTenantRowsAreInvisibleToOtherOrgAdmin() {
        String orgA = UUID.randomUUID().toString();
        String orgB = UUID.randomUUID().toString();

        HttpHeaders authA = registerLoginWithOrg("audit-org-a", orgA);
        // Login for org B also seeds a LOGIN_SUCCESS row under orgB
        registerLoginWithOrg("audit-org-b", orgB);

        // Seed an additional explicit row for org B
        jdbc.update("""
                INSERT INTO system_audits (id, org_id, username, action, resource_type)
                VALUES (gen_random_uuid()::text, ?, 'audit-org-b', 'AGENT_DELETED', 'AGENT')
                """, orgB);

        // Org A admin queries — should see only org A's rows
        ResponseEntity<JsonNode> responseA = listAuditLogs(authA, null);
        assertEquals(HttpStatus.OK, responseA.getStatusCode());

        JsonNode content = responseA.getBody().path("content");
        content.forEach(row -> assertEquals(orgA, row.path("orgId").asText(),
                "Org A admin must not see rows belonging to org B"));

        // Confirm org B rows are present in the DB but absent from org A's view
        int orgBRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE org_id = ?", Integer.class, orgB);
        assertTrue(orgBRows >= 2, "Org B must have rows in the DB (LOGIN_SUCCESS + AGENT_DELETED)");

        int totalDbRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits", Integer.class);
        long orgAVisible = responseA.getBody().path("page").path("totalElements").asLong();
        assertTrue(totalDbRows > orgAVisible,
                "Total DB rows (" + totalDbRows + ") must exceed org A's visible count ("
                        + orgAVisible + "); isolation is not filtering org B rows");
    }

    @Test
    void pageOutOfBoundsReturnsEmptyContent() {
        String orgId = UUID.randomUUID().toString();
        HttpHeaders auth = registerLoginWithOrg("audit-page-oob", orgId);

        // Requesting a very high page number should return 200 with empty content, not an error
        ResponseEntity<JsonNode> response = listAuditLogs(auth, "page=9999&size=20");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertTrue(body.path("content").isEmpty(),
                "Page 9999 should return empty content, not an error");
    }
}
