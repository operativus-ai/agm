package com.operativus.agentmanager.integration.agents;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins the export → import round-trip for
 *   {@code GET /api/admin/agents/{id}/export} and {@code POST /api/admin/agents/import}.
 *   These endpoints implement a portable "backup-and-restore" surface for agent
 *   configurations across environments.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Round-trip preserves all key fields</b> — exporting an agent, mutating only
 *         the id, and re-importing produces a new agent whose JSON-visible fields
 *         (name, description, instructions, model) match the source.</li>
 *     <li><b>Import writes an IMPORT audit row</b> — auditing requirement: every imported
 *         agent must produce an {@code agent_audits} row with action=IMPORT. Critical for
 *         compliance / forensics.</li>
 *     <li><b>Import with missing id → 400</b> — service-layer guard, surfaces via
 *         BusinessValidationException.</li>
 *   </ul>
 *
 *   <p>Not covered here (deferred): cross-tenant orgId isolation on import — the service
 *   sets {@code entity.setOrgId(callerOrgId())}, so a malicious payload with a fake orgId
 *   in the definition is overwritten. Verifying this end-to-end requires authenticating
 *   under two distinct orgIds, which is a larger fixture task left to a dedicated
 *   {@code AgentImportTenantIsolationRuntimeTest} follow-up.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentImportExportRoundTripRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        // Seed a model so the agents.model_id FK can resolve for round-trip cases.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools,
                                    supports_vision, supports_system_instructions, model_type,
                                    created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true,
                        'CHAT', NOW())
                ON CONFLICT (id) DO NOTHING
                """);
        adminAuth = authenticateAs("agent-import-export-admin",
                "agent-import-export-admin@test.local", "pass-aiea-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void exportThenImportPreservesKeyFields() {
        String sourceId = "src-" + UUID.randomUUID();
        seedAgent(sourceId, "Source Agent", "Source description", "Source instructions");

        // Export
        ResponseEntity<Map<String, Object>> exportResp = rest.exchange(
                url("/api/admin/agents/" + sourceId + "/export"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, exportResp.getStatusCode(),
                "export must return 200; got " + exportResp.getStatusCode());
        Map<String, Object> exported = exportResp.getBody();
        assertNotNull(exported, "export response body must not be null");

        // Mutate only the id, keep everything else.
        Map<String, Object> importBody = new HashMap<>(exported);
        String targetId = "dst-" + UUID.randomUUID();
        importBody.put("agentId", targetId);

        ResponseEntity<Map<String, Object>> importResp = rest.exchange(
                url("/api/admin/agents/import"),
                HttpMethod.POST,
                new HttpEntity<>(importBody, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, importResp.getStatusCode(),
                "import must return 200; got " + importResp.getStatusCode());
        Map<String, Object> imported = importResp.getBody();
        assertNotNull(imported, "import response body must not be null");

        // Compare key wire-visible fields. agentId differs by design; everything else must match.
        for (String field : List.of("name", "description", "instructions", "model")) {
            assertEquals(exported.get(field), imported.get(field),
                    "round-trip must preserve `" + field + "`; source=" + exported.get(field)
                            + " imported=" + imported.get(field));
        }

        // The imported row must be queryable as a NEW agent (different id, both rows present).
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE id IN (?, ?)",
                Long.class, sourceId, targetId);
        assertEquals(2L, total,
                "both source and imported agents must exist in the DB; got " + total);
    }

    @Test
    void importWritesIMPORTAuditRow() {
        String targetId = "import-audit-" + UUID.randomUUID();
        Map<String, Object> importBody = Map.of(
                "agentId", targetId,
                "name", "audit-probe",
                "description", "for IMPORT audit-row pin",
                "instructions", "do nothing",
                "model", "gpt-4o-mini");

        Long baseline = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'IMPORT'",
                Long.class, targetId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents/import"),
                HttpMethod.POST,
                new HttpEntity<>(importBody, adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "import must return 200; got " + response.getStatusCode());

        Long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'IMPORT'",
                Long.class, targetId);
        assertEquals(baseline + 1, after,
                "import must write exactly one IMPORT audit row; baseline=" + baseline
                        + " after=" + after);
    }

    @Test
    void importWithMissingIdReturns400() {
        // Missing the @NotBlank `agentId` field — @Valid on the controller returns 400 before
        // the service-layer null-check fires.
        Map<String, Object> importBody = Map.of(
                "name", "no-id-agent",
                "description", "missing id",
                "instructions", "do nothing",
                "model", "gpt-4o-mini");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents/import"),
                HttpMethod.POST,
                new HttpEntity<>(importBody, adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "import with missing required id must return 400; got "
                        + response.getStatusCode());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id, String name, String description, String instructions) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'gpt-4o-mini', true, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, name, description, instructions, TenantConstants.DEFAULT_SYSTEM_ORG);
    }
}
