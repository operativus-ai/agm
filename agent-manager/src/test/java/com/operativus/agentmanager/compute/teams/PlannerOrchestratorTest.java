package com.operativus.agentmanager.compute.teams;
import static org.mockito.ArgumentMatchers.anyString;

import com.operativus.agentmanager.compute.service.AgentClientFactory;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlannerOrchestrator}. Verifies:
 * - The Dag Generation phase accurately extracts the JSON array of steps.
 * - The Solve phase sequentially executes the sub-steps in topological order.
 * - Centralized Governance Validators (Transition, TierEscalation) are enforced 
 *   prior to each inner runner execution.
 * - Synthesis phase properly aggregates upstream histories.
 */
class PlannerOrchestratorTest {

    private PlannerOrchestrator orchestrator;
    private ChatModel chatModel;
    private AgentRegistry agentRegistry;
    private TransitionValidator transitionValidator;
    private TierEscalationValidator tierEscalationValidator;
    private AgentRunEventBus eventBus;
    private AgentOperations runner;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        org.springframework.ai.chat.prompt.ChatOptions options = mock(org.springframework.ai.chat.prompt.ChatOptions.class);
        org.springframework.ai.chat.prompt.ChatOptions.Builder optBuilder = mock(org.springframework.ai.chat.prompt.ChatOptions.Builder.class);
        when(optBuilder.build()).thenReturn(options);
        when(options.mutate()).thenReturn(optBuilder);
        when(chatModel.getDefaultOptions()).thenReturn(options);
        // Spring AI 2.0.0-SNAPSHOT: DefaultChatClientUtils now reads ChatModel.getOptions()
        // (not getDefaultOptions()); without this stub every orchestration LLM call NPEs and
        // the planner falls back to "unable to decompose".
        when(chatModel.getOptions()).thenReturn(options);

        agentRegistry = mock(AgentRegistry.class);
        transitionValidator = mock(TransitionValidator.class);
        tierEscalationValidator = mock(TierEscalationValidator.class);
        eventBus = mock(AgentRunEventBus.class);
        runner = mock(AgentOperations.class);

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // PR #394 routed Planner through the orchestration advisor chain via
        // AgentClientFactory.buildOrchestrationChatClient(builder). Bug #5 fix moved
        // the build call to execute time and added an AgentDefinition-aware overload
        // so def.modelId() takes effect — stub the new overload here. Pass through to
        // builder.build() so the test exercises the real ChatClient wrapping the mocked
        // ChatModel.
        AgentClientFactory factory = mock(AgentClientFactory.class);
        lenient().when(factory.buildOrchestrationChatClient(any(AgentDefinition.class), eq(builder))).thenReturn(builder.build());

        // REQ-DR-2: OrchestratorMembers component takes Optional<MemberResolver> + flag.
        // For unit tests the flag is left at default false → byte-identical to the
        // pre-REQ-DR-2 inline filter, so the existing test expectations carry over.
        OrchestratorMembers orchestratorMembers = new OrchestratorMembers(java.util.Optional.empty(), false);

