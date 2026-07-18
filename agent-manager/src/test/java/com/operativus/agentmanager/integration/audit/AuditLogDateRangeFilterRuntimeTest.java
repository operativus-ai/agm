package com.operativus.agentmanager.integration.audit;

import com.operativus.agentmanager.core.model.TenantConstants;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the new {@code createdAtFrom}/{@code createdAtTo} date-range
 *   filter on {@code GET /api/admin/audit-logs} and {@code /export}. Compliance queries
 *   ("show me audit activity for user X between Dec 1 and Dec 15") need this surface.
 *
 *   <p>Semantics: {@code createdAtFrom} is inclusive; {@code createdAtTo} is exclusive.
 *   The {@code [from, to)} half-open interval matches the standard compliance-query
 *   convention where {@code [2026-01-01, 2026-02-01)} captures all of January without
 *   ambiguity around the boundary.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>From-only filter</b> returns rows on/after the bound</li>
 *     <li><b>To-only filter</b> returns rows strictly before the bound</li>
 *     <li><b>From+to combined</b> returns rows in the half-open interval</li>
 *     <li><b>CSV export honors the same filter</b></li>
 *     <li><b>No date filter</b> returns all rows (backwards-compat)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AuditLogDateRangeFilterRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;
    private String agentId;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-daterange-admin",
                "audit-daterange-admin@test.local", "pass-ada-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        agentId = "daterange-agent-" + UUID.randomUUID();
        seedAgent(agentId);

        // Seed three audit rows at distinct fixed timestamps.
        seedAudit(agentId, "OLDER",  LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        seedAudit(agentId, "MIDDLE", LocalDateTime.of(2026, 1, 15, 0, 0, 0));
        seedAudit(agentId, "NEWER",  LocalDateTime.of(2026, 2, 1, 0, 0, 0));
    }

    @Test
    void noDateFilterReturnsAllRows() {
        ResponseEntity<Map<String, Object>> response = list("");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, totalElements(response),
                "no date filter must return all 3 seeded rows");
    }

    @Test
    void fromBoundIsInclusiveReturnsRowsOnOrAfterTheBound() {
        // from = 2026-01-15 → MIDDLE + NEWER (2 rows)
        ResponseEntity<Map<String, Object>> response = list(
                "?createdAtFrom=2026-01-15T00:00:00");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, totalElements(response),
                "from=2026-01-15 (inclusive) must return MIDDLE + NEWER (2 rows)");
    }

    @Test
    void toBoundIsExclusiveReturnsRowsStrictlyBeforeTheBound() {
        // to = 2026-02-01 → OLDER + MIDDLE (2 rows; NEWER at exactly 2026-02-01 is excluded)
        ResponseEntity<Map<String, Object>> response = list(
                "?createdAtTo=2026-02-01T00:00:00");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, totalElements(response),
                "to=2026-02-01 (exclusive) must return OLDER + MIDDLE — NEWER at exactly "
                        + "the bound is excluded");
    }

    @Test
    void fromAndToCombinedReturnsHalfOpenInterval() {
        // [2026-01-10, 2026-02-01) → only MIDDLE
        ResponseEntity<Map<String, Object>> response = list(
                "?createdAtFrom=2026-01-10T00:00:00&createdAtTo=2026-02-01T00:00:00");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, totalElements(response),
                "[2026-01-10, 2026-02-01) must contain exactly MIDDLE");
    }

    @Test
    void csvExportHonorsDateRangeFilter() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/audit-logs/export?createdAtFrom=2026-01-15T00:00:00"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        // CSV has 1 header line + N data lines; from=2026-01-15 → 2 rows.
        long dataLineCount = body.lines().count() - 1;
        assertEquals(2, dataLineCount,
                "CSV export with from=2026-01-15 must contain 2 data rows; got "
                        + dataLineCount);
        assertTrue(body.contains("MIDDLE") && body.contains("NEWER"),
                "CSV body must contain MIDDLE + NEWER actions");
        assertTrue(!body.contains("OLDER"),
                "CSV body must NOT contain OLDER (before the from bound)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> list(String queryString) {
        return rest.exchange(
                url("/api/admin/audit-logs" + queryString),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);
    }

    private int totalElements(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) body.get("page");
        assertNotNull(page, "missing nested page metadata");
        return ((Number) page.get("totalElements")).intValue();
    }

    private void seedAgent(String id) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'noop', NULL, true, 1, 'TIER_1_STANDARD', false, 0, ?,
                        NOW(), NOW())
                """, id, id, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private void seedAudit(String agentId, String action, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset,
                                          version_number, created_at)
                VALUES (?, ?, ?, ?, 'fixture', '{}'::jsonb, 1, ?)
                """, "audit-" + UUID.randomUUID(), agentId,
                TenantConstants.DEFAULT_SYSTEM_ORG, action, Timestamp.valueOf(createdAt));
    }
}
