package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.WorkflowService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import com.operativus.agentmanager.core.model.WorkflowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WorkflowsControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkflowService workflowService;

    @Mock
    private PersistentJobQueueService jobQueueService;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowStepRepository workflowStepRepository;

    @Mock
    private com.operativus.agentmanager.control.repository.WorkflowEdgeRepository workflowEdgeRepository;

    @Mock
    private com.operativus.agentmanager.compute.workflow.WorkflowDagValidator dagValidator;

    @Mock
    private com.operativus.agentmanager.control.repository.WorkflowNodeRunRepository workflowNodeRunRepository;

    @Mock
    private com.operativus.agentmanager.control.repository.WorkflowNodeLayoutRepository workflowNodeLayoutRepository;

    private WorkflowsController controller;

    @BeforeEach
    void setUp() {
        // Explicit constructor wiring: @InjectMocks cannot thread the real ObjectMapper
        // through without a @Spy wrapper, and ObjectMapper has enough surface area that
        // spying is noisier than just passing a real instance.
        controller = new WorkflowsController(workflowService, jobQueueService, workflowRunRepository, workflowRepository, workflowStepRepository, workflowEdgeRepository, workflowNodeRunRepository, workflowNodeLayoutRepository, dagValidator, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- Clone ---

    @Test
    void cloneWorkflow_ExistingWorkflow_Returns201() throws Exception {
        WorkflowDTO cloned = new WorkflowDTO("cloned-id", "My Workflow (Copy)", "desc", 3, LocalDateTime.now(), LocalDateTime.now());
        when(workflowService.cloneWorkflow("source-id")).thenReturn(cloned);

        mockMvc.perform(post("/api/v1/workflows/source-id/clone")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cloned-id"))
                .andExpect(jsonPath("$.name").value("My Workflow (Copy)"));

        verify(workflowService).cloneWorkflow("source-id");
    }

    @Test
    void cloneWorkflow_NotFound_Returns404() throws Exception {
        when(workflowService.cloneWorkflow("missing-id"))
                .thenThrow(new IllegalArgumentException("Workflow not found"));

        mockMvc.perform(post("/api/v1/workflows/missing-id/clone")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // --- Execute ---
    //
    // /run is fire-and-forget: the controller enqueues a WORKFLOW_EXECUTION job and
    // returns 202 immediately with {jobId, workflowId, sessionId}. The actual
    // execution happens later in WorkflowExecutionJobHandler; runId existence, bad
    // input, etc. cannot surface as HTTP errors from this endpoint.

    @Test
    void executeWorkflow_ValidRequest_Returns202() throws Exception {
        // Tenant guard: the controller resolves orgId via AgentContextHolder which is
        // unset in standaloneSetup, so the fallback DEFAULT_SYSTEM_ORG is used.
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), eq("DEFAULT_SYSTEM_ORG"))).thenReturn(true);
        when(workflowStepRepository.countByWorkflowId("wf-1")).thenReturn(2L);

        BackgroundJob job = new BackgroundJob();
        job.setId("job-exec-1");
        when(jobQueueService.enqueue(eq("WORKFLOW_EXECUTION"), isNull(), any(String.class), isNull(), isNull()))
                .thenReturn(job);

        String body = objectMapper.writeValueAsString(Map.of("input", "Analyze quarterly data", "sessionId", "sess-1"));

        mockMvc.perform(post("/api/v1/workflows/wf-1/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-exec-1"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.sessionId").value("sess-1"));

        verify(jobQueueService).enqueue(eq("WORKFLOW_EXECUTION"), isNull(), any(String.class), isNull(), isNull());
    }

    @Test
    void executeWorkflow_NoSessionId_GeneratesOne() throws Exception {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-2"), eq("DEFAULT_SYSTEM_ORG"))).thenReturn(true);
        when(workflowStepRepository.countByWorkflowId("wf-2")).thenReturn(1L);

        BackgroundJob job = new BackgroundJob();
        job.setId("job-exec-2");
        when(jobQueueService.enqueue(eq("WORKFLOW_EXECUTION"), isNull(), any(String.class), isNull(), isNull()))
                .thenReturn(job);

        String body = objectMapper.writeValueAsString(Map.of("input", "Hello"));

        mockMvc.perform(post("/api/v1/workflows/wf-2/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-exec-2"))
                .andExpect(jsonPath("$.workflowId").value("wf-2"))
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    void executeWorkflow_ZeroSteps_Returns400AndDoesNotEnqueue() throws Exception {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-empty"), eq("DEFAULT_SYSTEM_ORG"))).thenReturn(true);
        when(workflowStepRepository.countByWorkflowId("wf-empty")).thenReturn(0L);

        String body = objectMapper.writeValueAsString(Map.of("input", "anything"));

        mockMvc.perform(post("/api/v1/workflows/wf-empty/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no steps")));

        verify(jobQueueService, never()).enqueue(any(), any(), any(), any(), any());
    }

    // --- Resume ---
    //
    // /runs/{runId}/resume is also fire-and-forget — runId existence is validated
    // by WorkflowResumeJobHandler when the job runs, not at HTTP ingress. A 404
    // at the controller boundary is no longer reachable.

    @Test
    void resumeWorkflowRun_ValidRequest_Returns202() throws Exception {
        BackgroundJob job = new BackgroundJob();
        job.setId("job-resume-1");
        when(jobQueueService.enqueue(eq("WORKFLOW_RESUME"), isNull(), any(String.class), isNull(), isNull()))
                .thenReturn(job);

        String body = objectMapper.writeValueAsString(Map.of("output", "Approved result"));

        mockMvc.perform(post("/api/v1/workflows/runs/run-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-resume-1"))
                .andExpect(jsonPath("$.runId").value("run-1"));

        verify(jobQueueService).enqueue(eq("WORKFLOW_RESUME"), isNull(), any(String.class), isNull(), isNull());
    }

    @Test
    void resumeWorkflowRun_UnknownRunId_StillReturns202_DeferredValidation() throws Exception {
        // Pins the post-refactor contract: the controller does not reject unknown runIds.
        // Replaces the legacy resumeWorkflowRun_NotFound_Returns404 assertion, which was
        // only reachable when resume was a synchronous call into WorkflowService.
        BackgroundJob job = new BackgroundJob();
        job.setId("job-resume-missing");
        when(jobQueueService.enqueue(eq("WORKFLOW_RESUME"), isNull(), any(String.class), isNull(), isNull()))
                .thenReturn(job);

        String body = objectMapper.writeValueAsString(Map.of("output", "data"));

        mockMvc.perform(post("/api/v1/workflows/runs/missing-run/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted());

        verifyNoInteractions(workflowService);
    }

    // --- Workflow runs (T003) ---

    @Test
    void getWorkflowRuns_ReturnsPagedDtoWithDuration() throws Exception {
        LocalDateTime start = LocalDateTime.parse("2026-04-23T12:00:00");
        LocalDateTime end   = LocalDateTime.parse("2026-04-23T12:00:02.500");
        WorkflowRun run = workflowRun("run-1", "wf-42", "sess-1", RunStatus.COMPLETED, 3, start, end);
        Page<WorkflowRun> page = new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1);
        when(workflowRunRepository.findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(eq("wf-42"), eq("DEFAULT_SYSTEM_ORG"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/workflows/wf-42/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("run-1"))
                .andExpect(jsonPath("$.content[0].workflowId").value("wf-42"))
                .andExpect(jsonPath("$.content[0].sessionId").value("sess-1"))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].lastStepOrder").value(3))
                .andExpect(jsonPath("$.content[0].durationMs").value(2500));
    }

    @Test
    void getWorkflowRuns_NullUpdatedAt_DurationIsNull() throws Exception {
        LocalDateTime start = LocalDateTime.parse("2026-04-23T12:00:00");
        WorkflowRun run = workflowRun("run-2", "wf-1", "sess-2", RunStatus.RUNNING, 1, start, null);
        when(workflowRunRepository.findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(eq("wf-1"), eq("DEFAULT_SYSTEM_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/workflows/wf-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].durationMs").doesNotExist());
    }

    @Test
    void getWorkflowRuns_EmptyPage_Returns200() throws Exception {
        when(workflowRunRepository.findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(eq("wf-empty"), eq("DEFAULT_SYSTEM_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/workflows/wf-empty/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getWorkflowRuns_PageAndSizeParams_ForwardedToPageable() throws Exception {
        when(workflowRunRepository.findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(eq("wf-1"), eq("DEFAULT_SYSTEM_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 50), 0));

        mockMvc.perform(get("/api/v1/workflows/wf-1/runs?page=3&size=50")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(workflowRunRepository).findByWorkflowIdAndOrgIdOrderByCreatedAtDesc(eq("wf-1"), eq("DEFAULT_SYSTEM_ORG"), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    private static WorkflowRun workflowRun(String id, String workflowId, String sessionId, RunStatus status,
                                           Integer currentStepOrder, LocalDateTime createdAt, LocalDateTime updatedAt) {
        WorkflowRun r = new WorkflowRun();
        r.setId(id);
        r.setWorkflowId(workflowId);
        r.setSessionId(sessionId);
        r.setStatus(status);
        r.setCurrentStepOrder(currentStepOrder);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(updatedAt);
        return r;
    }
}
