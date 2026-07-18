package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the FinOps BUDGET_HALT propagation contract for inbound
 *   A2A tasks. {@code GenAiMetricsAdvisor.resolveBudgetCeiling()} returns the
 *   {@code agentmanager.finops.default-session-ceiling-usd} property when no
 *   request-scoped budget is bound. When the advisor's per-session cumulative
 *   spend (input+output tokens × rate from {@code finops_valuation_rate}) exceeds
 *   the ceiling, it throws {@code FinOpsBudgetExhaustedException}.
 *   {@code A2ATaskExecutor.executeTask} has a dedicated catch clause that routes
 *   this to {@code emitter.budgetHalt} + audit {@code A2aTaskStatus.BUDGET_HALT}.
 *
 *   This test forces the failure path:
 *   <ol>
 *     <li>{@code @TestPropertySource} sets the default ceiling to $0.0001.</li>
 *     <li>The seeded {@code finops_valuation_rate} for {@code gpt-4o-mini} (input
 *         $0.15/1K, output $0.60/1K per Liquibase 002-seed-data.sql) is used by
 *         {@code LiveValuationEngine}.</li>
 *     <li>{@code FakeChatModel.respondWithTokens(..., 1000, 1000)} emits real
 *         usage metadata in the {@code ChatResponse}. Cost ≈ $0.75, well above
 *         the $0.0001 ceiling.</li>
 *     <li>{@code GenAiMetricsAdvisor} throws; executor audits BUDGET_HALT.</li>
 *   </ol>
 *
 *   This is a separate test class (not stacked into A2aInboundTaskRuntimeTest)
 *   because the low ceiling would make every other A2A test fail. Spring's
 *   context cache picks up the distinct {@code @TestPropertySource}, so the cost
 *   is one extra context boot — acceptable for the coverage gain.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
// Tiny ceiling — any spend trips the budget guard. The first FinOps-monitored call
// in the agent pipeline is the cache-lookup embedding (via VectorStoreCacheAdvisor →
// FinOpsObservedEmbeddingModel), so the exception fires there. Until the
// VectorStoreCacheAdvisor propagation fix in this PR, that catch swallowed
// FinOpsBudgetExhaustedException as a soft cache-miss. Now it re-throws so the
// exception reaches A2ATaskExecutor's dedicated BUDGET_HALT branch.
@TestPropertySource(properties = "agentmanager.finops.default-session-ceiling-usd=0.0001")
public class A2aBudgetHaltRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Token-cost spend exceeds the configured ceiling. Advisor throws
     * {@code FinOpsBudgetExhaustedException}; executor's dedicated catch clause
     * audits BUDGET_HALT.
     *
     * <p>This test re-activates the {@code TASK_REF=A2A-BudgetHalt-Propagation}
     * marker that was {@code @Disabled} in PR #519. Investigation found
     * {@code VectorStoreCacheAdvisor.getCachedDocumentOrNull} and
     * {@code .cacheResponse} each had {@code catch (Exception e)} blocks that
     * silently swallowed {@code FinOpsBudgetExhaustedException} thrown from the
     * embedding model during semantic-cache similarity search / write. Those
     * catch blocks now re-throw budget-exhaustion exceptions cleanly while
     * still catching other Exceptions as soft cache-failure fallbacks. With
     * that propagation fix, the embedding-side exception reaches
     * {@code A2ATaskExecutor.executeTask}'s dedicated catch clause and
     * audits BUDGET_HALT.
     */
    @Test
    void a2aRun_tokenSpendExceedsCeiling_executorAuditsBudgetHaltNotFailedOrCompleted() {
        String agentId = persistAgent("a2a-budget-" + UUID.randomUUID());

        // Token count is incidental — at the $0.0001 ceiling, the cache-lookup
        // embedding (~$0.277) trips the guard before the chat call even runs.
        // We still seed a response so any unexpected fall-through path also
        // produces deterministic output rather than blocking on FakeChatModel.
        fakeChatModel.respondWithTokens("would-be-output", 100, 100);

        String taskId = "task-budget-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-budget");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "input that drives spend above the ceiling",
                null, "budget-session-" + UUID.randomUUID(), null, null);

        // Don't await the SSE — BUDGET_HALT is non-terminal at the SseEmitter
        // level (same pattern as PAUSED). Just kick off the POST asynchronously
        // and assert on the audit trail.
        new Thread(() -> {
            try {
                rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                        new HttpEntity<>(request, auth), String.class);
            } catch (Exception ignored) {
                // Stream timeout / read error are expected since BUDGET_HALT
                // doesn't close the emitter.
            }
        }).start();

        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'BUDGET_HALT'",
                        Integer.class, taskId) >= 1);

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        Integer failedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                Integer.class, taskId);
        Integer completedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                Integer.class, taskId);
        String budgetHaltMessage = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ? AND status = 'BUDGET_HALT' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("A2A BUDGET_HALT — spend exceeds ceiling, executor routes correctly",
                () -> assertTrue(statuses.contains("BUDGET_HALT"),
                        "BUDGET_HALT was audited — the executor's dedicated catch clause for " +
                                "FinOpsBudgetExhaustedException fired"),
                () -> assertEquals(0, failedRows.intValue(),
                        "no FAILED row — BUDGET_HALT must NOT be routed to the generic FAILED " +
                                "branch (different catch order, different semantics: renegotiate vs " +
                                "treat as error)"),
                () -> assertEquals(0, completedRows.intValue(),
                        "no COMPLETED row — the run did not finish, the LLM call was halted by " +
                                "FinOpsBudgetExhaustedException before any completion path ran"),
                () -> assertEquals("BUDGET_HALT",
                        statuses.get(statuses.size() - 1),
                        "BUDGET_HALT is the last audited status — terminal for this attempt; the " +
                                "peer is expected to renegotiate"),
                () -> assertTrue(budgetHaltMessage != null
                                && budgetHaltMessage.toLowerCase().contains("budget"),
                        "BUDGET_HALT audit message describes the budget condition"));
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
        a.setName("A2A budget-halt runtime target");
        a.setDescription("BUDGET_HALT fixture");
        a.setInstructions("Uses gpt-4o-mini whose seeded rate makes 1K+1K tokens cost ~$0.75.");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        a.setTeamMode(null);
        a.setMembers(null);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        // Pin provider='fake' so the FakeModelProviderConfig wires FakeChatModel for this agent.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
