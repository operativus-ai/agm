package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import com.operativus.agentmanager.compute.teams.TierEscalationValidator;
import com.operativus.agentmanager.compute.teams.TransitionValidator;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin the §9 MEM-2 wiring inside {@link DelegationTool}. The tool
 * now consults the delegating parent's {@link AgentDefinition#isolateMemory()} flag via a
 * lazy {@code ObjectProvider<AgentRegistry>} and routes the resolved {@code sessionId}
 * through {@link com.operativus.agentmanager.compute.teams.OrchestrationMemoryScopes#memberConversationId}.
 *
 * <p>Without these tests a future refactor could quietly drop the registry lookup and
 * make every delegated child share the parent session — defeating the §9 MEM-2 contract.
 */
class DelegationToolMemoryScopeTest {

    private AgentOperations runner;
    private AgentRegistry registry;
    private DelegationTool tool;
    private ObjectProvider<AgentOperations> runnerProvider;
    private ObjectProvider<AgentRegistry> registryProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runner = mock(AgentOperations.class);
        registry = mock(AgentRegistry.class);
        runnerProvider = (ObjectProvider<AgentOperations>) mock(ObjectProvider.class);
        registryProvider = (ObjectProvider<AgentRegistry>) mock(ObjectProvider.class);
        when(runnerProvider.getIfAvailable()).thenReturn(runner);
        when(registryProvider.getIfAvailable()).thenReturn(registry);

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
    void teamParentWithIsolateMemoryTrue_derivesPerChildConversationId() {
        when(registry.findById(eq("team-1"), any())).thenReturn(team("team-1", true));

        invokeWithContext("team-1", "sess-X", () -> tool.delegate_to_agent("child-A", "task"));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-A"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-X::member::child-A");
    }

    @Test
    void teamParentWithIsolateMemoryFalse_passesBareSessionThrough() {
        when(registry.findById(eq("team-2"), any())).thenReturn(team("team-2", false));

        invokeWithContext("team-2", "sess-Y", () -> tool.delegate_to_agent("child-B", "task"));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-B"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-Y");
    }

    @Test
    void singleAgentParent_isolateMemoryTreatedAsOff_bareSessionPassedThrough() {
        // Single agents have isolateMemory=false on construction (registry maps it that way).
        when(registry.findById(eq("solo-1"), any())).thenReturn(team("solo-1", false));

        invokeWithContext("solo-1", "sess-Z", () -> tool.delegate_to_agent("child-C", "task"));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-C"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-Z");
    }

    @Test
    void parentDefinitionUnresolvable_fallsBackToBareSession() {
        // Registry is available but findById returns null (parent agent was deleted mid-run).
        when(registry.findById(eq("ghost"), any())).thenReturn(null);

        invokeWithContext("ghost", "sess-Q", () -> tool.delegate_to_agent("child-D", "task"));

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-D"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-Q");
    }

    @Test
    void parentAgentIdMissingFromContext_fallsBackToBareSession() {
        // No AgentContextHolder.agentId scoped value at all — parent unknown.
        ScopedValueRunner runnable = () -> tool.delegate_to_agent("child-E", "task");
        ScopedValue.where(AgentContextHolder.sessionId, "sess-NoParent")
                .run(runnable::run);

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq("child-E"), eq("task"), any(),
                sessionCaptor.capture(), any(), any(), anyBoolean(), any());
        assertThat(sessionCaptor.getValue()).isEqualTo("sess-NoParent");
    }

    @FunctionalInterface
    private interface ScopedValueRunner {
        void run();
    }

    private static void invokeWithContext(String parentAgentId, String sessionId, Runnable r) {
        ScopedValue.where(AgentContextHolder.agentId, parentAgentId)
                .where(AgentContextHolder.sessionId, sessionId)
                .run(r);
    }

    private static AgentDefinition team(String id, boolean isolate) {
        return new AgentDefinition(
                id, id, "desc", "instr", "gpt-x",
                null, null, null, null,
                false, true, "SEQUENTIAL",
                List.of("m-a", "m-b"),
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, com.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                isolate, null, null, null);
    }
}
