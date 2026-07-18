package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.service.AgentService;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: cancellation-propagation runtime coverage. Verifies that
 *   {@code POST /runs/{id}/cancel} (via {@link AgentService#cancelRun}) does in fact
 *   stop a Sequential team mid-execution and that no further members are dispatched
 *   after the cancel signal arrives. This closes the P4 gap from the orchestration
 *   coverage mitigation plan.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Cancel mechanism (verified by code inspection of {@code RunExecutionManager.cancel}):
 *   1. {@code activeRuns.remove(runId)} returns the {@code Future<?>} registered when
 *      the team was started via {@code runInBackground}.
 *   2. {@code future.cancel(true)} interrupts the team's virtual thread.
 *   3. {@code AgentService.run}'s outer machinery propagates the interrupt; the
 *      finalizer marks the team row {@code CANCELLED}.
 *
 * Why this test runs the team via {@code runInBackground} (not synchronous {@code run}):
 *   only background runs are tracked in {@code RunExecutionManager.activeRuns}, so
 *   {@code cancel(runId)} can find a Future to interrupt. Synchronous orchestrator
 *   calls aren't cancellable through the public cancel API at all — that's a known
 *   shape of the production system, not a bug.
 *
 * Why FakeChatModel is wrapped with {@code Thread.sleep} (interrupt-aware):
 *   without a blocking sleep, FakeChatModel returns instantly and the interrupt has
 *   no I/O point to fire on. The sleep simulates a real LLM call's blocking I/O.
 *   The sleep is interrupt-aware (throws via {@code RuntimeException}) so the cancel
 *   actually terminates the in-flight member call.
 *
 * Test scope is deliberately narrow: Sequential 3-member team, cancel after member
 * 1 starts. This is the smallest test that exercises:
 *   - The cancel API path end-to-end (cancelRun → RunExecutionManager → Future.cancel)
 *   - VT interrupt propagation through AgentService.run for an in-flight member
 *   - The "no further members" guarantee (member 2 + member 3 never dispatched)
 *   - Real DB persistence of the CANCELLED status
 *
 * Extending to Planner / Router / Swarm follows the same pattern with different
 * orchestrator strategies and different FakeChatModel scripts; intentionally deferred
 * to follow-up work.
 *
 * <h2>History — production bugs this test originally surfaced (now fixed)</h2>
 *
 * When this test was first written (PR #445) it ran against {@code RunExecutionManager.submit}'s
 * catch block which routed every exception — including the {@code InterruptedException}
 * from {@code Future.cancel(true)} — to {@code RunStatus.FAILED}, AND the finalizer's
 * JDBC write rolled back because the VT's interrupt flag was still set. Net: cancelled
 * rows were mis-classified as {@code FAILED} (Bug A), and frequently stuck at
 * {@code RUNNING} when the rollback happened first (Bug B).
 *
 * <p>Both were fixed in {@code RunExecutionManager.submit}'s catch block:
 *   detect {@code Thread.interrupted()} or {@code InterruptedException}/
 *   {@code CancellationException} in the cause chain → route to {@code CANCELLED};
 *   call {@code Thread.interrupted()} (which clears the flag) before invoking the
 *   finalizer so JDBC writes commit successfully.
 * The {@code @Disabled} annotation has been removed; the assertions below now pass.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class OrchestrationCancellationRuntimeTest extends BaseIntegrationTest {

    /** How long each FakeChatModel call sleeps. Long enough that we can win the cancel race. */
    private static final long PER_CALL_SLEEP_MS = 3_000L;

    /** Max wait for the team run row to first reach RUNNING after submit. */
    private static final Duration RUNNING_TIMEOUT = Duration.ofSeconds(5);

    /** Max wait for the team run row to reach CANCELLED after the cancel call. */
    private static final Duration CANCELLED_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private AgentService agentService;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * P4 case 1 — Sequential team cancellation: starting member 1, the cancel API
     * is invoked; member 2 and member 3 must never be dispatched.
     *
     * Asserts (via assertAll):
     *   (a) Team run reaches CANCELLED status within {@link #CANCELLED_TIMEOUT}.
     *   (b) FakeChatModel saw exactly 1 prompt — member 1 was in flight when
     *       cancel fired; members 2 and 3 never got dispatched.
     *   (c) The team run's output column carries a non-null cancellation reason
     *       (proves the finalizer ran on the cancel path, not just a status flip).
     */
    @Test
    void sequentialTeamRun_cancelDuringMember1_marksTeamCancelled_andSkipsRemainingMembers() {
        String m1 = persistMember("cancel-m1-" + UUID.randomUUID(), "Stage 1");
        String m2 = persistMember("cancel-m2-" + UUID.randomUUID(), "Stage 2");
        String m3 = persistMember("cancel-m3-" + UUID.randomUUID(), "Stage 3");
        String rootId = persistTeam("cancel-root-" + UUID.randomUUID(), "Cancel Sequential Root",
                "SEQUENTIAL", List.of(m1, m2, m3));

        AtomicInteger callCount = new AtomicInteger();
        // Slow, interrupt-aware response. Each call sleeps PER_CALL_SLEEP_MS and
        // returns a placeholder. We register the Function 3 times so the deque has
        // entries for each potential member call, but in practice only 1 should
        // actually execute before cancel fires.
        for (int i = 0; i < 3; i++) {
            fakeChatModel.respondWith(p -> {
                callCount.incrementAndGet();
                try {
                    Thread.sleep(PER_CALL_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("FakeChatModel sleep interrupted (expected on cancel path)", e);
                }
                return new ChatResponse(List.of(
                        new Generation(new AssistantMessage("slow stage output"),
                                ChatGenerationMetadata.builder().finishReason("STOP").build())));
            });
        }

        String teamRunId = agentService.runInBackground(rootId, "kickoff", null,
                "cancel-sess-" + UUID.randomUUID(), "cancel-user",
                TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, null);

        assertNotNull(teamRunId, "runInBackground returns the team's runId");

        waitForRunStatus(teamRunId, RunStatus.RUNNING, RUNNING_TIMEOUT);

        // Cancel while the team is mid-flight (FakeChatModel is sleeping inside member 1).
        agentService.cancelRun(teamRunId);

        waitForRunStatus(teamRunId, RunStatus.CANCELLED, CANCELLED_TIMEOUT);

        Integer teamRunRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE id = ? AND status = 'CANCELLED'",
                Integer.class, teamRunId);
        String teamRunOutput = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE id = ?",
                String.class, teamRunId);

        assertAll("Sequential team cancellation",
                () -> assertEquals(1, teamRunRowCount,
                        "team run row in DB has status=CANCELLED"),
                // The contract is "cancel STOPS further dispatch" — not "cancel always lands
                // mid-member-1." Depending on scheduler timing the cancel signal may arrive
                // before member 1's LLM call starts (callCount=0) or while it's mid-sleep
                // (callCount=1). What MUST hold is that members 2 and 3 never ran, which we
                // pin by asserting the count is below the total member count.
                () -> assertTrue(callCount.get() <= 1,
                        "at most 1 FakeChatModel.call fired before cancel — members 2 and 3 " +
                                "must never have been dispatched. Observed: " + callCount.get()),
                () -> assertNotNull(teamRunOutput,
                        "finalizer set the cancellation reason on the run row"));
    }

    /** Polls the agent_runs table until {@code expectedStatus} is observed or the timeout elapses. */
    private void waitForRunStatus(String runId, RunStatus expectedStatus, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        String observed = null;
        while (Instant.now().isBefore(deadline)) {
            observed = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
            if (expectedStatus.name().equals(observed)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Test thread interrupted while polling for status=" + expectedStatus);
            }
        }
        fail("Timed out waiting for run " + runId + " to reach status=" + expectedStatus
                + " after " + timeout + " (last observed: " + observed + ")");
    }

    private String persistMember(String id, String name) {
        return persist(id, name, false, null, null);
    }

    private String persistTeam(String id, String name, String teamMode, List<String> members) {
        return persist(id, name, true, teamMode, members);
    }

    private String persist(String id, String name, boolean isTeam, String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("P4 cancellation fixture: " + name);
        a.setInstructions("P4 fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(isTeam);
        a.setTeamMode(teamMode);
        a.setMembers(members);
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
}
