package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves the production advisor chain fires on A2A-dispatched
 *   runs. The execution path goes:
 *
 *   POST /api/v1/a2a/tasks
 *     -> A2ATaskExecutor.executeTask (virtual thread)
 *     -> AgentOperations.run (production AgentService)
 *     -> AgentClientFactory.buildChatClient — assembles the advisor chain
 *     -> ChatClient with chain -> FakeChatModel
 *
 *   The advisor chain includes {@code GenAiMetricsAdvisor} (token-usage capture,
 *   FinOps), {@code AgentLoggingAdvisor}, {@code ContentSafetyAdvisor}, etc. The
 *   most direct way to prove the chain fired on the A2A path — without enumerating
 *   every advisor — is to assert on observable side-effects that only the chain
 *   produces:
 *   <ul>
 *     <li>{@code GenAiMetricsAdvisor} reads usage from the {@code ChatResponse} and
 *         pushes input/output token counts into {@code RunTelemetryAccumulator},
 *         which sets them on the {@code AgentRun} entity row.</li>
 *     <li>{@code AgentService.run} stamps the caller's {@code orgId} into the
 *         {@code agent_runs.org_id} column when persisting the run.</li>
 *   </ul>
 *
 *   {@code FakeChatModel.respondWithTokens(text, inputTokens, outputTokens)} emits
 *   real {@code DefaultUsage} metadata in the ChatResponse, so the advisor chain
 *   has real data to work with — the path is exercised end-to-end, not stubbed.
 *
 *   Pairs with {@code A2aTenantIsolationRuntimeTest}: that one pins the boundary,
 *   this one pins that the run persists with the correct caller {@code orgId}
 *   (complementary side of the same fix).
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aAdvisorChainRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Token-usage capture proves {@code GenAiMetricsAdvisor} (and the
     * downstream {@code RunTelemetryAccumulator}) fired on the A2A path. Asserts
     * the exact token counts emitted by FakeChatModel land on the {@code agent_runs}
     * row. If the metrics advisor were skipped for A2A-dispatched runs, both
     * columns would remain null and this assertion would fail.
     */
    @Test
    void a2aRun_persistsTokenUsageFromFakeChatModel_proofMetricsAdvisorFires() {
        String agentId = persistAgent("a2a-tokens-" + UUID.randomUUID());
        fakeChatModel.respondWithTokens("token-bearing response", 47, 113);

        String taskId = "task-tokens-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-tokens");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "input that produces usage metadata",
                null, "tokens-session", null, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        // Token columns are written from the metrics advisor's accumulator. Give
        // the run-finalize path a beat to flush.
        sleepQuietly(500);

        Long inputTokens = jdbc.queryForObject(
                "SELECT input_tokens FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                Long.class, agentId);
        Long outputTokens = jdbc.queryForObject(
                "SELECT output_tokens FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                Long.class, agentId);

        assertAll("A2A run — advisor chain fires (GenAiMetricsAdvisor + RunTelemetryAccumulator)",
                () -> assertNotNull(inputTokens,
                        "agent_runs.input_tokens is non-null — GenAiMetricsAdvisor read usage " +
                                "from the FakeChatModel ChatResponse and the accumulator persisted it"),
                () -> assertNotNull(outputTokens,
                        "agent_runs.output_tokens is non-null"),
                () -> assertEquals(47L, inputTokens.longValue(),
                        "input_tokens equals the count emitted by FakeChatModel — round-trip " +
                                "through the production advisor chain"),
                () -> assertEquals(113L, outputTokens.longValue(),
                        "output_tokens equals the count emitted by FakeChatModel"));
    }

    /**
     * Case 2 — Caller's org propagates through the full execution stack and lands
     * on {@code agent_runs.org_id}. Complements
     * {@code A2aTenantIsolationRuntimeTest} (which pins the boundary): this one
     * pins that *the right* org_id is stamped on the persisted run, not just that
     * cross-tenant access is rejected.
     */
    @Test
    void a2aRun_persistsAgentRunWithCallerOrgId_proofTenantPropagation() {
        String agentId = persistAgent("a2a-org-" + UUID.randomUUID());
        fakeChatModel.respondWith("org-propagation result");

        String taskId = "task-org-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-org");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "input for org-stamp",
                null, "org-session", null, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        String runOrgId = jdbc.queryForObject(
                "SELECT org_id FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, agentId);
        String runModel = jdbc.queryForObject(
                "SELECT model FROM agent_runs WHERE agent_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, agentId);

        assertAll("A2A run — caller orgId propagates to agent_runs row",
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, runOrgId,
                        "agent_runs.org_id equals the caller's JWT-bound org — proves " +
                                "CallerContext.resolveCallerOrgId -> A2ATaskExecutor -> " +
                                "AgentService.run -> AgentRun.orgId chain holds"),
                () -> assertTrue(runModel != null && !runModel.isBlank(),
                        "agent_runs.model is populated — AgentClientFactory selected a model " +
                                "for the A2A run (proves the model-resolution path fired)"));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private HttpHeaders userHeaders(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = registerLoginWithOrg(username, TenantConstants.DEFAULT_SYSTEM_ORG);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    private String persistAgent(String id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName("A2A advisor-chain runtime target");
        a.setDescription("Advisor-chain runtime fixture");
        a.setInstructions("Target that drives FakeChatModel through the full production advisor chain.");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        a.setTeamMode(null);
        a.setMembers(null);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
