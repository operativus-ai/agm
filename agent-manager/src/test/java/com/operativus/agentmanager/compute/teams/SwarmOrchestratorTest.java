package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import com.operativus.agentmanager.compute.service.AgentClientFactory;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SwarmOrchestratorTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient.Builder builder;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private TransitionValidator transitionValidator;

    @Mock
    private TierEscalationValidator tierEscalationValidator;

    @Mock
    private EphemeralSwarmContext ephemeralSwarmContext;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AgentRunEventBus eventBus;

    @Mock
    private AgentOperations runner;

    private SwarmOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // PR #394 routed Swarm through the orchestration advisor chain via
        // AgentClientFactory.buildOrchestrationChatClient(builder). Bug #5 fix moved
        // the build call to execute time and added an AgentDefinition-aware overload
        // so def.modelId() takes effect — stub the new overload here.
        AgentClientFactory factory = mock(AgentClientFactory.class);
        lenient().when(factory.buildOrchestrationChatClient(any(AgentDefinition.class), eq(builder))).thenReturn(chatClient);
        // REQ-DR-2: flag-off OrchestratorMembers behaves like the pre-REQ-DR-2 inline filter.
        OrchestratorMembers orchestratorMembers = new OrchestratorMembers(java.util.Optional.empty(), false);
        TeamMemberHumanReviewGate gate = mock(TeamMemberHumanReviewGate.class);
        orchestrator = new SwarmOrchestrator(builder, agentRegistry, transitionValidator,
                tierEscalationValidator, ephemeralSwarmContext, eventPublisher, eventBus,
                factory, orchestratorMembers, gate);
    }

    @Test
    void supports_ReturnsTrueOnlyForSwarm() {
        assertTrue(orchestrator.supports("SWARM"));
        assertTrue(orchestrator.supports("swarm"));
        assertFalse(orchestrator.supports("ROUTER"));
        assertEquals("SWARM", orchestrator.getStrategyName());
    }

    @Test
    void execute_NoMembersFound_ThrowsBusinessValidationException() {
        // Tier 2.5 (PR #394) tightened the empty-members guard: instead of returning a graceful
        // "Team has no members" message, Swarm now throws BusinessValidationException so the
        // caller can surface a 4xx rather than a misleading 200 with empty content.
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        lenient().when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner)
        );
        // REQ-DR-2 refactor consolidated the "no members configured" + "no valid active members"
        // error paths in SwarmOrchestrator since the two paths were semantically identical.
        assertTrue(ex.getMessage().contains("No valid, active members"),
                "Consolidated error message must describe the no-roster condition; got: " + ex.getMessage());
    }

    @Test
    void execute_MembersInactive_ThrowsBusinessValidationException() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        lenient().when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition inactiveChild = mock(AgentDefinition.class);
        when(inactiveChild.active()).thenReturn(false);

        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(inactiveChild);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner)
        );
        assertTrue(ex.getMessage().contains("No valid, active members found"));
    }

    @Test
    void execute_NoSubtasksRequired_ReturnsOrchestratorMessage() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition childAgent = mock(AgentDefinition.class);
        when(childAgent.id()).thenReturn("child-1");
        when(childAgent.name()).thenReturn("Child One");
        when(childAgent.description()).thenReturn("Desc");
        when(childAgent.active()).thenReturn(true);
        when(childAgent.maintenanceMode()).thenReturn(false);

        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent);

        SwarmOrchestrator.OrchestratorResponse decision = new SwarmOrchestrator.OrchestratorResponse(List.of(), "not needed");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(SwarmOrchestrator.OrchestratorResponse.class))
                .thenReturn(decision);

        String result = orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner);
        assertTrue(result.contains("Swarm Orchestrator determined no subtasks were required."));
    }

    @Test
    void execute_ValidSubtasks_ExecutesInScopeAndAggregates() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1", "child-2"));

        AgentDefinition childAgent1 = mock(AgentDefinition.class);
        when(childAgent1.id()).thenReturn("child-1");
        when(childAgent1.name()).thenReturn("Child One");
        when(childAgent1.description()).thenReturn("Desc 1");
        when(childAgent1.active()).thenReturn(true);
        when(childAgent1.maintenanceMode()).thenReturn(false);

        AgentDefinition childAgent2 = mock(AgentDefinition.class);
        when(childAgent2.id()).thenReturn("child-2");
        when(childAgent2.name()).thenReturn("Child Two");
        when(childAgent2.description()).thenReturn("Desc 2");
        when(childAgent2.active()).thenReturn(true);
        when(childAgent2.maintenanceMode()).thenReturn(false);

        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent1);
        when(agentRegistry.findById(eq("child-2"), any())).thenReturn(childAgent2);

        SwarmOrchestrator.SwarmSubtask subtask1 = new SwarmOrchestrator.SwarmSubtask("child-1", "do part A");
        SwarmOrchestrator.SwarmSubtask subtask2 = new SwarmOrchestrator.SwarmSubtask("child-2", "do part B");

        SwarmOrchestrator.OrchestratorResponse decision = new SwarmOrchestrator.OrchestratorResponse(List.of(subtask1, subtask2), "rationale output");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(SwarmOrchestrator.OrchestratorResponse.class))
                .thenReturn(decision);

        RunResponse runResponse1 = mock(RunResponse.class);
        when(runResponse1.content()).thenReturn("worker A finished");
        RunResponse runResponse2 = mock(RunResponse.class);
        when(runResponse2.content()).thenReturn("worker B finished");

        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(runResponse1);
            
        when(runner.run(eq("child-2"), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(runResponse2);

        String result = orchestrator.execute(rootAgent, "hello context", null, "session", "user", "org", false, runner);

        // Core aggregated output checks
        assertTrue(result.contains("### Swarm Synthesis:"));
        assertTrue(result.contains("rationale output"));
        assertTrue(result.contains("**Worker [child-1]**"));
        assertTrue(result.contains("worker A finished"));
        assertTrue(result.contains("**Worker [child-2]**"));
        assertTrue(result.contains("worker B finished"));

        // Confirm validations were called for both routes
        verify(transitionValidator).validate("root", "root", "child-1");
        verify(transitionValidator).validate("root", "root", "child-2");
        
        verify(tierEscalationValidator).validate(eq("root"), eq("child-1"), anyString());
        verify(tierEscalationValidator).validate(eq("root"), eq("child-2"), anyString());

        // Verify EphemeralSwarmContext handling
        verify(ephemeralSwarmContext, times(2)).mergeFrom(anyString(), anyString());
        verify(ephemeralSwarmContext, times(1)).flush(anyString());
    }

    @Test
    void execute_WithBoundRunId_EmitsOrchestratorDecisionEvent() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition childAgent1 = mock(AgentDefinition.class);
        when(childAgent1.id()).thenReturn("child-1");
        when(childAgent1.name()).thenReturn("Child One");
        when(childAgent1.description()).thenReturn("Desc 1");
        when(childAgent1.active()).thenReturn(true);
        when(childAgent1.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent1);

        SwarmOrchestrator.SwarmSubtask subtask = new SwarmOrchestrator.SwarmSubtask("child-1", "part A");
        SwarmOrchestrator.OrchestratorResponse decision = new SwarmOrchestrator.OrchestratorResponse(List.of(subtask), "split by capability");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(SwarmOrchestrator.OrchestratorResponse.class))
                .thenReturn(decision);

        RunResponse workerResponse = mock(RunResponse.class);
        when(workerResponse.content()).thenReturn("worker A finished");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(workerResponse);

        ScopedValue.where(AgentContextHolder.currentRunId, "run-swarm")
                .where(AgentContextHolder.agentId, "root")
                .run(() -> orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(captor.capture());
        AgentRunEvent event = captor.getValue();
        assertEquals(AgentRunEventType.ORCHESTRATOR_DECISION, event.eventType());
        assertEquals("run-swarm", event.runId());
        assertEquals("SWARM", event.payload().get("mode"));
        assertEquals(1, event.payload().get("subtaskCount"));
        assertEquals(List.of("child-1"), event.payload().get("subtaskAgents"));
        assertEquals("split by capability", event.payload().get("rationale"));
    }

    @Test
    void execute_WithoutBoundRunId_SkipsEventPublish() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition childAgent1 = mock(AgentDefinition.class);
        when(childAgent1.id()).thenReturn("child-1");
        when(childAgent1.name()).thenReturn("Child One");
        when(childAgent1.description()).thenReturn("Desc 1");
        when(childAgent1.active()).thenReturn(true);
        when(childAgent1.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent1);

        SwarmOrchestrator.SwarmSubtask subtask = new SwarmOrchestrator.SwarmSubtask("child-1", "part A");
        SwarmOrchestrator.OrchestratorResponse decision = new SwarmOrchestrator.OrchestratorResponse(List.of(subtask), "rationale");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(SwarmOrchestrator.OrchestratorResponse.class))
                .thenReturn(decision);

        RunResponse workerResponse = mock(RunResponse.class);
        when(workerResponse.content()).thenReturn("ok");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(workerResponse);

        orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner);

        verify(eventBus, never()).publish(any());
    }
}
