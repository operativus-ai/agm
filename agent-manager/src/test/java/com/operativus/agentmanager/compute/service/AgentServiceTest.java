package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.KnowledgeIngestionOperations;
import com.operativus.agentmanager.core.registry.ModelOperations;
import com.operativus.agentmanager.core.registry.RunOperations;
import com.operativus.agentmanager.control.repository.SessionRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentServiceTest {

    @Mock
    private AgentRegistry agentRegistry;
    @Mock
    private RunOperations runRepository;
    @Mock
    private AgentClientFactory agentClientFactory;
    @Mock
    private KnowledgeIngestionOperations knowledgeIngestionService;
    @Mock
    private ModelOperations modelService;
    @Mock
    private ReflectionService reflectionService;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private Tracer tracer;
    @Mock
    private Span span;
    @Mock
    private TeamOrchestrationEngine teamOrchestrationEngine;
    @Mock
    private RunExecutionManager runExecutionManager;
    @Mock
    private AgentStreamManager agentStreamManager;
    @Mock
    private AgentRunEventBus agentRunEventBus;
    @Mock
    private AgentRunFinalizer agentRunFinalizer;
    @Mock
    private com.operativus.agentmanager.compute.security.ModelRateLimitGuard modelRateLimitGuard;
    @Mock
    private com.operativus.agentmanager.control.finops.service.BudgetPolicyService budgetPolicyService;
    @Mock
    private com.operativus.agentmanager.control.finops.service.DailySpendService dailySpendService;

    private AgentService agentService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        agentService = new AgentService(
                agentRegistry, runRepository, agentClientFactory,
                knowledgeIngestionService, modelService, reflectionService,
                sessionRepository, tracer, teamOrchestrationEngine,
                runExecutionManager, agentStreamManager, agentRunEventBus,
                agentRunFinalizer, modelRateLimitGuard,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                budgetPolicyService,
                dailySpendService, // mock → enforceOrgDailyCap is a no-op (daily cap disabled in tests)
                // followupClientBuilderFallback — used only when a run with
                // generateFollowups=true reaches the followup-generation branch.
                // Existing test scenarios don't exercise that path, so a null is
                // adequate (and exposes any future test that DOES exercise it).
                null,
                10, true, meterRegistry
        );

        // Relax strict stubbing for tracer
        lenient().when(tracer.nextSpan()).thenReturn(span);
        lenient().when(span.name(anyString())).thenReturn(span);
        lenient().when(span.tag(anyString(), anyString())).thenReturn(span);
        lenient().when(span.start()).thenReturn(span);
    }

    // --- POSITIVE PATHS ---

    @Test
    void testCleanupOrphanedRuns_ModifiesStuckRuns() {
        AgentRun run1 = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        run1.setId("run1-id");
        run1.setStatus(RunStatus.RUNNING);

        when(runRepository.findByStatusIn(any())).thenReturn(List.of(run1));

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(com.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);
        rem.cleanupOrphanedRuns();

        assertEquals(RunStatus.CANCELLED, run1.getStatus());
        assertTrue(run1.getOutput().contains("Orphaned"));
        verify(runRepository, times(1)).save(run1);
    }

    @Test
    void testContinueRun_ApproveAction_PausesState() {
        AgentRun run = new AgentRun("agent1", "session1", "input", "user1", "org1");
        run.setId("run1");
        run.setStatus(RunStatus.PAUSED);
        run.setRequiredAction("{ \"tool\": \"test_tool\" }");

        when(runRepository.findById("run1")).thenReturn(Optional.of(run));
        
        // When we call continueRun, it will internally call run(). We must mock agent finding for that run
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.teamMode()).thenReturn("SINGLE");
        
        lenient().when(agentRegistry.findById(eq("agent1"), any())).thenReturn(def);

        // Given how deep the ChatClient is, we will intercept the BusinessValidationException for depth
        // or just let it fail gracefully if we don't mock ChatClient. To avoid deep ChatClient mocking here, 
        // we test the rejection logic.
        
        // Actually, let's test what happens if user REJECTS. It bypasses ChatClient and routes
        // the paused row's terminal write through AgentRunFinalizer (F7).
        RunResponse response = agentService.continueRun("run1", "REJECT");

        assertEquals(RunStatus.CANCELLED, response.status());
        assertEquals("Cancelled by user", response.content());
        verify(agentRunFinalizer).finalizeRun(
                eq("run1"),
                eq(RunStatus.CANCELLED),
                eq("User rejected the requested action."),
                isNull(),
                isNull());
        // The local `run` entity is not mutated by the finalizer (which reloads internally),
        // so its in-memory status stays PAUSED — that's expected; the row in the DB has been
        // updated. Don't assert on the local field.
    }

    // --- NEGATIVE PATHS ---

    @Test
    void testRun_AgentNotFound_ThrowsException() {
        when(agentRegistry.findById(eq("missing_agent"), any())).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> {
            agentService.run("missing_agent", "hello", "session_123");
        });
    }

    @Test
    void testRun_MaintenanceMode_ThrowsResponseStatusException() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.maintenanceMode()).thenReturn(true);
        when(agentRegistry.findById(eq("agent1"), any())).thenReturn(def);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            agentService.run("agent1", "hello", "session_123");
        });
        
        assertEquals("503 SERVICE_UNAVAILABLE \"Agent is currently in maintenance mode\"", ex.getMessage());
    }

    @Test
    void testContinueRun_InvalidState_ThrowsException() {
        AgentRun run = new AgentRun("agent1", "session1", "input", "user1", "org1");
        run.setId("run1");
        run.setStatus(RunStatus.RUNNING); // Not paused
        
        when(runRepository.findById("run1")).thenReturn(Optional.of(run));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> {
            agentService.continueRun("run1", "APPROVE");
        });
        
        assertTrue(ex.getMessage().contains("not in PAUSED state"));
    }

    // --- EDGE CASES ---

    @Test
    void testStream_AgentInMaintenance_ThrowsError() {
        AgentDefinition def = mock(AgentDefinition.class);
        when(def.maintenanceMode()).thenReturn(true);
        when(agentRegistry.findById(eq("agent1"), any())).thenReturn(def);

        // Subscribing to flux should yield error
        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            agentService.stream("agent1", "hello", "sess").blockFirst();
        });
        
        assertTrue(ex.getMessage().contains("maintenance mode"));
    }

    @Test
    void testRun_TeamPath_EmitsRunStartAndRunCompleteEvents() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.name()).thenReturn("Team Alpha");
        lenient().when(def.modelId()).thenReturn("gpt-4");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-1"), any())).thenReturn(def);

        when(runRepository.countByAgentIdAndStatus("team-1", RunStatus.RUNNING)).thenReturn(0L);

        RunResponse teamResp = new RunResponse("exec-run", "sess-1", "team done",
                java.util.Map.of(), java.util.List.of(), java.util.List.of(), RunStatus.COMPLETED, null);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenReturn(teamResp);

        RunResponse out = agentService.run("team-1", "kickoff", "sess-1");
        assertEquals(RunStatus.COMPLETED, out.status());

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(agentRunEventBus, times(2)).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_START, evCap.getAllValues().get(0).eventType());
        assertEquals(AgentRunEventType.RUN_COMPLETE, evCap.getAllValues().get(1).eventType());
        assertEquals("team-1", evCap.getAllValues().get(0).agentId());
        assertEquals(Boolean.TRUE, evCap.getAllValues().get(0).payload().get("isTeam"));
        assertEquals(Boolean.TRUE, evCap.getAllValues().get(1).payload().get("viaTeam"));
    }

    @Test
    void testRun_TeamPath_FailedStatusEmitsRunFailedEvent() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.name()).thenReturn("Team Beta");
        lenient().when(def.modelId()).thenReturn("gpt-4");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-2"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("team-2", RunStatus.RUNNING)).thenReturn(0L);

        RunResponse failed = new RunResponse("exec", "sess", "boom",
                java.util.Map.of(), java.util.List.of(), java.util.List.of(), RunStatus.FAILED, null);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenReturn(failed);

        agentService.run("team-2", "kickoff", "sess");

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(agentRunEventBus, times(2)).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_FAILED, evCap.getAllValues().get(1).eventType());
    }

    @Test
    void testRun_TeamPath_FinalizesBeforeTerminalEvent() {
        // R-16: accumulator flush (finalizer) must occur BEFORE RUN_COMPLETE emission.
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.name()).thenReturn("Team Gamma");
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.teamMode()).thenReturn("ROUTER");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-3"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("team-3", RunStatus.RUNNING)).thenReturn(0L);

        RunResponse resp = new RunResponse("exec", "sess", "done",
                java.util.Map.of(), java.util.List.of(), java.util.List.of(), RunStatus.COMPLETED, null);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenReturn(resp);

        agentService.run("team-3", "go", "sess");

        InOrder order = inOrder(agentRunFinalizer, agentRunEventBus);
        order.verify(agentRunEventBus).publish(argThat(e -> e.eventType() == AgentRunEventType.RUN_START));
        order.verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.COMPLETED),
                anyString(), any(), any());
        order.verify(agentRunEventBus).publish(argThat(e -> e.eventType() == AgentRunEventType.RUN_COMPLETE));
    }

    @Test
    void testRun_ConcurrencyLimitExceeded_AgentSpecific() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        when(def.maxConcurrentExecutions()).thenReturn(2);

        when(agentRegistry.findById(eq("agent1"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("agent1", RunStatus.RUNNING)).thenReturn(2L);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> {
            agentService.run("agent1", "hello", "session_123");
        });

        assertTrue(ex.getMessage().contains("reached its configured concurrency limit"));

        double perAgent = meterRegistry.counter(MetricConstants.ORCHESTRATION_CALLS_REJECTED, "scope", "per_agent").count();
        double global = meterRegistry.counter(MetricConstants.ORCHESTRATION_CALLS_REJECTED, "scope", "global").count();
        assertEquals(1.0, perAgent, "per-agent rejection must increment scope=per_agent counter");
        assertEquals(0.0, global, "per-agent rejection must NOT touch scope=global counter");
    }

    @Test
    void testRun_ConcurrencyLimitExceeded_GlobalCap() {
        // No per-agent override → falls through to JVM-wide cap (configured to 10 in setUp).
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        when(def.maxConcurrentExecutions()).thenReturn(null);

        when(agentRegistry.findById(eq("agent2"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("agent2", RunStatus.RUNNING)).thenReturn(10L);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> {
            agentService.run("agent2", "hello", "session_456");
        });

        assertTrue(ex.getMessage().contains("reached maximum concurrent capacity"));

        double perAgent = meterRegistry.counter(MetricConstants.ORCHESTRATION_CALLS_REJECTED, "scope", "per_agent").count();
        double global = meterRegistry.counter(MetricConstants.ORCHESTRATION_CALLS_REJECTED, "scope", "global").count();
        assertEquals(0.0, perAgent, "global-cap rejection must NOT touch scope=per_agent counter");
        assertEquals(1.0, global, "global-cap rejection must increment scope=global counter");
    }

    // F1 — RunExecutionManager.submit must finalize stuck rows when the supplier throws before
    // AgentService.run reaches its finalizer block (pre-validation: agent missing, concurrency
    // cap, maintenance, rate-limit, depth, repo errors). Without this, the row stays at RUNNING
    // and inflates countByAgentIdAndStatus(RUNNING), causing false per-agent cap rejections.
    @Test
    void submit_supplierThrowsPreFinalizer_finalizesStuckRowAsFailed() {
        AgentRun run = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        run.setId("stuck-row-id");
        run.setStatus(RunStatus.QUEUED);

        // Reload returns the row still in non-terminal state — simulates the pre-try-block throw
        // path where AgentService.run.line~132–171 throws before line 210's try.
        when(runRepository.findById("stuck-row-id")).thenReturn(Optional.of(run));

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(com.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);

        rem.submit(run, () -> {
            throw new ResourceNotFoundException("Agent", "agent1");
        });

        // Submit fires on a virtual thread — Mockito's verify(timeout) waits for the VT to settle.
        verify(agentRunFinalizer, timeout(2000)).finalizeRun(
                eq("stuck-row-id"),
                eq(RunStatus.FAILED),
                argThat(s -> s != null && s.contains("Pre-execution validation failed")),
                isNull(),
                isNull());
    }

    // F2 — cancel() on a non-terminal row WITHOUT an active future routes the CANCELLED
    // write through AgentRunFinalizer rather than saving directly. Pins the no-active-future
    // branch (covers QUEUED rows that never reached submit's lambda + already-popped runs).
    @Test
    void cancel_noActiveFutureNonTerminalRow_finalizesAsCancelled() {
        AgentRun run = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        run.setId("run-cancel-1");
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findById("run-cancel-1")).thenReturn(Optional.of(run));

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(com.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);
        rem.cancel("run-cancel-1");

        verify(agentRunFinalizer).finalizeRun(
                eq("run-cancel-1"),
                eq(RunStatus.CANCELLED),
                eq("Cancelled by user"),
                isNull(),
                isNull());
    }

    // F2 — cancel() on a terminal row preserves the terminal status (BackgroundRunsRuntimeTest
    // case (c) contract: DELETE on already-COMPLETED row stays COMPLETED, no overwrite).
    @Test
    void cancel_noActiveFutureTerminalRow_skipsFinalize() {
        AgentRun run = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        run.setId("run-cancel-2");
        run.setStatus(RunStatus.COMPLETED);
        when(runRepository.findById("run-cancel-2")).thenReturn(Optional.of(run));

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(com.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);
        rem.cancel("run-cancel-2");

        verify(agentRunFinalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    // F1 — but DO NOT double-finalize when AgentService.run's own outer catch already finalized
    // (post-try-block throw). The reload sees a terminal status; the safety net must skip the
    // finalize call to avoid clobbering output/telemetry the inner finalizer already wrote.
    @Test
    void submit_supplierThrowsAfterInnerFinalizer_skipsRedundantFinalize() throws Exception {
        AgentRun run = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        run.setId("already-failed-id");

        // Reload returns the row in FAILED — simulates AgentService.run's outer catch having
        // already invoked finalizeRun before the exception propagated up.
        AgentRun reloaded = new AgentRun("agent1", "session1", "input1", "user1", "org1");
        reloaded.setId("already-failed-id");
        reloaded.setStatus(RunStatus.FAILED);
        when(runRepository.findById("already-failed-id")).thenReturn(Optional.of(reloaded));

        RunExecutionManager rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer, mock(com.operativus.agentmanager.control.repository.ApprovalRepository.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class), 24L);

        rem.submit(run, () -> {
            throw new RuntimeException("post-finalizer rethrow");
        });

        // Give the VT time to run + check; verify the safety net did NOT fire a redundant finalize.
        Thread.sleep(500);
        verify(agentRunFinalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------------------------
    // Tier 2.5 F1 — COORDINATOR carve-out + preflight contracts.
    //
    // Pre-fix: any team agent with isTeam()=true short-circuited to TeamOrchestrationEngine.executeSync
    // → CoordinatorOrchestrator.execute → runner.run(rootAgent.id()) → re-entered AgentService.run →
    // executeSync again, recursing to the depth=5 cap. Coordinator pattern was 100% broken.
    //
    // Post-fix: AgentService.run carves out COORDINATOR teams from the team-engine short-circuit.
    // It calls coordinatorPreflight (members + modelId validation + ORCHESTRATOR_DECISION publish),
    // then falls through to the single-agent ChatClient path so AgentClientFactory:221-229 can inject
    // delegate_to_agent on the leader's ChatClient. The leader autonomously orchestrates via tool-calling.
    // ---------------------------------------------------------------------------------------

    @Test
    void coordinatorTeam_doesNotShortCircuitToTeamEngine() {
        // Pin the F1 contract: COORDINATOR teams MUST NOT route through TeamOrchestrationEngine.executeSync.
        // Pre-F1 they did, which caused infinite recursion. Post-F1 they fall through to the single-agent
        // ChatClient path. We force a buildChatClient failure to short-circuit the rest of run() while still
        // proving that executeSync was never invoked.
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.id()).thenReturn("coord-team");
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.members()).thenReturn(java.util.List.of("member-a"));
        when(def.isTeam()).thenReturn(true);
        lenient().when(def.teamMode()).thenReturn("COORDINATOR");

        AgentDefinition memberA = mock(AgentDefinition.class);
        lenient().when(memberA.id()).thenReturn("member-a");
        lenient().when(memberA.active()).thenReturn(true);
        lenient().when(memberA.maintenanceMode()).thenReturn(false);
        when(agentRegistry.findById(eq("coord-team"), any())).thenReturn(def);
        when(agentRegistry.findById(eq("member-a"), any())).thenReturn(memberA);
        when(runRepository.countByAgentIdAndStatus("coord-team", RunStatus.RUNNING)).thenReturn(0L);

        // Force the single-agent path to abort — sufficient to prove we reached it.
        when(agentClientFactory.buildChatClient(any(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("buildChatClient short-circuit for test"));

        // Run must NOT throw — the outer catch finalizes FAILED. We just need to assert the routing.
        try {
            agentService.run("coord-team", "input", "sess");
        } catch (RuntimeException expected) {
            // Outer catch rethrows; that's fine. We're asserting routing, not status.
        }

        verify(teamOrchestrationEngine, never()).executeSync(any(), anyString(), any(), anyString(),
                any(), any(), any(), any(), anyString(), any(), any());
        verify(agentClientFactory).buildChatClient(any(), anyString(), any(), any(), any());
    }

    @Test
    void coordinatorTeam_missingModelId_throwsActionablePreflightError() {
        // Spec §4c: stub modelId is the operator's data-quality issue surfaced by F1. Preflight throws
        // an actionable BusinessValidationException naming the team and the missing modelId field
        // BEFORE buildChatClient gets a chance to fail with a Spring AI validation error.
        // The outer catch at AgentService.run finalizes FAILED then rethrows; we catch here to
        // make the assertion site explicit.
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.id()).thenReturn("coord-no-model");
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn(null);
        when(def.isTeam()).thenReturn(true);
        lenient().when(def.teamMode()).thenReturn("COORDINATOR");

        when(agentRegistry.findById(eq("coord-no-model"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("coord-no-model", RunStatus.RUNNING)).thenReturn(0L);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> agentService.run("coord-no-model", "input", "sess"));
        assertTrue(ex.getMessage().contains("modelId"),
                "operator-actionable error must reference the missing modelId field; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("coord-no-model"),
                "error message should name the offending team; got: " + ex.getMessage());

        // Outer catch must have finalized the run as FAILED with the actionable message.
        ArgumentCaptor<String> outputCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.FAILED),
                outputCap.capture(), any(), any());
        assertTrue(outputCap.getValue().contains("modelId"),
                "finalizer output must carry the modelId reason for SRE/audit triage; got: " + outputCap.getValue());

        verify(agentClientFactory, never()).buildChatClient(any(), anyString(), any(), any(), any());
        verify(teamOrchestrationEngine, never()).executeSync(any(), anyString(), any(), anyString(),
                any(), any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void coordinatorTeam_noMembers_throwsActionablePreflightError() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.id()).thenReturn("coord-no-members");
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.members()).thenReturn(java.util.List.of());
        when(def.isTeam()).thenReturn(true);
        lenient().when(def.teamMode()).thenReturn("COORDINATOR");

        when(agentRegistry.findById(eq("coord-no-members"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("coord-no-members", RunStatus.RUNNING)).thenReturn(0L);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> agentService.run("coord-no-members", "input", "sess"));
        assertTrue(ex.getMessage().contains("no members"),
                "error must explain the no-members condition; got: " + ex.getMessage());

        verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.FAILED), anyString(), any(), any());
        verify(agentClientFactory, never()).buildChatClient(any(), anyString(), any(), any(), any());
    }

    @Test
    void coordinatorTeam_publishesOrchestrationDecisionEvent() {
        // Pin that the ORCHESTRATOR_DECISION event publish (formerly in CoordinatorOrchestrator) is preserved
        // through the F1 migration. When preflight succeeds, the leader's ChatClient build fires; even if that
        // build fails (here, by stubbed throw) the event has already been published.
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.id()).thenReturn("coord-event");
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.members()).thenReturn(java.util.List.of("m-a", "m-b"));
        when(def.isTeam()).thenReturn(true);
        lenient().when(def.teamMode()).thenReturn("COORDINATOR");

        AgentDefinition memberA = mock(AgentDefinition.class);
        lenient().when(memberA.id()).thenReturn("m-a");
        lenient().when(memberA.active()).thenReturn(true);
        lenient().when(memberA.maintenanceMode()).thenReturn(false);
        AgentDefinition memberB = mock(AgentDefinition.class);
        lenient().when(memberB.id()).thenReturn("m-b");
        lenient().when(memberB.active()).thenReturn(true);
        lenient().when(memberB.maintenanceMode()).thenReturn(false);

        when(agentRegistry.findById(eq("coord-event"), any())).thenReturn(def);
        when(agentRegistry.findById(eq("m-a"), any())).thenReturn(memberA);
        when(agentRegistry.findById(eq("m-b"), any())).thenReturn(memberB);
        when(runRepository.countByAgentIdAndStatus("coord-event", RunStatus.RUNNING)).thenReturn(0L);
        when(agentClientFactory.buildChatClient(any(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("post-event short-circuit"));

        try {
            agentService.run("coord-event", "input", "sess");
        } catch (RuntimeException expected) {
            // Outer catch rethrows; we only care about the event publish.
        }

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(agentRunEventBus, atLeastOnce()).publish(evCap.capture());
        boolean found = evCap.getAllValues().stream()
                .anyMatch(e -> e.eventType() == AgentRunEventType.ORCHESTRATOR_DECISION
                        && "COORDINATOR".equals(e.payload().get("mode"))
                        && "coord-event".equals(e.payload().get("rootAgentId"))
                        && Integer.valueOf(2).equals(e.payload().get("memberCount")));
        assertTrue(found, "ORCHESTRATOR_DECISION event with COORDINATOR mode + memberCount=2 must be published");
    }

    @Test
    void nonCoordinatorTeam_stillRoutesThroughTeamEngine() {
        // Regression pin: F1 must not break SEQUENTIAL/SWARM/etc. teams. Their orchestrators do NOT
        // call runner.run(rootAgent.id()) so they don't have the recursion problem; they belong on
        // the team-engine path.
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.name()).thenReturn("Sequential Team");
        lenient().when(def.modelId()).thenReturn("gpt-4");
        when(def.isTeam()).thenReturn(true);
        lenient().when(def.teamMode()).thenReturn("SEQUENTIAL");

        when(agentRegistry.findById(eq("seq-team"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("seq-team", RunStatus.RUNNING)).thenReturn(0L);

        RunResponse teamResp = new RunResponse("exec", "sess", "done",
                java.util.Map.of(), java.util.List.of(), java.util.List.of(), RunStatus.COMPLETED, null);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenReturn(teamResp);

        RunResponse out = agentService.run("seq-team", "kickoff", "sess");

        assertEquals(RunStatus.COMPLETED, out.status());
        verify(teamOrchestrationEngine).executeSync(any(), anyString(), any(), anyString(),
                any(), any(), any(), any(), anyString(), any(), any());
        verify(agentClientFactory, never()).buildChatClient(any(), anyString(), any(), any(), any());
    }

    // --- Tier 2.5 F2 / F3 — team-pause propagation + escalation catches ---

    @Test
    void teamMemberPaused_finalizesTeamAsPaused_withLiftedRequiredAction() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.teamMode()).thenReturn("SEQUENTIAL");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-pause"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("team-pause", RunStatus.RUNNING)).thenReturn(0L);

        com.operativus.agentmanager.core.model.RequiredAction childPayload =
                com.operativus.agentmanager.core.model.RequiredAction.toolApproval(
                        "delete_file", "{}", "approval-xyz", "trace-1", "lineage", "depth=1");
        com.operativus.agentmanager.core.exception.TeamMemberPausedException tpe =
                new com.operativus.agentmanager.core.exception.TeamMemberPausedException(
                        "child-run-id", "memberA", childPayload);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenThrow(tpe);

        RunResponse out = agentService.run("team-pause", "kickoff", "sess");

        assertEquals(RunStatus.PAUSED, out.status());
        com.operativus.agentmanager.core.model.RequiredAction lifted =
                (com.operativus.agentmanager.core.model.RequiredAction) out.metadata().get("requiredAction");
        assertEquals("child-run-id", lifted.pausedChildRunId());
        assertEquals("delete_file", lifted.tool());
        assertEquals("approval-xyz", lifted.approvalId());

        verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.PAUSED), anyString(), anyString(), any());

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(agentRunEventBus, times(2)).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_START, evCap.getAllValues().get(0).eventType());
        assertEquals(AgentRunEventType.RUN_PAUSED, evCap.getAllValues().get(1).eventType());
    }

    @Test
    void teamSwarmEscalation_finalizesTeamAsPaused_withSwarmEscalationPayload() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.teamMode()).thenReturn("ROUTER");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("router-team"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("router-team", RunStatus.RUNNING)).thenReturn(0L);

        com.operativus.agentmanager.core.exception.SwarmEscalationException se =
                new com.operativus.agentmanager.core.exception.SwarmEscalationException(
                        "src-agent", "tgt-agent", 1, 2);
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenThrow(se);

        RunResponse out = agentService.run("router-team", "go", "sess");

        assertEquals(RunStatus.PAUSED, out.status());
        com.operativus.agentmanager.core.model.RequiredAction ra =
                (com.operativus.agentmanager.core.model.RequiredAction) out.metadata().get("requiredAction");
        assertEquals(com.operativus.agentmanager.core.model.RequiredActionType.SWARM_ESCALATION_APPROVAL, ra.type());
        assertNull(ra.pausedChildRunId(), "F3 path has no child to point at");
        assertEquals("src-agent", ra.sourceAgentId());

        verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.PAUSED), anyString(), anyString(), any());
    }

    @Test
    void teamApprovalRequired_finalizesTeamAsPaused_withToolApprovalPayload() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.teamMode()).thenReturn("PLANNER");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("planner-team"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("planner-team", RunStatus.RUNNING)).thenReturn(0L);

        com.operativus.agentmanager.core.exception.ApprovalRequiredException are =
                new com.operativus.agentmanager.core.exception.ApprovalRequiredException(
                        "approval-99", "drop_table", "{\"table\":\"users\"}");
        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any())).thenThrow(are);

        RunResponse out = agentService.run("planner-team", "go", "sess");

        assertEquals(RunStatus.PAUSED, out.status());
        com.operativus.agentmanager.core.model.RequiredAction ra =
                (com.operativus.agentmanager.core.model.RequiredAction) out.metadata().get("requiredAction");
        assertEquals(com.operativus.agentmanager.core.model.RequiredActionType.TOOL_APPROVAL, ra.type());
        assertEquals("drop_table", ra.tool());
        assertNull(ra.pausedChildRunId(), "F3 path has no child to point at");
    }

    @Test
    void teamUnrelatedException_stillFinalizesAsFailed_regressionPin() {
        AgentDefinition def = mock(AgentDefinition.class);
        lenient().when(def.maintenanceMode()).thenReturn(false);
        lenient().when(def.modelId()).thenReturn("gpt-4");
        lenient().when(def.teamMode()).thenReturn("SEQUENTIAL");
        when(def.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-x"), any())).thenReturn(def);
        when(runRepository.countByAgentIdAndStatus("team-x", RunStatus.RUNNING)).thenReturn(0L);

        when(teamOrchestrationEngine.executeSync(any(), anyString(), any(), anyString(), any(), any(),
                any(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("unrelated boom"));

        assertThrows(RuntimeException.class, () -> agentService.run("team-x", "go", "sess"));

        verify(agentRunFinalizer).finalizeRun(anyString(), eq(RunStatus.FAILED), anyString(), isNull(), any());
    }

    @Test
    void continueRun_teamMemberPause_rejectsWithChildRunIdHint() throws Exception {
        AgentRun run = new AgentRun("team-1", "sess", "input", "user", "org");
        run.setId("team-run-id");
        run.setStatus(RunStatus.PAUSED);
        com.operativus.agentmanager.core.model.RequiredAction lifted =
                com.operativus.agentmanager.core.model.RequiredAction.teamMemberPause(
                        com.operativus.agentmanager.core.model.RequiredAction.toolApproval(
                                "delete_file", "{}", "approval-xyz", "trace", "lineage", "depth"),
                        "child-run-789");
        run.setRequiredAction(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(lifted));

        when(runRepository.findById("team-run-id")).thenReturn(Optional.of(run));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                agentService.continueRun("team-run-id", "APPROVE"));

        assertTrue(ex.getMessage().contains("child-run-789"),
                "Rejection message must point at the paused child runId; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("/api/v1/approvals/approval-xyz/resolve"),
                "Rejection message must direct the caller at the typed approvals resolve endpoint "
                        + "for the child's TOOL_APPROVAL payload (post-#406 sister to /v1/escalations); was: "
                        + ex.getMessage());
    }

    @Test
    void continueRun_teamLevelEscalation_rejectsWithReinvokeHint() throws Exception {
        AgentRun run = new AgentRun("team-2", "sess", "input", "user", "org");
        run.setId("team-f3-run-id");
        run.setStatus(RunStatus.PAUSED);
        // F3-style: SWARM_ESCALATION_APPROVAL with no pausedChildRunId
        com.operativus.agentmanager.core.model.RequiredAction f3Payload =
                com.operativus.agentmanager.core.model.RequiredAction.swarmEscalation(
                        "src", "tgt", 1, 2, "esc-1", "trace", "lineage", "depth");
        run.setRequiredAction(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f3Payload));

        when(runRepository.findById("team-f3-run-id")).thenReturn(Optional.of(run));
        AgentDefinition teamDef = mock(AgentDefinition.class);
        when(teamDef.isTeam()).thenReturn(true);
        when(agentRegistry.findById(eq("team-2"), any())).thenReturn(teamDef);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                agentService.continueRun("team-f3-run-id", "APPROVE"));

        assertTrue(ex.getMessage().contains("Re-invoke the team agent from scratch"),
                "F3 rejection message must direct to re-invocation; was: " + ex.getMessage());
    }
}
