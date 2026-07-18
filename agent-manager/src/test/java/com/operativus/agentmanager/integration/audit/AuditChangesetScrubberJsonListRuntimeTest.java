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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins that the JSON list path of
 *   {@code GET /api/admin/audit-logs} now applies {@link com.operativus.agentmanager.control.service.audit.ChangesetScrubber}
 *   to each row's {@code changeset} field, closing the asymmetry where only the CSV
 *   export path scrubbed. PII fields ({@code api_key}, {@code password}, etc.) in the
 *   serialized JSON response are masked with {@code "***"}.
 *
 *   <p>The {@code @Transactional(readOnly = true)} annotation on
 *   {@link com.operativus.agentmanager.control.service.AuditLogService#listAuditLogs}
 *   ensures the post-read mutation of the entity's {@code changeset} field is NOT
 *   flushed back to the database — Spring sets Hibernate flush mode to MANUAL/NEVER
 *   for read-only transactions. This test pins the DB-row-unchanged invariant alongside
 *   the response-scrubbing contract.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li>JSON list response masks {@code api_key}</li>
 *     <li>JSON list response masks nested-object {@code password}</li>
 *     <li>The on-disk audit row is NOT modified — the scrubber runs on the read path
 *         only; raw values remain in the DB for forensic recovery (and to preserve the
 *         {@code agent_audits} immutability trigger contract).</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AuditChangesetScrubberJsonListRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-json-scrubber-admin",
                "audit-json-scrubber-admin@test.local", "pass-ajsa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void jsonListMasksApiKeyInChangeset() {
        String agentId = "json-scrubber-" + UUID.randomUUID();
        seedAgent(agentId);
        String rawSecret = "sk-JSON-MUST-MASK-DO-NOT-LEAK";
        seedAuditWithChangeset(agentId,
                "{\"api_key\":\"" + rawSecret + "\",\"name\":\"keep-me\"}");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/audit-logs?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody().toString();
        assertFalse(body.contains(rawSecret),
                "JSON list MUST NOT contain raw api_key — ChangesetScrubber must run on "
                        + "this path. Body snippet: "
                        + body.substring(0, Math.min(body.length(), 500)));
        assertTrue(body.contains("***"),
                "JSON list MUST contain '***' (mask token) for the api_key field");
        assertTrue(body.contains("keep-me"),
                "JSON list must preserve non-secret field values verbatim");
    }

    @Test
    void jsonListMasksNestedSecrets() {
        String agentId = "json-nested-" + UUID.randomUUID();
        seedAgent(agentId);
        String rawNested = "nested-pw-DO-NOT-LEAK";
        seedAuditWithChangeset(agentId,
                "{\"name\":\"x\",\"config\":{\"password\":\"" + rawNested + "\"}}");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/audit-logs?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody().toString();
        assertFalse(body.contains(rawNested),
                "JSON list must mask nested secrets recursively; body snippet: "
                        + body.substring(0, Math.min(body.length(), 500)));
    }

    @Test
    void scrubberDoesNotMutateTheOnDiskAuditRow() {
        String agentId = "json-on-disk-" + UUID.randomUUID();
        seedAgent(agentId);
        String rawSecret = "sk-on-disk-must-remain";
        seedAuditWithChangeset(agentId,
                "{\"api_key\":\"" + rawSecret + "\"}");

        // Trigger the read path (which scrubs the in-memory entity).
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/audit-logs?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        // The on-disk row must still contain the raw value. The @Transactional(readOnly=true)
        // on listAuditLogs prevents the mutated entity from flushing.
        String dbValue = jdbc.queryForObject(
                "SELECT changeset FROM agent_audits WHERE agent_id = ?",
                String.class, agentId);
        assertTrue(dbValue.contains(rawSecret),
                "scrubbing the JSON response must NOT mutate the on-disk audit row. "
                        + "Raw secret must remain in the DB for forensic recovery. "
                        + "On-disk value was: " + dbValue);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'noop', NULL, true, 1, 'TIER_1_STANDARD', false, 0, ?,
                        NOW(), NOW())
                """, id, id, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private void seedAuditWithChangeset(String agentId, String changesetJson) {
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset,
                                          version_number, created_at)
                VALUES (?, ?, ?, 'UPDATE', 'scrubber-fixture', ?::jsonb, 1, NOW())
                """, "audit-" + UUID.randomUUID(), agentId,
                TenantConstants.DEFAULT_SYSTEM_ORG, changesetJson);
    }
}
