package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.compute.mcp.McpConnectionPool;
import ai.operativus.agentmanager.compute.service.RunExecutionManager;
import ai.operativus.agentmanager.control.registry.DatabaseAgentRegistry;
import ai.operativus.agentmanager.control.service.ModelApiKeyMigrationService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box coverage of the AGM startup lifecycle — the pre-bean
 *   {@code DynamicProviderInitializer} (which now only installs dummy provider keys so
 *   Spring AI's eager validation passes) and the four
 *   {@code @EventListener(ApplicationReadyEvent.class)} hooks:
 *   {@link McpConnectionPool#reconcileOnStartup()},
 *   {@link RunExecutionManager#cleanupOrphanedRuns()},
 *   {@link DatabaseAgentRegistry#primeCache()}, and
 *   {@link ModelApiKeyMigrationService#encryptExistingApiKeys()}.
 *
 *   Real API keys are resolved per-request from {@code provider_credentials} (or the
 *   per-model {@code ModelEntity.apiKey} override). No env-var / Spring property
 *   provider-enablement contract exists anymore.
 *
 * State: Stateless (per-test DB truncation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class StartupLifecycleRuntimeTest extends BaseIntegrationTest {

    @Autowired private McpConnectionPool mcpConnectionPool;
    @Autowired private RunExecutionManager runExecutionManager;
    @Autowired private DatabaseAgentRegistry databaseAgentRegistry;
    @Autowired private ModelApiKeyMigrationService modelApiKeyMigrationService;

    @Test
    void contextIsHealthyAfterBoot() {
        ResponseEntity<String> resp = rest.exchange(
                url("/actuator/health"), HttpMethod.GET, null, String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "/actuator/health must return 200 once the context is ready");
        String body = resp.getBody();
        assertNotNull(body, "/actuator/health must carry a body");
        assertTrue(body.contains("\"status\":\"UP\""),
                "/actuator/health must report status UP. Got: " + body);
    }

    @Test
    void applicationReadyEventBeansAreWired() {
        assertNotNull(mcpConnectionPool, "McpConnectionPool bean must be wired");
        assertNotNull(runExecutionManager, "RunExecutionManager bean must be wired");
        assertNotNull(databaseAgentRegistry, "DatabaseAgentRegistry bean must be wired");
        assertNotNull(modelApiKeyMigrationService, "ModelApiKeyMigrationService bean must be wired");
    }

    @Test
    void mcpConnectionPoolInitializedEmptyWhenNoConnectionsConfigured() {
        var callbacks = mcpConnectionPool.getAllToolCallbacks();
        assertNotNull(callbacks, "getAllToolCallbacks must never return null");
        assertEquals(0, callbacks.size(),
                "MCP pool must initialize empty because no stdio connections are configured in "
                        + "the test profile. Got " + callbacks.size() + " callbacks.");
    }

    @Test
    void orphanedRunningRunIsReconciledToCancelled() {
        String orphanId = "orphan-run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, input, status, created_at, updated_at)
                VALUES (?, NULL, ?, 'RUNNING', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours')
                """, orphanId, "test input");

        String statusBefore = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, orphanId);
        assertEquals("RUNNING", statusBefore, "seed precondition — row must start RUNNING");

        runExecutionManager.cleanupOrphanedRuns();

        String statusAfter = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, orphanId);
        String output = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?", String.class, orphanId);
        assertEquals("CANCELLED", statusAfter,
                "orphan RUNNING run must be transitioned to CANCELLED by cleanupOrphanedRuns()");
        assertNotNull(output);
        assertTrue(output.contains("Orphaned"),
                "cancelled orphan must carry the documented 'Orphaned' output marker. Got: " + output);
    }

    @Test
    void modelApiKeyMigrationIsIdempotentAcrossReinvocation() {
        modelApiKeyMigrationService.encryptExistingApiKeys();
        modelApiKeyMigrationService.encryptExistingApiKeys();
    }
}
