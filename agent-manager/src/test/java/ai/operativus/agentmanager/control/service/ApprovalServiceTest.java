package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ApprovalRepository;
import ai.operativus.agentmanager.core.entity.Approval;
import ai.operativus.agentmanager.core.event.AlertFiredEvent;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ApprovalServiceTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ai.operativus.agentmanager.core.registry.AgentOperations agentOperations;
    @Mock private ApplicationContext applicationContext;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(approvalRepository, agentOperations, applicationContext, eventPublisher);
    }

    @Test
    void checkApprovalSla_publishesOneEventPerOverdueApproval() {
        Approval overdue = mock(Approval.class);
        when(overdue.getId()).thenReturn("approval-1");
        when(overdue.getAgentId()).thenReturn("agent-1");
        when(overdue.getToolName()).thenReturn("some-tool");
        when(overdue.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(21));

        when(approvalRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of(overdue));

        service.checkApprovalSla();

        ArgumentCaptor<AlertFiredEvent> captor = ArgumentCaptor.forClass(AlertFiredEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        AlertFiredEvent fired = captor.getValue();
        assertEquals("APPROVAL_SLA_BREACH", fired.getRuleId());
        assertEquals("approval-1", fired.getEventId());
        assertEquals("WARNING", fired.getSeverity());
    }

    @Test
    void checkApprovalSla_publishesOneEventPerEachOverdueApproval() {
        Approval first = mock(Approval.class);
        when(first.getId()).thenReturn("approval-1");
        when(first.getAgentId()).thenReturn("agent-1");
        when(first.getToolName()).thenReturn("tool-a");
        when(first.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(25));

        Approval second = mock(Approval.class);
        when(second.getId()).thenReturn("approval-2");
        when(second.getAgentId()).thenReturn("agent-2");
        when(second.getToolName()).thenReturn("tool-b");
        when(second.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(22));

        Approval third = mock(Approval.class);
        when(third.getId()).thenReturn("approval-3");
        when(third.getAgentId()).thenReturn("agent-3");
        when(third.getToolName()).thenReturn("tool-c");
        when(third.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(21));

        when(approvalRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of(first, second, third));

        service.checkApprovalSla();

        verify(eventPublisher, times(3)).publishEvent(any(AlertFiredEvent.class));
    }

    @Test
    void checkApprovalSla_noEventsWhenNoOverdueApprovals() {
        Approval recent = mock(Approval.class);
        when(recent.getCreatedAt()).thenReturn(LocalDateTime.now().minusHours(1));

        when(approvalRepository.findByStatus(RunStatus.PENDING)).thenReturn(List.of(recent));

        service.checkApprovalSla();

        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- Tier 2.4 PR 7 F-A — workflow_run REJECTED cascade ---

    @Test
    void resolveApprovalForOrg_workflowRejected_invokesCancelWorkflowRun() throws Exception {
        Approval pending = mock(Approval.class);
        when(pending.getId()).thenReturn("approval-1");
        when(pending.getOrgId()).thenReturn("org-1");
        when(pending.getStatus()).thenReturn(RunStatus.PENDING);
        when(pending.getRunId()).thenReturn("run-1");
        when(pending.getWorkflowRunId()).thenReturn("workflow-run-W");
        when(approvalRepository.findById("approval-1")).thenReturn(java.util.Optional.of(pending));
        when(approvalRepository.save(any(Approval.class))).thenReturn(pending);

        WorkflowService workflowService = mock(WorkflowService.class);
        when(applicationContext.getBean(WorkflowService.class)).thenReturn(workflowService);
        when(agentOperations.continueRun(anyString(), anyString()))
                .thenReturn(new ai.operativus.agentmanager.core.model.RunResponse(
                        "run-1", "sess", "cancelled",
                        java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                        RunStatus.CANCELLED, null));

        service.resolveApprovalForOrg("approval-1", "REJECTED", "user-1", "org-1");

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(workflowService, timeout(1000)).cancelWorkflowRun(eq("workflow-run-W"), reasonCap.capture());
        assertEquals(true, reasonCap.getValue().contains("REJECTED"),
                "cancellation reason must mention REJECTED so operators correlate to the approval; was: "
                        + reasonCap.getValue());
    }

    @Test
    void resolveApprovalForOrg_workflowApproved_doesNotInvokeCancelWorkflowRun() throws Exception {
        Approval pending = mock(Approval.class);
        when(pending.getId()).thenReturn("approval-2");
        when(pending.getOrgId()).thenReturn("org-2");
        when(pending.getStatus()).thenReturn(RunStatus.PENDING);
        when(pending.getRunId()).thenReturn("run-2");
        when(pending.getWorkflowRunId()).thenReturn("workflow-run-X");
        when(approvalRepository.findById("approval-2")).thenReturn(java.util.Optional.of(pending));
        when(approvalRepository.save(any(Approval.class))).thenReturn(pending);

        WorkflowService workflowService = mock(WorkflowService.class);
        lenient().when(applicationContext.getBean(WorkflowService.class)).thenReturn(workflowService);
        when(agentOperations.continueRun(anyString(), anyString()))
                .thenReturn(new ai.operativus.agentmanager.core.model.RunResponse(
                        "run-2", "sess", "ok",
                        java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                        RunStatus.COMPLETED, null));

        service.resolveApprovalForOrg("approval-2", "APPROVED", "user-2", "org-2");

        verify(workflowService, timeout(1000)).resumeWorkflowRun(eq("workflow-run-X"), anyString());
        verify(workflowService, never()).cancelWorkflowRun(anyString(), anyString());
    }

    @Test
    void resolveApprovalForOrg_noWorkflowRunRejected_doesNotInvokeCancelWorkflowRun() throws Exception {
        Approval pending = mock(Approval.class);
        when(pending.getId()).thenReturn("approval-3");
        when(pending.getOrgId()).thenReturn("org-3");
        when(pending.getStatus()).thenReturn(RunStatus.PENDING);
        when(pending.getRunId()).thenReturn("run-3");
        when(pending.getWorkflowRunId()).thenReturn(null);
        when(approvalRepository.findById("approval-3")).thenReturn(java.util.Optional.of(pending));
        when(approvalRepository.save(any(Approval.class))).thenReturn(pending);

        WorkflowService workflowService = mock(WorkflowService.class);
        lenient().when(applicationContext.getBean(WorkflowService.class)).thenReturn(workflowService);
        when(agentOperations.continueRun(anyString(), anyString()))
                .thenReturn(new ai.operativus.agentmanager.core.model.RunResponse(
                        "run-3", "sess", "cancelled",
                        java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                        RunStatus.CANCELLED, null));

        service.resolveApprovalForOrg("approval-3", "REJECTED", "user-3", "org-3");
        Thread.sleep(300);

        verify(workflowService, never()).cancelWorkflowRun(anyString(), anyString());
    }
}
