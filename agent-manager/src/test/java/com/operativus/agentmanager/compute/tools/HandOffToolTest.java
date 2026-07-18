package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.compute.teams.TierEscalationValidator;
import com.operativus.agentmanager.compute.teams.TransitionValidator;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.exception.SwarmEscalationException;
import com.operativus.agentmanager.core.exception.SwarmHandOffException;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Domain Responsibility: Runtime test for {@link HandOffTool#hand_off_to_agent} — the
 * Swarm-style voluntary handoff surface (sister to {@link DelegationTool}, which has its
 * own 3 test classes). Asserts the 4 vectors:
 *   (a) success path: both validators pass, method throws SwarmHandOffException carrying
 *       target agent + handoff context (this is the SUCCESS signal — the exception is
 *       how the LLM control loop knows to redirect)
 *   (b) DAG transition rejected: TransitionValidator throws → propagates unchanged
 *   (c) tier escalation blocked: TierEscalationValidator throws SwarmEscalationException
 *       → propagates unchanged
 *   (d) null source agent (no ScopedValue bound): TierEscalationValidator MUST NOT be
 *       called, but the success path still terminates via SwarmHandOffException
 *
 * State: Stateless. Mockito stubs of TransitionValidator + TierEscalationValidator are
 * independent ground truth (A18). HandOffTool reads source agent from
 * AgentContextHolder.getAgentId() — bound via ScopedValue.where(...).run(...) in
 * tests that need a non-null source.
 */
class HandOffToolTest {

    private final TransitionValidator transitionValidator = mock(TransitionValidator.class);
    private final TierEscalationValidator tierEscalationValidator = mock(TierEscalationValidator.class);
    private final AgentRunEventBus eventBus = mock(AgentRunEventBus.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentRegistry> agentRegistryProvider = mock(ObjectProvider.class);
    private final HandOffTool tool = new HandOffTool(transitionValidator, tierEscalationValidator,
            eventBus, agentRegistryProvider);

    /**
     * Capture-and-rethrow holder so the lambda inside ScopedValue.run() can pass a
     * RuntimeException out to the test for assertion.
     */
    private static final class Holder<T extends RuntimeException> {
        T captured;
    }

    /** Run handoff under a ScopedValue binding for agentId; capture any RuntimeException it throws. */
    private RuntimeException runWithSource(String sourceAgentId, Runnable body) {
        Holder<RuntimeException> h = new Holder<>();
        try {
            ScopedValue.where(AgentContextHolder.agentId, sourceAgentId).run(() -> {
                try {
                    body.run();
                } catch (RuntimeException re) {
                    h.captured = re;
                }
            });
        } catch (RuntimeException re) {
            // belt-and-braces: in case ScopedValue propagates instead of catching
            h.captured = re;
        }
        return h.captured;
    }

    // (a) success path — both validators pass, exception thrown carrying target + context
    @Test
    void handOff_success_throwsSwarmHandOffException() {
        RuntimeException ex = runWithSource("run-1",
                () -> tool.hand_off_to_agent("billing-agent", "user wants refund"));

        assertEquals(SwarmHandOffException.class, ex.getClass());
        SwarmHandOffException handoff = (SwarmHandOffException) ex;
        assertEquals("billing-agent", handoff.getTargetAgentId());
        assertEquals("user wants refund", handoff.getHandOffContext());
        // teamId is intentionally null — tool-driven handoffs do not carry a team root
        // id (see HandOffTool DAG-validation comment). Pinning eq(null) so a future
        // refactor that re-introduces workflowRunId-as-teamId trips this assertion.
        // No teamRootId bound in this test → getTeamRootId() returns null → validator
        // sees null teamId (the "skip DAG validation" sentinel). The dedicated team-scope
        // test below pins the positive path where teamRootId IS bound.
        verify(transitionValidator).validate(eq(null), eq("run-1"), eq("billing-agent"));
        verify(tierEscalationValidator).validate(eq("run-1"), eq("billing-agent"), any());
    }

    // (b) DAG transition rejected — propagates unchanged, tier validator not invoked
    @Test
    void handOff_dagRejected_propagatesAndSkipsTierCheck() {
        doThrow(new IllegalStateException("DAG: edge not allowed"))
                .when(transitionValidator).validate(eq(null), eq("run-1"), eq("billing-agent"));

        RuntimeException ex = runWithSource("run-1",
                () -> tool.hand_off_to_agent("billing-agent", "ctx"));

        assertEquals(IllegalStateException.class, ex.getClass());
        assertEquals("DAG: edge not allowed", ex.getMessage());
        verify(tierEscalationValidator, never()).validate(any(), any(), any());
    }

    // (c) tier escalation blocked — propagates SwarmEscalationException unchanged
    @Test
    void handOff_tierEscalationBlocked_propagatesEscalationException() {
        SwarmEscalationException blocked = new SwarmEscalationException("run-1", "privileged-agent", 1, 3);
        doThrow(blocked).when(tierEscalationValidator).validate(eq("run-1"), eq("privileged-agent"), any());

        RuntimeException ex = runWithSource("run-1",
                () -> tool.hand_off_to_agent("privileged-agent", "needs MFA-tier work"));

        assertEquals(SwarmEscalationException.class, ex.getClass());
        SwarmEscalationException esc = (SwarmEscalationException) ex;
        assertEquals("run-1", esc.getSourceAgentId());
        assertEquals("privileged-agent", esc.getTargetAgentId());
        assertEquals(1, esc.getSourceTier());
        assertEquals(3, esc.getTargetTier());
    }

    // (e) teamRootId IS bound — validator must receive the team's root agent id, not null.
    // Pins the wiring done by TeamOrchestrationEngine.executeSync/executeStream so a future
    // refactor that drops the binding silently regresses DAG validation back to no-op mode.
    @Test
    void handOff_teamRootIdBound_passesTeamIdToTransitionValidator() {
        Holder<RuntimeException> h = new Holder<>();
        ScopedValue.where(AgentContextHolder.agentId, "run-1")
                .where(AgentContextHolder.teamRootId, "team-root-007")
                .run(() -> {
                    try {
                        tool.hand_off_to_agent("billing-agent", "user wants refund");
                    } catch (RuntimeException re) {
                        h.captured = re;
                    }
                });

        assertEquals(SwarmHandOffException.class, h.captured.getClass());
        verify(transitionValidator).validate(eq("team-root-007"), eq("run-1"), eq("billing-agent"));
    }

    // (d) null source — tier validator MUST NOT be called; success still throws SwarmHandOff
    @Test
    void handOff_nullSourceAgent_skipsTierCheckButStillSucceeds() {
        // No ScopedValue binding → AgentContextHolder.getCurrentRunId() == null
        SwarmHandOffException ex = assertThrows(SwarmHandOffException.class,
                () -> tool.hand_off_to_agent("billing-agent", "user wants refund"));

        assertEquals("billing-agent", ex.getTargetAgentId());
        verify(transitionValidator).validate(eq(null), eq(null), eq("billing-agent"));
        verify(tierEscalationValidator, never()).validate(any(), any(), any());
    }
}
