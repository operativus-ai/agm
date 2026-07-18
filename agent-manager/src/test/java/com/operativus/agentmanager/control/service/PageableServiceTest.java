package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.ApprovalRepository;
import com.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import com.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import com.operativus.agentmanager.control.repository.ModelRepository;
import com.operativus.agentmanager.control.repository.ScheduleRepository;
import com.operativus.agentmanager.control.repository.ScheduleRunRepository;
import com.operativus.agentmanager.control.repository.TeamMemberRepository;
import com.operativus.agentmanager.control.repository.TeamRepository;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.control.websocket.WorkflowWebSocketHandler;
import com.operativus.agentmanager.core.entity.Approval;
import com.operativus.agentmanager.core.entity.KnowledgeContent;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.entity.Schedule;
import com.operativus.agentmanager.core.entity.Team;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.ModelDTO;
import com.operativus.agentmanager.core.model.ScheduleDTO;
import com.operativus.agentmanager.core.model.TeamDTO;
import com.operativus.agentmanager.core.model.WorkflowDTO;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for the paginated overloads introduced across 6 domain services.
 * Verifies that each service correctly delegates to the repository's Pageable methods
 * and maps entities to DTOs within the Page envelope.
 */
@ExtendWith(MockitoExtension.class)
public class PageableServiceTest {

    // ═══════════════════════════════════════════════════════════════════
    // ── KnowledgeService ──────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("KnowledgeService.listFiles(Pageable)")
    class KnowledgeServicePaginationTests {

        @Mock private VectorStore vectorStore;
        @Mock private KnowledgeContentRepository knowledgeRepo;
        @Mock private JdbcTemplate jdbcTemplate;
        @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
        @Mock private ObjectMapper objectMapper;
        @Mock private SafetyService safetyService;
        @Mock private IngestionStatusService ingestionStatusService;

        private KnowledgeService service;

        @BeforeEach
        void setUp() {
            service = new KnowledgeService(vectorStore, knowledgeRepo, jdbcTemplate,
                    knowledgeBaseRepository, objectMapper, safetyService, ingestionStatusService, false, 4, 0.0);
        }

