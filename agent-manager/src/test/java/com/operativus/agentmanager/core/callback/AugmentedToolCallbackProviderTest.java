package com.operativus.agentmanager.core.callback;

import com.operativus.agentmanager.compute.advisor.HitlAdvisor;
import com.operativus.agentmanager.compute.service.ToolCompressionService;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AugmentedToolCallbackProviderTest {

    @Mock
    private ToolCompressionService compressionService;

    @Mock
    private AgentRunEventBus eventBus;

    @Mock
    private ToolCallback delegate;

    @Mock
    private ToolDefinition toolDefinition;

    @Mock
    private HitlAdvisor hitlAdvisor;

    private static final String TOOL_NAME = "search_knowledge";
    private static final String TOOL_INPUT = "{\"query\":\"hello\"}";

    private ToolCallback buildWrapped() {
        when(delegate.getToolDefinition()).thenReturn(toolDefinition);
        when(toolDefinition.name()).thenReturn(TOOL_NAME);
        // search_knowledge is not in the HITL set — requiresHitl returns false (default Mockito).
        AugmentedToolCallbackProvider provider = new AugmentedToolCallbackProvider(compressionService, eventBus, false, hitlAdvisor);
        List<ToolCallback> wrapped = provider.wrap(List.of(delegate), null);
        return wrapped.get(0);
    }

    @Test
    void call_SuccessfulInvocation_EmitsInvokedThenCompletedWithOkStatus() {
        ToolCallback wrapped = buildWrapped();
        when(delegate.call(TOOL_INPUT)).thenReturn("RESULT-OK");
        when(compressionService.compressIfRequired(anyString(), anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

        ScopedValue.where(AgentContextHolder.currentRunId, "run-xyz")
                .where(AgentContextHolder.agentId, "agent-xyz")
                .where(AgentContextHolder.sessionId, "sess-xyz")
                .where(AgentContextHolder.orgId, "org-xyz")
                .run(() -> {
                    String result = wrapped.call(TOOL_INPUT);
                    assertEquals("RESULT-OK", result);
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertEquals(AgentRunEventType.TOOL_INVOKED, events.get(0).eventType());
        assertEquals("run-xyz", events.get(0).runId());
        assertEquals("agent-xyz", events.get(0).agentId());
        assertEquals("sess-xyz", events.get(0).sessionId());
        assertEquals("org-xyz", events.get(0).orgId());
        assertEquals(TOOL_NAME, events.get(0).payload().get("toolName"));
        assertEquals(TOOL_INPUT.length(), events.get(0).payload().get("inputLength"));

        assertEquals(AgentRunEventType.TOOL_COMPLETED, events.get(1).eventType());
        assertEquals("ok", events.get(1).payload().get("status"));
        assertEquals("RESULT-OK".length(), events.get(1).payload().get("outputLength"));
    }

    @Test
    void call_DelegateThrows_EmitsInvokedThenCompletedWithErrorStatus() {
        ToolCallback wrapped = buildWrapped();
        when(delegate.call(TOOL_INPUT)).thenThrow(new IllegalStateException("boom"));

        ScopedValue.where(AgentContextHolder.currentRunId, "run-err")
                .where(AgentContextHolder.agentId, "agent-err")
                .run(() -> {
                    assertThrows(IllegalStateException.class, () -> wrapped.call(TOOL_INPUT));
                });

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(2)).publish(captor.capture());

        List<AgentRunEvent> events = captor.getAllValues();
        assertEquals(AgentRunEventType.TOOL_INVOKED, events.get(0).eventType());
        assertEquals(AgentRunEventType.TOOL_COMPLETED, events.get(1).eventType());
        assertEquals("error", events.get(1).payload().get("status"));
    }

    @Test
    void call_WithoutBoundRunId_SkipsEventPublish() {
        ToolCallback wrapped = buildWrapped();
        when(delegate.call(TOOL_INPUT)).thenReturn("RESULT");
        when(compressionService.compressIfRequired(anyString(), anyString(), any())).thenAnswer(inv -> inv.getArgument(1));

        // No ScopedValue scope — currentRunId is unbound
        String result = wrapped.call(TOOL_INPUT);
        assertEquals("RESULT", result);

        verify(eventBus, never()).publish(any());
    }

    @Test
    void call_BusFailure_DoesNotBreakToolExecution() {
        ToolCallback wrapped = buildWrapped();
        when(delegate.call(TOOL_INPUT)).thenReturn("RESULT");
        when(compressionService.compressIfRequired(anyString(), anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        doThrow(new RuntimeException("bus down")).when(eventBus).publish(any());

        ScopedValue.where(AgentContextHolder.currentRunId, "run-safe")
                .run(() -> {
                    String result = wrapped.call(TOOL_INPUT);
                    assertEquals("RESULT", result);
                });

        // Both INVOKED and COMPLETED attempted despite failure on first
        verify(eventBus, times(2)).publish(any());
    }
}
