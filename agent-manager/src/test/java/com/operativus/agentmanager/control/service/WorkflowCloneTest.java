package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.control.websocket.WorkflowWebSocketHandler;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.WorkflowDTO;
import com.operativus.agentmanager.core.registry.AgentOperations;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowCloneTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowStepRepository workflowStepRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowWebSocketHandler webSocketHandler;
    @Mock private AgentOperations agentOperations;
    @Mock private Tracer tracer;

    @InjectMocks
    private WorkflowService workflowService;

    private MockedStatic<com.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        // Tenant scoping resolves orgId via AgentContextHolder; stub deterministically.
        mockedContext = mockStatic(com.operativus.agentmanager.core.callback.AgentContextHolder.class);
        mockedContext.when(com.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                .thenReturn("TEST_ORG");
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    @Test
    void cloneWorkflow_CopiesWorkflowAndSteps() {
        Workflow source = new Workflow("src-id", "My Workflow", "A description");
        when(workflowRepository.findByIdAndOrgId("src-id", "TEST_ORG")).thenReturn(Optional.of(source));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc("src-id")).thenReturn(List.of(
                new WorkflowStep("step-1", "src-id", 1, "agent-a", "AGENT"),
                new WorkflowStep("step-2", "src-id", 2, "agent-b", "AGENT")
        ));
        when(workflowStepRepository.save(any(WorkflowStep.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDTO result = workflowService.cloneWorkflow("src-id");

        assertNotNull(result);
        assertEquals("My Workflow (Copy)", result.name());
        assertEquals("A description", result.description());
        assertNotEquals("src-id", result.id());

        // Verify 2 steps were cloned
        ArgumentCaptor<WorkflowStep> stepCaptor = ArgumentCaptor.forClass(WorkflowStep.class);
        verify(workflowStepRepository, times(2)).save(stepCaptor.capture());

        List<WorkflowStep> clonedSteps = stepCaptor.getAllValues();
        assertEquals(result.id(), clonedSteps.get(0).getWorkflowId());
        assertEquals(result.id(), clonedSteps.get(1).getWorkflowId());
        assertEquals(1, clonedSteps.get(0).getStepOrder());
        assertEquals(2, clonedSteps.get(1).getStepOrder());
        assertEquals("agent-a", clonedSteps.get(0).getAgentId());
        assertEquals("agent-b", clonedSteps.get(1).getAgentId());
        // Cloned steps get new IDs
        assertNotEquals("step-1", clonedSteps.get(0).getId());
        assertNotEquals("step-2", clonedSteps.get(1).getId());
    }

    @Test
    void cloneWorkflow_EmptySteps_CopiesWorkflowOnly() {
        Workflow source = new Workflow("src-id", "Empty WF", null);
        when(workflowRepository.findByIdAndOrgId("src-id", "TEST_ORG")).thenReturn(Optional.of(source));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc("src-id")).thenReturn(List.of());

        WorkflowDTO result = workflowService.cloneWorkflow("src-id");

        assertEquals("Empty WF (Copy)", result.name());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void cloneWorkflow_NotFound_ThrowsException() {
        when(workflowRepository.findByIdAndOrgId("missing", "TEST_ORG")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> workflowService.cloneWorkflow("missing"));
    }
}
