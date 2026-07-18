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
 * Domain Responsibility: Pins the {@code ChangesetScrubber} wiring on the audit-log read
 *   paths. The scrubber masks secret-key values (apikey, password, token, credentials, etc.)
 *   with {@code "***"} via {@code ChangesetScrubber.DEFAULT_SECRET_KEYS}.
 *
 *   <p><b>Important asymmetry pinned by this test</b>: today the scrubber is applied on
 *   the <em>CSV export</em> path ({@code AuditLogService:70}) but NOT on the JSON list
 *   path. A CSV download masks {@code api_key} → {@code "***"}; a JSON list returns the
 *   raw {@code "sk-secret"} value. This is a documented gap worth surfacing.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>CSV export masks {@code api_key}</b> — happy-path scrubber contract. If this
 *         fails, the scrubber is no longer invoked on the export path → PII exfiltration
 *         risk.</li>
 *     <li><b>JSON list does NOT mask {@code api_key}</b> — pins the current asymmetry.
 *         If the JSON list starts masking too, this test flips and we celebrate (the gap
 *         was closed). Until then, the test makes the gap visible and surveys-friendly.</li>
 *     <li><b>CSV masks nested secrets</b> — {@code {"config":{"password":"p"}}} → nested
 *         password masked. Pins recursive scrubber behavior.</li>
 *     <li><b>CSV passes non-secret fields verbatim</b> — {@code {"name":"foo"}} → "foo"
 *         survives. Pins the scrubber is non-destructive for non-secret keys.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AuditChangesetScrubberCsvExportRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-scrubber-admin",
                "audit-scrubber-admin@test.local", "pass-asa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void csvExportMasksApiKeyInChangeset() {
        String agentId = "scrubber-agent-" + UUID.randomUUID();
        seedAgent(agentId);
        seedAuditWithChangeset(agentId,
                "{\"api_key\":\"sk-leaked-secret-DO-NOT-LEAK\",\"name\":\"keep-me\"}");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/audit-logs/export"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains("sk-leaked-secret-DO-NOT-LEAK"),
                "CSV export must NOT contain the raw api_key value — ChangesetScrubber "
                        + "must be invoked on the export path. If this fails, "
                        + "AuditLogService.toCsv stopped calling changesetScrubber.scrub(). "
                        + "Body snippet: " + body.substring(0, Math.min(body.length(), 500)));
        assertTrue(body.contains("***"),
                "CSV export must contain '***' (the mask token) for the api_key field; "
                        + "body snippet: " + body.substring(0, Math.min(body.length(), 500)));
        assertTrue(body.contains("keep-me"),
                "CSV export must preserve non-secret field values verbatim ('keep-me'); "
                        + "got: " + body.substring(0, Math.min(body.length(), 500)));
    }

    @Test
    void csvExportMasksNestedPasswordInChangeset() {
        String agentId = "scrubber-nested-" + UUID.randomUUID();
        seedAgent(agentId);
        seedAuditWithChangeset(agentId,
                "{\"name\":\"x\",\"config\":{\"password\":\"deep-secret-pw-DO-NOT-LEAK\"}}");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/audit-logs/export"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertFalse(body.contains("deep-secret-pw-DO-NOT-LEAK"),
                "CSV export must mask nested-object secrets recursively; got: "
                        + body.substring(0, Math.min(body.length(), 500)));
        assertTrue(body.contains("***"),
                "CSV export must contain '***' for the nested password");
    }

    @Test
    void jsonListPathDoesNotScrubApiKeyTodayKnownAsymmetry() {
        String agentId = "scrubber-json-" + UUID.randomUUID();
        seedAgent(agentId);
        seedAuditWithChangeset(agentId,
                "{\"api_key\":\"sk-json-path-still-leaks\",\"name\":\"y\"}");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/audit-logs?page=0&size=10"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Serialize the whole response back to string and search for the unmasked value.
        // The asymmetry is now CLOSED — the scrubber is wired into the JSON list path too
        // (not just the CSV export), so the api_key value is masked (***) here as well.
        String responseStr = response.getBody().toString();
        assertFalse(responseStr.contains("sk-json-path-still-leaks"),
                "JSON list path now scrubs changeset secrets (api_key masked to ***), matching "
                        + "the CSV export. The previously-tolerated leak is closed. "
                        + "Got: " + responseStr.substring(0, Math.min(responseStr.length(), 500)));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'test-instructions', NULL, true, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, id, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private void seedAuditWithChangeset(String agentId, String changesetJson) {
        // agent_audits is append-only — direct INSERTs are fine (trigger only blocks
        // UPDATE/DELETE without the bypass flag).
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset,
                                          version_number, created_at)
                VALUES (?, ?, ?, 'UPDATE', 'scrubber-fixture', ?::jsonb, 1, NOW())
                """, "audit-" + UUID.randomUUID(), agentId,
                TenantConstants.DEFAULT_SYSTEM_ORG, changesetJson);
    }
}
