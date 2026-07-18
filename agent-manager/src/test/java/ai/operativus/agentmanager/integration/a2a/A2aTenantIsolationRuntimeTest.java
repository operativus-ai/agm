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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the tenant-isolation contract of the inbound A2A task
 *   path. The {@code A2AController.submitTask} handler does NOT read {@code X-Org-Id}
 *   (unlike the peer-registry handlers at the same controller), and {@code
 *   A2ATaskExecutor.executeTask} does not pass orgId to {@code AgentOperations.run}.
 *   Tenant resolution therefore depends on whatever the agent-registry layer does
 *   with the JWT-bound org_id propagated via {@code SecurityContextHolder} +
 *   {@code TenantContextFilter}.
 *
 *   Two cases:
 *   <ol>
 *     <li>Same-org positive control: user in Org A submits a task for an agent
 *         owned by Org A — must succeed end-to-end.</li>
 *     <li>Cross-org isolation: user in Org A submits a task for an agent owned
 *         by Org B — the cross-tenant agent must NOT execute. The test asserts
 *         on outcomes (zero COMPLETED runs for the cross-tenant agent, no
 *         COMPLETED audit for this taskId, no scripted response leakage into
 *         the SSE body), not on the specific failure mechanism. A regression
 *         at any defense layer (controller, executor, registry, row resolver)
 *         surfaces here.</li>
 *   </ol>
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTenantIsolationRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    // Victim agent lives in the default system org (proven to work via
    // A2aInboundTaskRuntimeTest happy path). The attacker user is bound to a
    // separate custom org. The cross-tenant question is: can the attacker reach
    // the victim agent via POST /api/v1/a2a/tasks ?
    private static final String VICTIM_ORG = TenantConstants.DEFAULT_SYSTEM_ORG;
    private static final String ATTACKER_ORG = "attacker-org-" + UUID.randomUUID();

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Positive control. Same-org A2A run (both user and agent bound to
     * the default system org) succeeds end-to-end. Guarantees the test fixture
     * itself is correct before pinning the cross-tenant negative in Case 2.
     */
    @Test
    void sameOrgUser_submitsTaskForAgentInSameOrg_succeeds() {
        String agentId = persistAgent("a2a-tenant-same-" + UUID.randomUUID(), VICTIM_ORG);
        fakeChatModel.respondWith("same-org result");

        String taskId = "task-same-" + UUID.randomUUID();
        HttpHeaders auth = userHeadersInOrg("a2a-tenant-same", VICTIM_ORG);
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "same-org input",
                null, "same-org-session", null, null);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/a2a/tasks"),
                HttpMethod.POST,
                new HttpEntity<>(request, auth),
                String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        Integer completedRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, agentId);

        assertAll("Same-org A2A — positive control",
                () -> assertEquals(HttpStatus.OK, response.getStatusCode(),
                        "controller returns 200 — same-org submission accepted"),
                () -> assertEquals(1, completedRuns,
                        "agent_runs has one COMPLETED row for the agent — same-org dispatch succeeded"));
    }

    /**
     * Case 2 — Cross-org isolation. A user authenticated under Org A submits a
     * task targeting an agent owned by Org B. Asserts on outcomes (no successful
     * cross-tenant execution), not on the specific failure mechanism.
     */
    @Test
    void crossOrgUser_submitsTaskForAgentInDifferentOrg_isRejectedAndDoesNotExecute() {
        String victimAgent = persistAgent("a2a-tenant-victim-" + UUID.randomUUID(), VICTIM_ORG);
        // Scripted response is set so that, if isolation IS broken and the agent
        // executes, the failure is visible as scripted-content leakage in the SSE
        // body — making the regression mode obvious.
        fakeChatModel.respondWith("cross-tenant leak result");

        String taskId = "task-cross-" + UUID.randomUUID();
        HttpHeaders attackerAuth = userHeadersInOrg("a2a-tenant-attacker", ATTACKER_ORG);
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, victimAgent, "cross-tenant probe input",
                null, "cross-tenant-session", null, null);

        // Tolerant SSE read: the cross-tenant reject completes the emitter with an error (abrupt
        // chunk close), but we still need the body to prove the scripted leak did NOT stream back.
        String sseBody = postAndReadEventStreamTolerant("/api/v1/a2a/tasks", request, attackerAuth);

        // Wait for the executor's FAILED branch to land — cross-tenant lookup
        // surfaces as "Agent not found" inside AgentService.run, which the
        // executor catches and audits as FAILED with the exception message.
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED'",
                        Integer.class, taskId) >= 1);

        Integer crossTenantCompletedRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, victimAgent);
        Integer completedAuditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                Integer.class, taskId);
        String failedErrorDetail = jdbc.queryForObject(
                "SELECT error_detail FROM a2a_task_events WHERE task_id = ? AND status = 'FAILED' " +
                        "ORDER BY event_ts DESC LIMIT 1",
                String.class, taskId);

        assertAll("Cross-org A2A — attacker in another org must NOT execute victim's agent",
                () -> assertEquals(0, crossTenantCompletedRuns,
                        "victim agent has zero COMPLETED agent_run rows from the attacker's " +
                                "request — execution did not breach the org boundary"),
                () -> assertEquals(0, completedAuditRows,
                        "no COMPLETED audit row for this taskId — cross-tenant lookup failed " +
                                "before agent execution"),
                () -> assertTrue(failedErrorDetail != null && !failedErrorDetail.isBlank(),
                        "FAILED audit row carries an error_detail — the registry's " +
                                "tenant-scoped lookup raised an exception that the executor " +
                                "captured"),
                () -> assertTrue(sseBody == null || !sseBody.contains("cross-tenant leak result"),
                        "scripted cross-tenant result did NOT leak into the SSE response — proves " +
                                "FakeChatModel was not invoked on behalf of the cross-tenant agent"));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private HttpHeaders userHeadersInOrg(String prefix, String orgId) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = registerLoginWithOrg(username, orgId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    private String persistAgent(String id, String orgId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(orgId);
        a.setName("A2A tenant-isolation runtime target");
        a.setDescription("Tenant boundary fixture");
        a.setInstructions("Tenant-bound target — should only run for callers in the same org.");
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
