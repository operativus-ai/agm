package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import ai.operativus.agentmanager.compute.teams.TierEscalationValidator;
import ai.operativus.agentmanager.compute.teams.TransitionValidator;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelegationToolTest {

    @Mock
    private ObjectProvider<AgentOperations> agentRunnerProvider;

    @Mock
    private AgentOperations agentRunner;

    @Mock
    private ObjectProvider<ai.operativus.agentmanager.core.model.definitions.AgentRegistry> agentRegistryProvider;

    @Mock
    private TransitionValidator transitionValidator;

    @Mock
    private TierEscalationValidator tierEscalationValidator;

    @Mock
    private EphemeralSwarmContext ephemeralSwarmContext;

    @Mock
    private AgentRunEventBus eventBus;

    private DelegationTool tool;

    @BeforeEach
    void setUp() {
        tool = new DelegationTool(agentRunnerProvider, agentRegistryProvider, transitionValidator, tierEscalationValidator,
                ephemeralSwarmContext, eventBus);
    }

    @Test
    void delegate_SuccessfulRun_EmitsStartThenCompleteWithChildRunId() {
        when(agentRunnerProvider.getIfAvailable()).thenReturn(agentRunner);
        RunResponse childResponse = new RunResponse(
                "child-run-42", "sess-parent", "child answer", Map.of(), List.of(), List.of(),
                RunStatus.COMPLETED, null);
        when(agentRunner.run(eq("target-agent"), eq("do it"), isNull(), any(), any(), any(), anyBoolean(), isNull()))
                .thenReturn(childResponse);

        ScopedValue.where(AgentContextHolder.currentRunId, "parent-run")
                .where(AgentContextHolder.agentId, "parent-agent")
                .where(AgentContextHolder.sessionId, "sess-parent")
                .where(AgentContextHolder.orgId, "org-1")
                .run(() -> {
                    String result = tool.delegate_to_agent("target-agent", "do it");
                    assertEquals("child answer", result);
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertEquals(AgentRunEventType.DELEGATION_START, events.get(0).eventType());
        assertEquals("parent-run", events.get(0).runId());
        assertEquals("parent-agent", events.get(0).agentId());
        assertEquals("target-agent", events.get(0).payload().get("targetAgentId"));
        assertEquals("do it".length(), events.get(0).payload().get("taskLength"));

        assertEquals(AgentRunEventType.DELEGATION_COMPLETE, events.get(1).eventType());
        assertEquals("ok", events.get(1).payload().get("status"));
        assertEquals("child-run-42", events.get(1).payload().get("childRunId"));
        assertEquals("child answer".length(), events.get(1).payload().get("contentLength"));
    }

    @Test
    void delegate_TargetAgentNotFound_EmitsStartThenCompleteWithErrorStatus() {
        when(agentRunnerProvider.getIfAvailable()).thenReturn(agentRunner);
        when(agentRunner.run(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResourceNotFoundException("agent", "missing-agent"));

        ScopedValue.where(AgentContextHolder.currentRunId, "parent-run-2")
                .where(AgentContextHolder.agentId, "parent-agent")
                .run(() -> {
                    String result = tool.delegate_to_agent("missing-agent", "task");
                    assertTrue(result.contains("unavailable"), "error message should include 'unavailable'");
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertEquals(AgentRunEventType.DELEGATION_START, events.get(0).eventType());
        assertEquals(AgentRunEventType.DELEGATION_COMPLETE, events.get(1).eventType());
        assertEquals("error", events.get(1).payload().get("status"));
        assertEquals("ResourceNotFoundException", events.get(1).payload().get("errorClass"));
    }

    @Test
    void delegate_WithoutBoundRunId_SkipsEventPublish() {
        when(agentRunnerProvider.getIfAvailable()).thenReturn(agentRunner);
        RunResponse childResponse = new RunResponse(
                "child-run", null, "ok", Map.of(), List.of(), List.of(), RunStatus.COMPLETED, null);
        when(agentRunner.run(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(childResponse);

        // No ScopedValue scope — currentRunId unbound
        String result = tool.delegate_to_agent("target-agent", "task");
        assertEquals("ok", result);

        verify(eventBus, never()).publish(any());
    }
}
