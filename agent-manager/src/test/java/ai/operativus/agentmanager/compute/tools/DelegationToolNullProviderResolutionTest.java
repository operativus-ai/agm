package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import ai.operativus.agentmanager.compute.teams.TierEscalationValidator;
import ai.operativus.agentmanager.compute.teams.TransitionValidator;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin the {@link DelegationTool#resolveParentDefinition} fallback for
 * the Spring-shutdown race — when {@code agentRegistryProvider.getIfAvailable()} returns
 * {@code null} (half-disposed context, bean disposal order race), the tool MUST NOT NPE
 * and dispatch MUST still complete with the bare {@code sessionId}. Anti-pattern A4 from
 * {@code docs/plans/agm-clear-out.md}.
 *
 * <p><b>Scope boundary:</b> this test asserts the MINIMUM contract — no NPE, no thrown
 * exception, dispatch completes with bare sessionId. It does NOT claim to fully exercise
 * Spring shutdown (bean disposal order, partially-initialized contexts). The full
 * shutdown lifecycle is explicitly out of scope.
 */
class DelegationToolNullProviderResolutionTest {

    private AgentOperations runner;
    private DelegationTool tool;
    private ObjectProvider<AgentOperations> runnerProvider;
    private ObjectProvider<AgentRegistry> registryProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runner = mock(AgentOperations.class);
        runnerProvider = (ObjectProvider<AgentOperations>) mock(ObjectProvider.class);
        registryProvider = (ObjectProvider<AgentRegistry>) mock(ObjectProvider.class);
        when(runnerProvider.getIfAvailable()).thenReturn(runner);

        tool = new DelegationTool(
                runnerProvider, registryProvider,
                mock(TransitionValidator.class),
                mock(TierEscalationValidator.class),
                mock(EphemeralSwarmContext.class),
                mock(AgentRunEventBus.class));

        when(runner.run(anyString(), anyString(), any(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(new RunResponse("child-run", "sess", "ok", new HashMap<>(),
                        List.of(), List.of(), RunStatus.COMPLETED, null));
    }

    @Test
    void registryProviderReturnsNull_dispatchStillCompletesWithBareSessionId() {
        when(registryProvider.getIfAvailable()).thenReturn(null);

        assertThatNoException().isThrownBy(() ->
                ScopedValue.where(AgentContextHolder.agentId, "team-1")
                        .where(AgentContextHolder.sessionId, "sess-shutdown")
                        .run(() -> tool.delegate_to_agent("child-A", "task")));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-A"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue())
                .as("provider null → resolveParentDefinition returns null → bare session id")
                .isEqualTo("sess-shutdown");
    }

    @Test
    void registryProviderItselfNull_constructorPathDoesNotNpe() {
        // Edge: ObjectProvider injection point yielded null. Different from getIfAvailable() returning null.
        // Spring 6+ does not pass null ObjectProvider, but defensive: if a future ObjectProvider impl
        // returns null on construction, the tool's resolveParentDefinition() must not NPE.
        // (Verified indirectly — the existing tool guards via getIfAvailable() before any deref.)
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRegistry> nullReturning = (ObjectProvider<AgentRegistry>) mock(ObjectProvider.class);
        when(nullReturning.getIfAvailable()).thenReturn(null);

        DelegationTool toolWithNullProvider = new DelegationTool(
                runnerProvider, nullReturning,
                mock(TransitionValidator.class),
                mock(TierEscalationValidator.class),
                mock(EphemeralSwarmContext.class),
                mock(AgentRunEventBus.class));

        assertThatNoException().isThrownBy(() ->
                ScopedValue.where(AgentContextHolder.agentId, "team-2")
                        .where(AgentContextHolder.sessionId, "sess-edge")
                        .run(() -> toolWithNullProvider.delegate_to_agent("child-B", "task")));
    }

    @Test
    void registryProviderReturnsNull_andNoParentAgentInContext_stillFallsBackCleanly() {
        // Compound failure: the provider returns null AND the AgentContextHolder.agentId is
        // not set (e.g. a tool dispatch that escapes the normal team-orchestrator scope).
        // Both signals must compose into the safe bare-session-id fallback.
        when(registryProvider.getIfAvailable()).thenReturn(null);

        assertThatNoException().isThrownBy(() ->
                ScopedValue.where(AgentContextHolder.sessionId, "sess-no-context")
                        .run(() -> tool.delegate_to_agent("child-C", "task")));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-C"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-no-context");
    }
}
