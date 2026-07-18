package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.control.repository.ApprovalRepository;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.entity.Approval;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.RunOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunExecutionManagerTest {

    @Mock private RunOperations runRepository;
    @Mock private Tracer tracer;
    @Mock private AgentRunFinalizer agentRunFinalizer;
    @Mock private ApprovalRepository approvalRepository;
    @Mock private AgentRunEventBus eventBus;

    private SimpleMeterRegistry meterRegistry;
    private RunExecutionManager rem;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        rem = new RunExecutionManager(runRepository, tracer, agentRunFinalizer,
                approvalRepository, meterRegistry, eventBus, 24L);
    }

    private AgentRun stuckRun(String id, boolean withWorkflow) {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "org-1");
        run.setId(id);
        run.setStatus(RunStatus.PAUSED);
        run.setCreatedAt(LocalDateTime.now().minusHours(48));
        // workflow link is on the approval, not the run
        return run;
    }

    private Approval approvalWithStatus(RunStatus status, String workflowRunId) {
        Approval a = mock(Approval.class);
        when(a.getStatus()).thenReturn(status);
        lenient().when(a.getWorkflowRunId()).thenReturn(workflowRunId);
        return a;
    }

    @Test
    void expireStuckPausedRuns_classifiesByApprovalState_expired() {
        AgentRun run = stuckRun("run-A", false);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        Approval approvalrun_A = approvalWithStatus(RunStatus.EXPIRED, null);
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-A"))
                .thenReturn(Optional.of(approvalrun_A));

        rem.expireStuckPausedRuns();

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(eq("run-A"), eq(RunStatus.CANCELLED), reasonCap.capture(), isNull(), isNull());
        assertTrue(reasonCap.getValue().contains("approval_expired_upstream"),
                "reason must include classification label; was: " + reasonCap.getValue());

        assertEquals(1.0, meterRegistry.counter("agm.runs.stuck_paused_cancelled_total",
                "classification", "approval_expired_upstream").count());
    }

    @Test
    void expireStuckPausedRuns_classifiesPendingWithWorkflow_asWorkflowStepAbandoned() {
        AgentRun run = stuckRun("run-B", true);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        Approval approvalrun_B = approvalWithStatus(RunStatus.PENDING, "workflow-W");
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-B"))
                .thenReturn(Optional.of(approvalrun_B));

        rem.expireStuckPausedRuns();

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(eq("run-B"), eq(RunStatus.CANCELLED), reasonCap.capture(), isNull(), isNull());
        assertTrue(reasonCap.getValue().contains("workflow_step_abandoned"));
        assertEquals(1.0, meterRegistry.counter("agm.runs.stuck_paused_cancelled_total",
                "classification", "workflow_step_abandoned").count());
    }

    @Test
    void expireStuckPausedRuns_classifiesPendingNoWorkflow_asUserAbandoned() {
        AgentRun run = stuckRun("run-C", false);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        Approval approvalrun_C = approvalWithStatus(RunStatus.PENDING, null);
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-C"))
                .thenReturn(Optional.of(approvalrun_C));

        rem.expireStuckPausedRuns();

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(eq("run-C"), eq(RunStatus.CANCELLED), reasonCap.capture(), isNull(), isNull());
        assertTrue(reasonCap.getValue().contains("user_abandoned"));
    }

    @Test
    void expireStuckPausedRuns_classifiesResolved_asResumeFailedAfterResolve() {
        AgentRun run = stuckRun("run-D", false);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        Approval approvalrun_D = approvalWithStatus(RunStatus.APPROVED, null);
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-D"))
                .thenReturn(Optional.of(approvalrun_D));

        rem.expireStuckPausedRuns();

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(eq("run-D"), eq(RunStatus.CANCELLED), reasonCap.capture(), isNull(), isNull());
        assertTrue(reasonCap.getValue().contains("resume_failed_after_resolve"));
    }

    @Test
    void expireStuckPausedRuns_noApprovalRow_classifiesNoApprovalRow() {
        AgentRun run = stuckRun("run-E", false);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-E"))
                .thenReturn(Optional.empty());

        rem.expireStuckPausedRuns();

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(agentRunFinalizer).finalizeRun(eq("run-E"), eq(RunStatus.CANCELLED), reasonCap.capture(), isNull(), isNull());
        assertTrue(reasonCap.getValue().contains("no_approval_row"));
    }

    @Test
    void expireStuckPausedRuns_publishesRunCancelledLifecycleEvent() {
        AgentRun run = stuckRun("run-F", false);
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of(run));
        Approval approvalrun_F = approvalWithStatus(RunStatus.EXPIRED, null);
        when(approvalRepository.findFirstByRunIdOrderByCreatedAtDesc("run-F"))
                .thenReturn(Optional.of(approvalrun_F));

        rem.expireStuckPausedRuns();

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_CANCELLED, evCap.getValue().eventType());
        assertEquals("run-F", evCap.getValue().runId());
        assertEquals("approval_expired_upstream", evCap.getValue().payload().get("classification"));
    }

    @Test
    void expireStuckPausedRuns_emptyList_noOp() {
        when(runRepository.findByStatusInAndCreatedAtBefore(eq(List.of(RunStatus.PAUSED)), any(LocalDateTime.class))).thenReturn(List.of());
        rem.expireStuckPausedRuns();
        verify(agentRunFinalizer, never()).finalizeRun(anyString(), any(), anyString(), any(), any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    void cleanupOrphanedRuns_publishesRunCancelledEvent() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "org-1");
        run.setId("run-orphan");
        run.setStatus(RunStatus.RUNNING);
        when(runRepository.findByStatusIn(any())).thenReturn(List.of(run));

        rem.cleanupOrphanedRuns();

        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_CANCELLED, evCap.getValue().eventType());
        assertEquals("orphaned_on_restart", evCap.getValue().payload().get("classification"));
    }

    @Test
    void cancel_inactiveRun_finalizesAndPublishesUserInitiatedEvent() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "org-1");
        run.setId("run-cancel");
        run.setStatus(RunStatus.PAUSED);
        when(runRepository.findById("run-cancel")).thenReturn(Optional.of(run));

        rem.cancel("run-cancel");

        verify(agentRunFinalizer).finalizeRun(eq("run-cancel"), eq(RunStatus.CANCELLED), anyString(), isNull(), isNull());
        ArgumentCaptor<AgentRunEvent> evCap = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus).publish(evCap.capture());
        assertEquals(AgentRunEventType.RUN_CANCELLED, evCap.getValue().eventType());
        assertEquals("user_initiated", evCap.getValue().payload().get("classification"));
    }
}