        TeamMemberHumanReviewGate gate = mock(TeamMemberHumanReviewGate.class);
        orchestrator = new PlannerOrchestrator(
                builder,
                agentRegistry,
                transitionValidator,
                tierEscalationValidator,
                eventBus,
                factory,
                orchestratorMembers,
                gate
        );
    }

    @Test
    @DisplayName("Strategy name should be PLANNER")
    void testStrategyName() {
        assertThat(orchestrator.getStrategyName()).isEqualTo("PLANNER");
        assertThat(orchestrator.supports("PLANNER")).isTrue();
        assertThat(orchestrator.supports("router")).isFalse();
    }

    @Test
    @DisplayName("execute() correctly performs Plan -> Solve -> Synthesize workflow with Governance validations")
    void testExecuteWorkflow() {
        // Mock root agent definition
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("planner-master");
        when(rootAgent.members()).thenReturn(List.of("search-agent", "writer-agent"));

        AgentDefinition searchAgent = mock(AgentDefinition.class);
        when(searchAgent.id()).thenReturn("search-agent");
        when(searchAgent.description()).thenReturn("Searches the web");
        when(searchAgent.active()).thenReturn(true);
        when(searchAgent.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("search-agent"), any())).thenReturn(searchAgent);

        // PR #394 tightened the planner's per-step validator: every planned step's
        // targetAgentId must resolve to a registered agent. This fixture's plan references
        // both search-agent (above) and writer-agent (below).
        AgentDefinition writerAgent = mock(AgentDefinition.class);
        when(writerAgent.id()).thenReturn("writer-agent");
        when(writerAgent.description()).thenReturn("Writes summaries");
        when(writerAgent.active()).thenReturn(true);
        when(writerAgent.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("writer-agent"), any())).thenReturn(writerAgent);
        
        // Phase 1: Mock the Planning LLM response (returning a JSON array of PlannedSteps)
        String planJson = """
            {
                "steps": [
                    {
                        "stepNumber": 1,
                        "targetAgentId": "search-agent",
                        "taskDescription": "Find details on LangChain4j"
                    },
                    {
                        "stepNumber": 2,
                        "targetAgentId": "writer-agent",
                        "taskDescription": "Summarize the findings"
                    }
                ]
            }
            """;
        
        // Phase 3: Mock the Synthesis LLM response
        String synthesisText = "Here is the final synthesized report.";
        
        // Setup ChatModel consecutive responses (1st is plan, 2nd is synthesis)
        ChatResponse planResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(planJson))));
        ChatResponse synthesisResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(synthesisText))));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(planResponse)
                .thenReturn(synthesisResponse);

        RunResponse searchResponse = mock(RunResponse.class);
        when(searchResponse.content()).thenReturn("It is an orchestrator.");
        
        RunResponse writerResponse = mock(RunResponse.class);
        when(writerResponse.content()).thenReturn("Sum: It is an orchestrator.");
        
        when(runner.run(eq("search-agent"), anyString(), any(), anyString(), anyString(), anyString(), eq(false), any()))
            .thenReturn(searchResponse);
        when(runner.run(eq("writer-agent"), anyString(), any(), anyString(), anyString(), anyString(), eq(false), any()))
            .thenReturn(writerResponse);

        // ACT
        String finalOutput = orchestrator.execute(
                rootAgent,
                "Explain langchain4j",
                List.of(),
                "session-123",
                "user-1",
                "org-1",
                false,
                runner
        );

        // ASSERT 
        assertThat(finalOutput).isEqualTo(synthesisText);

        // Verify Validators were called for BOTH steps
        verify(transitionValidator, times(1)).validate("planner-master", "planner-master", "search-agent");
        verify(transitionValidator, times(1)).validate("planner-master", "planner-master", "writer-agent");
        
        verify(tierEscalationValidator, times(1)).validate(eq("planner-master"), eq("search-agent"), anyString());
        verify(tierEscalationValidator, times(1)).validate(eq("planner-master"), eq("writer-agent"), anyString());

        // Verify inner runner was invoked sequentially
        verify(runner, times(2)).run(anyString(), anyString(), any(), anyString(), anyString(), anyString(), eq(false), any());
    }

    @Test
    @DisplayName("execute() with bound runId emits ORCHESTRATOR_DECISION with plan metadata")
    void execute_WithBoundRunId_EmitsOrchestratorDecisionEvent() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("planner-master");
        when(rootAgent.members()).thenReturn(List.of("search-agent"));

        AgentDefinition searchAgent = mock(AgentDefinition.class);
        when(searchAgent.id()).thenReturn("search-agent");
        when(searchAgent.description()).thenReturn("Searches the web");
        when(searchAgent.active()).thenReturn(true);
        when(searchAgent.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("search-agent"), any())).thenReturn(searchAgent);

        String planJson = """
            {"steps":[{"stepNumber":1,"targetAgentId":"search-agent","taskDescription":"Find"}]}
            """;
        ChatResponse planResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(planJson))));
        ChatResponse synthesisResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(planResponse).thenReturn(synthesisResponse);

        RunResponse stepResponse = mock(RunResponse.class);
        when(stepResponse.content()).thenReturn("result");
        when(runner.run(eq("search-agent"), anyString(), any(), anyString(), anyString(), anyString(), eq(false), any()))
                .thenReturn(stepResponse);

        ScopedValue.where(AgentContextHolder.currentRunId, "run-planner")
                .where(AgentContextHolder.agentId, "planner-master")
                .run(() -> orchestrator.execute(rootAgent, "req", List.of(),
                        "session-1", "user-1", "org-1", false, runner));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(captor.capture());
        AgentRunEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AgentRunEventType.ORCHESTRATOR_DECISION);
        assertThat(event.runId()).isEqualTo("run-planner");
        assertThat(event.payload().get("mode")).isEqualTo("PLANNER");
        assertThat(event.payload().get("stepCount")).isEqualTo(1);
        assertThat(event.payload().get("stepAgents")).isEqualTo(List.of("search-agent"));
    }

    @Test
    @DisplayName("execute() without bound runId skips ORCHESTRATOR_DECISION publish")
    void execute_WithoutBoundRunId_SkipsEventPublish() {
        AgentDefinition rootAgent = mock(AgentDefinition.class);
        when(rootAgent.id()).thenReturn("planner-master");
        when(rootAgent.members()).thenReturn(List.of("search-agent"));

        AgentDefinition searchAgent = mock(AgentDefinition.class);
        when(searchAgent.id()).thenReturn("search-agent");
        when(searchAgent.description()).thenReturn("Searches the web");
        when(searchAgent.active()).thenReturn(true);
        when(searchAgent.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("search-agent"), any())).thenReturn(searchAgent);

        String planJson = """
            {"steps":[{"stepNumber":1,"targetAgentId":"search-agent","taskDescription":"Find"}]}
            """;
        ChatResponse planResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(planJson))));
        ChatResponse synthesisResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(planResponse).thenReturn(synthesisResponse);

        RunResponse stepResponse = mock(RunResponse.class);
        when(stepResponse.content()).thenReturn("result");
        when(runner.run(eq("search-agent"), anyString(), any(), anyString(), anyString(), anyString(), eq(false), any()))
                .thenReturn(stepResponse);

        orchestrator.execute(rootAgent, "req", List.of(),
                "session-1", "user-1", "org-1", false, runner);

        verify(eventBus, never()).publish(any());
    }
}
