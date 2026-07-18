package ai.operativus.agentmanager.compute.teams;
import static org.mockito.ArgumentMatchers.eq;

import ai.operativus.agentmanager.compute.service.AgentClientFactory;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.registry.AgentOperations;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RouterOrchestratorTest {

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
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AgentRunEventBus eventBus;

    @Mock
    private AgentOperations runner;

    private RouterOrchestrator orchestrator;
    private AgentClientFactory factory;

    @BeforeEach
    void setUp() {
        // PR #394 routed Router through the orchestration advisor chain via
        // AgentClientFactory.buildOrchestrationChatClient(builder). Bug #5 fix moved
        // the build call to execute time and added an AgentDefinition-aware overload
        // so def.modelId() takes effect — stub the new overload here.
        factory = mock(AgentClientFactory.class);
        lenient().when(factory.buildOrchestrationChatClient(any(AgentDefinition.class), eq(builder))).thenReturn(chatClient);
        // REQ-DR-2: flag-off OrchestratorMembers behaves like the pre-REQ-DR-2 inline filter.
        OrchestratorMembers orchestratorMembers = new OrchestratorMembers(java.util.Optional.empty(), false);
        TeamMemberHumanReviewGate gate = mock(TeamMemberHumanReviewGate.class);
        orchestrator = new RouterOrchestrator(builder, agentRegistry, transitionValidator,
                tierEscalationValidator, eventPublisher, eventBus, factory, orchestratorMembers, gate);
    }

    @Test
    void supports_ReturnsTrueOnlyForRouter() {
        assertTrue(orchestrator.supports("ROUTER"));
        assertTrue(orchestrator.supports("router"));
        assertFalse(orchestrator.supports("SWARM"));
        assertEquals("ROUTER", orchestrator.getStrategyName());
    }

    @Test
    void execute_NoMembersFound_ThrowsException() {
        // REQ-DR-2 refactor consolidated the "no members defined" + "no valid active members"
        // error paths into a single BusinessValidationException since they were semantically
        // identical (both mean: nothing to route to). Test updated to reflect the new contract.
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        lenient().when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of());

        ai.operativus.agentmanager.core.exception.BusinessValidationException ex = assertThrows(
                ai.operativus.agentmanager.core.exception.BusinessValidationException.class, () ->
                        orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner)
        );
        assertTrue(ex.getMessage().contains("No valid, active members"),
                "Consolidated error message must describe the no-routable-members condition; got: " + ex.getMessage());
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
    void execute_PassesRootAgentToFactory_ForPerTeamModelResolution() {
        // Bug #5 regression guard: Router must call buildOrchestrationChatClient(rootAgent, builder)
        // — passing the team's AgentDefinition — so that def.modelId() takes effect via
        // AgentClientFactory. A revert to the cached-at-construction-time pattern would fail
        // this assertion: the factory would never see the AgentDefinition at execute time.
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("router-root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition childAgent = mock(AgentDefinition.class);
        when(childAgent.id()).thenReturn("child-1");
        when(childAgent.name()).thenReturn("Child One");
        when(childAgent.description()).thenReturn("Desc");
        when(childAgent.active()).thenReturn(true);
        when(childAgent.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent);

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("child-1", "rationale");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        RunResponse runResponse = mock(RunResponse.class);
        when(runResponse.content()).thenReturn("ok");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any())).thenReturn(runResponse);

        orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner);

        ArgumentCaptor<AgentDefinition> defCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(factory, atLeastOnce()).buildOrchestrationChatClient(defCaptor.capture(), eq(builder));
        assertSame(rootAgent, defCaptor.getValue(),
                "Router must pass the team's AgentDefinition to the factory so def.modelId() is honored");
    }

    @Test
    void execute_ValidMembers_RoutesAndRunsSuccessfully() {
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

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("child-1", "because");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        RunResponse runResponse = mock(RunResponse.class);
        when(runResponse.content()).thenReturn("worker output");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(runResponse);

        String result = orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner);

        assertEquals("worker output", result);
        verify(transitionValidator).validate("root", "root", "child-1");
        verify(tierEscalationValidator).validate(eq("root"), eq("child-1"), anyString());
    }

    @Test
    void execute_WithBoundRunId_EmitsOrchestratorDecisionEvent() {
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

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("child-1", "best fit");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        RunResponse runResponse = mock(RunResponse.class);
        when(runResponse.content()).thenReturn("ok");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(runResponse);

        ScopedValue.where(AgentContextHolder.currentRunId, "run-router")
                .where(AgentContextHolder.agentId, "root")
                .run(() -> orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(captor.capture());
        AgentRunEvent event = captor.getValue();
        assertEquals(AgentRunEventType.ORCHESTRATOR_DECISION, event.eventType());
        assertEquals("run-router", event.runId());
        assertEquals("ROUTER", event.payload().get("mode"));
        assertEquals("child-1", event.payload().get("targetAgentId"));
        assertEquals("best fit", event.payload().get("rationale"));
        assertEquals(1, event.payload().get("candidateCount"));
    }

    @Test
    void execute_WithoutBoundRunId_SkipsEventPublish() {
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

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("child-1", "rationale");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        RunResponse runResponse = mock(RunResponse.class);
        when(runResponse.content()).thenReturn("ok");
        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(runResponse);

        orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner);

        verify(eventBus, never()).publish(any());
    }

    @Test
    void execute_InvalidAgentDecision_ThrowsRuntimeException() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("root");
        when(rootAgent.members()).thenReturn(List.of("child-1"));

        AgentDefinition childAgent = mock(AgentDefinition.class);
        when(childAgent.id()).thenReturn("child-1");
        when(childAgent.active()).thenReturn(true);
        when(childAgent.maintenanceMode()).thenReturn(false);

        when(agentRegistry.findById(eq("child-1"), any())).thenReturn(childAgent);

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("invalid-child", "bad choice");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner)
        );
        assertTrue(ex.getMessage().contains("invalid agent ID"));
    }

    @Test
    void execute_memberPaused_throwsTeamMemberPausedException() {
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

        RouterOrchestrator.RouterDecision decision = new RouterOrchestrator.RouterDecision("child-1", "best fit");
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .call()
                .entity(RouterOrchestrator.RouterDecision.class))
                .thenReturn(decision);

        ai.operativus.agentmanager.core.model.RequiredAction childRA =
                ai.operativus.agentmanager.core.model.RequiredAction.toolApproval(
                        "delete_file", "{}", "approval-1", "trace", "lineage", "depth");
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("requiredAction", childRA);
        RunResponse pausedResp = new RunResponse("paused-child-runid", "session", "tool paused",
                meta, java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                ai.operativus.agentmanager.core.model.enums.RunStatus.PAUSED, null);

        when(runner.run(eq("child-1"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pausedResp);

        ai.operativus.agentmanager.core.exception.TeamMemberPausedException ex = assertThrows(
                ai.operativus.agentmanager.core.exception.TeamMemberPausedException.class,
                () -> orchestrator.execute(rootAgent, "hello", null, "session", "user", "org", false, runner));
        assertEquals("paused-child-runid", ex.getPausedRunId());
        assertEquals("child-1", ex.getPausedAgentId());
        assertEquals("delete_file", ex.getRequiredAction().tool());
    }
}