        @Test
        @DisplayName("Returns paginated KnowledgeContent from repository")
        void listFiles_Pageable_ReturnsPageFromRepository() {
            Pageable pageable = PageRequest.of(0, 10);
            KnowledgeContent entity = new KnowledgeContent();
            entity.setName("test-doc.pdf");
            entity.setStatus(RunStatus.COMPLETED);

            Page<KnowledgeContent> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(knowledgeRepo.findAllByCallerOrgId(any(String.class), eq(pageable))).thenReturn(mockPage);

            Page<KnowledgeContent> result = service.listFiles(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals(1, result.getContent().size());
            assertEquals("test-doc.pdf", result.getContent().getFirst().getName());
            verify(knowledgeRepo).findAllByCallerOrgId(any(String.class), eq(pageable));
        }

        @Test
        @DisplayName("Returns empty page when no documents exist")
        void listFiles_Pageable_EmptyDataset() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<KnowledgeContent> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(knowledgeRepo.findAllByCallerOrgId(any(String.class), eq(pageable))).thenReturn(emptyPage);

            Page<KnowledgeContent> result = service.listFiles(pageable);

            assertEquals(0, result.getTotalElements());
            assertTrue(result.getContent().isEmpty());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Preserves page metadata correctly across multiple pages")
        void listFiles_Pageable_MultiplePages() {
            Pageable pageable = PageRequest.of(2, 5);
            KnowledgeContent entity = new KnowledgeContent();
            entity.setName("page3-doc.pdf");

            Page<KnowledgeContent> mockPage = new PageImpl<>(List.of(entity), pageable, 25);
            when(knowledgeRepo.findAllByCallerOrgId(any(String.class), eq(pageable))).thenReturn(mockPage);

            Page<KnowledgeContent> result = service.listFiles(pageable);

            assertEquals(25, result.getTotalElements());
            assertEquals(5, result.getTotalPages());
            assertEquals(2, result.getNumber());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── WorkflowService ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WorkflowService.getAllWorkflows(Pageable)")
    class WorkflowServicePaginationTests {

        @Mock private WorkflowRepository workflowRepository;
        @Mock private WorkflowStepRepository workflowStepRepository;
        @Mock private WorkflowRunRepository workflowRunRepository;
        @Mock private AgentRepository agentRepository;
        @Mock private com.operativus.agentmanager.control.repository.SessionRepository sessionRepository;
        @Mock private WorkflowWebSocketHandler webSocketHandler;
        @Mock private com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
        @Mock private Tracer tracer;

        private WorkflowService service;
        private org.mockito.MockedStatic<com.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

        @BeforeEach
        void setUp() {
            mockedContext = org.mockito.Mockito.mockStatic(
                    com.operativus.agentmanager.core.callback.AgentContextHolder.class);
            mockedContext.when(com.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                    .thenReturn("TEST_ORG");
            service = new WorkflowService(workflowRepository, workflowStepRepository,
                    workflowRunRepository, agentRepository, sessionRepository, webSocketHandler,
                    agentOperations, tracer, Collections.emptyList(), new SimpleMeterRegistry(),
                    Collections.emptyList(), null, null, null, null);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            if (mockedContext != null) mockedContext.close();
        }

        private static WorkflowStepRepository.WorkflowStepCount stepCount(String id, long count) {
            return new WorkflowStepRepository.WorkflowStepCount() {
                @Override public String getWorkflowId() { return id; }
                @Override public long getCount() { return count; }
            };
        }

        @Test
        @DisplayName("Returns paginated WorkflowDTOs mapped from entities")
        void getAllWorkflows_Pageable_ReturnsMappedDTOs() {
            Pageable pageable = PageRequest.of(0, 10);
            Workflow entity = new Workflow("wf-1", "Test Workflow", "A description");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            Page<Workflow> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(workflowRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);
            when(workflowStepRepository.countByWorkflowIdIn(List.of("wf-1")))
                    .thenReturn(List.of(stepCount("wf-1", 5)));

            Page<WorkflowDTO> result = service.getAllWorkflows(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("Test Workflow", result.getContent().getFirst().name());
            assertEquals("A description", result.getContent().getFirst().description());
            assertEquals(5, result.getContent().getFirst().stepCount(),
                    "list DTO must carry the batch-counted step count (feeds the FE Steps column)");
            verify(workflowRepository).findAllByOrgId("TEST_ORG", pageable);
        }

        @Test
        @DisplayName("Returns empty page when no workflows exist")
        void getAllWorkflows_Pageable_EmptyDataset() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Workflow> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(workflowRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(emptyPage);

            Page<WorkflowDTO> result = service.getAllWorkflows(pageable);

            assertTrue(result.isEmpty());
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Page metadata is preserved after DTO mapping")
        void getAllWorkflows_Pageable_PageMetadataPreserved() {
            Pageable pageable = PageRequest.of(1, 5);
            Workflow entity = new Workflow("wf-2", "Mapped WF", null);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            Page<Workflow> mockPage = new PageImpl<>(List.of(entity), pageable, 15);
            when(workflowRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);

            Page<WorkflowDTO> result = service.getAllWorkflows(pageable);

            assertEquals(15, result.getTotalElements());
            assertEquals(3, result.getTotalPages());
            assertEquals(1, result.getNumber());
            assertEquals(5, result.getSize());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── ApprovalService ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ApprovalService.getAllPendingApprovals(Pageable)")
    class ApprovalServicePaginationTests {

        @Mock private ApprovalRepository approvalRepository;
        @Mock private com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
        @Mock private ApplicationContext applicationContext;
        @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

        private ApprovalService service;

        @BeforeEach
        void setUp() {
            service = new ApprovalService(approvalRepository, agentOperations, applicationContext, eventPublisher);
        }

        @Test
        @DisplayName("Returns paginated pending approvals mapped to DTOs (tenant-scoped)")
        void getAllPendingApprovals_Pageable_ReturnsMappedDTOs() {
            String orgId = "org-1";
            Pageable pageable = PageRequest.of(0, 10);
            Approval entity = new Approval(
                    "approval-1", "run-1", null, "session-1", "agent-1",
                    RunStatus.PENDING, "toolX", "{}", "admin", "Please review", "TIER_2",
                    "reasoning", "impact"
            );

            Page<Approval> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(approvalRepository.findByStatusAndOrgId(RunStatus.PENDING, orgId, pageable)).thenReturn(mockPage);

            Page<ApprovalDTO> result = service.getAllPendingApprovals(orgId, pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("approval-1", result.getContent().getFirst().id());
            assertEquals(RunStatus.PENDING, result.getContent().getFirst().status());
            verify(approvalRepository).findByStatusAndOrgId(RunStatus.PENDING, orgId, pageable);
        }

        @Test
        @DisplayName("Returns empty page when no pending approvals for tenant")
        void getAllPendingApprovals_Pageable_EmptyDataset() {
            String orgId = "org-1";
            Pageable pageable = PageRequest.of(0, 20);
            Page<Approval> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(approvalRepository.findByStatusAndOrgId(RunStatus.PENDING, orgId, pageable)).thenReturn(emptyPage);

            Page<ApprovalDTO> result = service.getAllPendingApprovals(orgId, pageable);

            assertTrue(result.isEmpty());
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Always filters by PENDING status + orgId regardless of pageable params")
        void getAllPendingApprovals_Pageable_AlwaysFiltersPending() {
            String orgId = "org-1";
            Pageable pageable = PageRequest.of(3, 7);
            Page<Approval> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(approvalRepository.findByStatusAndOrgId(RunStatus.PENDING, orgId, pageable)).thenReturn(emptyPage);

            service.getAllPendingApprovals(orgId, pageable);

            verify(approvalRepository).findByStatusAndOrgId(eq(RunStatus.PENDING), eq(orgId), eq(pageable));
            verify(approvalRepository, never()).findByStatusAndOrgId(eq(RunStatus.APPROVED), eq(orgId), any(Pageable.class));
            verify(approvalRepository, never()).findByStatusAndOrgId(eq(RunStatus.REJECTED), eq(orgId), any(Pageable.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── TeamService ───────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("TeamService.getAllTeams(Pageable)")
    class TeamServicePaginationTests {

        @Mock private TeamRepository teamRepository;
        @Mock private TeamMemberRepository teamMemberRepository;
        @Mock private com.operativus.agentmanager.control.repository.TransitionEdgeRepository transitionEdgeRepository;
        @Mock private jakarta.persistence.EntityManager entityManager;
        @Mock private com.operativus.agentmanager.control.finops.service.DailySpendService dailySpendService;

        private TeamService service;
        private org.mockito.MockedStatic<com.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

        @BeforeEach
        void setUp() {
            // Tenant scoping resolves orgId via AgentContextHolder; stub deterministically.
            mockedContext = org.mockito.Mockito.mockStatic(
                    com.operativus.agentmanager.core.callback.AgentContextHolder.class);
            mockedContext.when(com.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                    .thenReturn("TEST_ORG");
            service = new TeamService(teamRepository, teamMemberRepository, transitionEdgeRepository, entityManager, dailySpendService);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            if (mockedContext != null) mockedContext.close();
        }

        @Test
        @DisplayName("Returns paginated TeamDTOs mapped from entities")
        void getAllTeams_Pageable_ReturnsMappedDTOs() {
            Pageable pageable = PageRequest.of(0, 10);
            Team entity = new Team("team-1", "Alpha Squad", "Rapid response team",
                    "COORDINATE", "leader-1", "model-1", "Instructions", 4096, true, true, null);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            Page<Team> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(teamRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);

            Page<TeamDTO> result = service.getAllTeams(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("Alpha Squad", result.getContent().getFirst().name());
            assertEquals("COORDINATE", result.getContent().getFirst().teamMode());
            verify(teamRepository).findAllByOrgId("TEST_ORG", pageable);
        }

        @Test
        @DisplayName("Returns empty page when no teams exist")
        void getAllTeams_Pageable_EmptyDataset() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Team> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(teamRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(emptyPage);

            Page<TeamDTO> result = service.getAllTeams(pageable);

            assertTrue(result.isEmpty());
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Multiple teams on a mid-page preserve metadata")
        void getAllTeams_Pageable_MultiplePagesMetadata() {
            Pageable pageable = PageRequest.of(1, 3);
            Team t1 = new Team("team-2", "Bravo", null, "ROUTE", null, null, null, null, null, null, null);
            t1.setCreatedAt(LocalDateTime.now());
            t1.setUpdatedAt(LocalDateTime.now());
            Team t2 = new Team("team-3", "Charlie", null, "COLLABORATE", null, null, null, null, null, null, null);
            t2.setCreatedAt(LocalDateTime.now());
            t2.setUpdatedAt(LocalDateTime.now());

            Page<Team> mockPage = new PageImpl<>(List.of(t1, t2), pageable, 8);
            when(teamRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);

            Page<TeamDTO> result = service.getAllTeams(pageable);

            assertEquals(8, result.getTotalElements());
            assertEquals(3, result.getTotalPages());
            assertEquals(2, result.getContent().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── ModelService ──────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ModelService.getAllModels(Pageable)")
    class ModelServicePaginationTests {

        @Mock private ModelRepository modelRepository;
        @Mock private com.operativus.agentmanager.control.repository.AgentRepository agentRepository;
        @Mock private com.operativus.agentmanager.control.repository.RunRepository runRepository;
        @Mock private SettingsService settingsService;

        private ModelService service;

        @BeforeEach
        void setUp() {
            service = new ModelService(modelRepository, agentRepository, runRepository, settingsService, Collections.emptyList(), null);
        }

        @Test
        @DisplayName("Returns paginated ModelDTOs mapped from entities")
        void getAllModels_Pageable_ReturnsMappedDTOs() {
            Pageable pageable = PageRequest.of(0, 10);
            ModelEntity entity = new ModelEntity();
            entity.setId("model-1");
            entity.setName("GPT-4o");
            entity.setProvider("OPENAI");
            entity.setModelName("gpt-4o");
            entity.setSupportsTools(true);

            Page<ModelEntity> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(modelRepository.findAll(pageable)).thenReturn(mockPage);

            Page<ModelDTO> result = service.getAllModels(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("GPT-4o", result.getContent().getFirst().name());
            assertEquals("OPENAI", result.getContent().getFirst().provider());
            assertTrue(result.getContent().getFirst().supportsTools());
            verify(modelRepository).findAll(pageable);
        }

        @Test
        @DisplayName("Returns empty page when no models configured")
        void getAllModels_Pageable_EmptyDataset() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<ModelEntity> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(modelRepository.findAll(pageable)).thenReturn(emptyPage);

            Page<ModelDTO> result = service.getAllModels(pageable);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Paginated method is NOT cached (unlike unpaginated)")
        void getAllModels_Pageable_NotCached() {
            // This test verifies that calling the paginated method multiple times
            // still hits the repository each time (no cache layer)
            Pageable pageable = PageRequest.of(0, 5);
            Page<ModelEntity> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(modelRepository.findAll(pageable)).thenReturn(emptyPage);

            service.getAllModels(pageable);
            service.getAllModels(pageable);

            verify(modelRepository, times(2)).findAll(pageable);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ── ScheduleService ───────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ScheduleService.getAllSchedules(Pageable)")
    class ScheduleServicePaginationTests {

        @Mock private ScheduleRepository scheduleRepository;
        @Mock private ScheduleRunRepository scheduleRunRepository;
        @Mock private AgentRepository agentRepository;
        @Mock private WorkflowRepository workflowRepository;
        @Mock private com.operativus.agentmanager.control.repository.SessionRepository sessionRepository;
        @Mock private com.operativus.agentmanager.control.repository.SpotBatchJobRepository spotBatchJobRepository;
        @Mock private ObjectMapper objectMapper;

        private ScheduleService service;
        private org.mockito.MockedStatic<com.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

        @BeforeEach
        void setUp() {
            // The post-tenant-isolation service resolves orgId via AgentContextHolder.
            // Stub it deterministically here so tests don't depend on a Spring SecurityContext.
            mockedContext = org.mockito.Mockito.mockStatic(
                    com.operativus.agentmanager.core.callback.AgentContextHolder.class);
            mockedContext.when(com.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                    .thenReturn("TEST_ORG");
            service = new ScheduleService(scheduleRepository, scheduleRunRepository, agentRepository, workflowRepository, sessionRepository, spotBatchJobRepository, objectMapper);
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() {
            if (mockedContext != null) mockedContext.close();
        }

        @Test
        @DisplayName("Returns paginated ScheduleDTOs mapped from entities")
        void getAllSchedules_Pageable_ReturnsMappedDTOs() {
            Pageable pageable = PageRequest.of(0, 10);
            Schedule entity = new Schedule("sched-1", "Daily Report", "Run daily",
                    "0 0 * * *", "AGENT", "agent-1", null, "Generate report", true);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            Page<Schedule> mockPage = new PageImpl<>(List.of(entity), pageable, 1);
            when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);

            Page<ScheduleDTO> result = service.getAllSchedules(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("Daily Report", result.getContent().getFirst().name());
            assertEquals("0 0 * * *", result.getContent().getFirst().cronExpression());
            assertTrue(result.getContent().getFirst().isActive());
            verify(scheduleRepository).findAllByOrgId("TEST_ORG", pageable);
        }

        @Test
        @DisplayName("Returns empty page when no schedules exist")
        void getAllSchedules_Pageable_EmptyDataset() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Schedule> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
            when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(emptyPage);

            Page<ScheduleDTO> result = service.getAllSchedules(pageable);

            assertTrue(result.isEmpty());
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Handles large datasets with correct page count")
        void getAllSchedules_Pageable_LargeDataset() {
            Pageable pageable = PageRequest.of(0, 10);
            Schedule entity = new Schedule("sched-100", "Hourly Check", null,
                    "0 * * * *", "WORKFLOW", "wf-1", null, null, false);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            Page<Schedule> mockPage = new PageImpl<>(List.of(entity), pageable, 150);
            when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), eq(pageable))).thenReturn(mockPage);

            Page<ScheduleDTO> result = service.getAllSchedules(pageable);

            assertEquals(150, result.getTotalElements());
            assertEquals(15, result.getTotalPages());
            assertFalse(result.isEmpty());
        }
    }
}
