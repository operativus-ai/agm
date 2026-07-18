package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.control.service.AgentBulkOperationsService;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the bulk-action surface across two layers:
 *   <ol>
 *     <li><b>HTTP layer</b> — {@code POST /api/admin/agents/bulk-action} enqueues a job per
 *         submit and returns 202 with a {@code jobId}. Verified for ENABLE / DISABLE / DELETE
 *         actions. Async processing is NOT observed in tests because the job-queue poller is
 *         intentionally disabled in {@code application-test.properties}.</li>
 *     <li><b>Service layer</b> — {@link AgentBulkOperationsService#enableAll} and
 *         {@link AgentBulkOperationsService#disableAll} invoked synchronously to pin the
 *         actual behavior the queue handler executes asynchronously in prod:
 *         <ul>
 *           <li>{@code findAllById}-based bulk fetch silently filters missing IDs
 *               (no IllegalArgumentException for non-existent IDs).</li>
 *           <li>Idempotent: enabling already-enabled agents is a no-op for {@code active}
 *               but still counts toward the {@code affected} return.</li>
 *           <li>Empty list returns {@code affected=0} (does not throw).</li>
 *         </ul>
 *     </li>
 *   </ol>
 *
 *   <p>What this does NOT cover (deferred): the {@code DELETE} bulk path requires admin
 *   authentication context because {@code AgentAdminService.deleteAgent} is
 *   {@code @PreAuthorize}-gated by {@link com.operativus.agentmanager.control.security.AgentPermissionEvaluator}.
 *   Exercising it from a runtime test requires SecurityContextHolder plumbing that's
 *   easier to skip here and verify in a follow-up that targets the queue-handler boundary
 *   specifically.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentBulkActionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, String>> JOB_ID_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private AgentBulkOperationsService bulkOperationsService;

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("agent-bulk-admin",
                "agent-bulk-admin@test.local", "pass-aba-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    // ─── HTTP layer ──────────────────────────────────────────────────────────

    @Test
    void httpPostBulkActionEnqueuesJobForEachOfTheThreeKnownActions() {
        for (String action : List.of("ENABLE", "DISABLE", "DELETE")) {
            Map<String, Object> body = Map.of(
                    "ids", List.of("probe-id-1", "probe-id-2"),
                    "action", action);
            ResponseEntity<Map<String, String>> response = rest.exchange(
                    url("/api/admin/agents/bulk-action"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, adminAuth),
                    JOB_ID_RESPONSE);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(),
                    "POST /bulk-action with action=" + action + " must return 202; got "
                            + response.getStatusCode());
            assertNotNull(response.getBody(), "response missing body for action=" + action);
            String jobId = response.getBody().get("jobId");
            assertNotNull(jobId, "response missing jobId for action=" + action);
            assertTrue(!jobId.isBlank(), "jobId must not be blank for action=" + action);
        }
    }

    // ─── Service layer ───────────────────────────────────────────────────────

    @Test
    void enableAllFlipsActiveTrueOnAllSpecifiedAgents() {
        String id1 = "bulk-enable-1-" + UUID.randomUUID();
        String id2 = "bulk-enable-2-" + UUID.randomUUID();
        seedAgent(id1, /* active = */ false);
        seedAgent(id2, /* active = */ false);

        Map<String, Integer> result = bulkOperationsService.enableAll(List.of(id1, id2));

        assertEquals(2, result.get("affected"));
        assertTrue(activeFlag(id1), id1 + " must be active=true after enableAll");
        assertTrue(activeFlag(id2), id2 + " must be active=true after enableAll");
    }

    @Test
    void disableAllFlipsActiveFalseOnAllSpecifiedAgents() {
        String id1 = "bulk-disable-1-" + UUID.randomUUID();
        String id2 = "bulk-disable-2-" + UUID.randomUUID();
        seedAgent(id1, /* active = */ true);
        seedAgent(id2, /* active = */ true);

        Map<String, Integer> result = bulkOperationsService.disableAll(List.of(id1, id2));

        assertEquals(2, result.get("affected"));
        assertEquals(false, activeFlag(id1), id1 + " must be active=false after disableAll");
        assertEquals(false, activeFlag(id2), id2 + " must be active=false after disableAll");
    }

    @Test
    void bulkActionWithNonExistentIdsSilentlyFiltersToExistingRows() {
        String existing = "bulk-mixed-existing-" + UUID.randomUUID();
        String missing = "bulk-mixed-missing-" + UUID.randomUUID();
        seedAgent(existing, /* active = */ false);

        Map<String, Integer> result = bulkOperationsService.enableAll(List.of(existing, missing));

        assertEquals(1, result.get("affected"),
                "non-existent IDs must be silently filtered (findAllById behavior); "
                        + "affected count should reflect only the existing rows");
        assertTrue(activeFlag(existing), existing + " must be active=true");
    }

    @Test
    void bulkActionOnAlreadyEnabledAgentsIsIdempotentForStateButCountsAsAffected() {
        String id = "bulk-idempotent-" + UUID.randomUUID();
        seedAgent(id, /* active = */ true);

        Map<String, Integer> result = bulkOperationsService.enableAll(List.of(id));

        assertEquals(1, result.get("affected"),
                "enableAll counts every fetched row as affected, regardless of whether "
                        + "the state was already at the target value");
        assertTrue(activeFlag(id),
                "enableAll on an already-enabled agent must leave it enabled");
    }

    @Test
    void emptyIdListReturnsZeroAffectedWithoutThrowing() {
        Map<String, Integer> result = bulkOperationsService.enableAll(List.of());
        assertEquals(0, result.get("affected"),
                "empty bulk list must return affected=0 (no-op, no throw)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id, boolean active) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'test-instructions', NULL, ?, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, id, "desc-" + id, active, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private Boolean activeFlag(String agentId) {
        return jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
    }
}
