package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.*;
import com.operativus.agentmanager.core.entity.*;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.exception.StaleDataException;
import com.operativus.agentmanager.core.model.TopologyDTO;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentAdminServiceTest {

    private static final String TEST_ORG = "TEST_ORG";

    @Mock private AgentRepository agentRepository;
    @Mock private RunRepository runRepository;
    @Mock private AgentAuditRepository auditRepository;
    @Mock private TransitionEdgeRepository transitionEdgeRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private com.operativus.agentmanager.core.registry.AgentOperations agentOperations;

    private AgentAdminService service;
    private MockedStatic<com.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        mockedContext = mockStatic(com.operativus.agentmanager.core.callback.AgentContextHolder.class);
        mockedContext.when(com.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                .thenReturn(TEST_ORG);
        service = new AgentAdminService(agentRepository, runRepository, auditRepository, transitionEdgeRepository, objectMapper, agentOperations);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    @Test
    void getAllAgents_IncludeInactive_ReturnsAll() {
        AgentEntity entity = new AgentEntity();
        entity.setId("agent-1");
        entity.setName("Test Agent");
        
        when(agentRepository.findAllByOrgId(eq(TEST_ORG), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        Page<AgentDefinition> result = service.getAllAgents(Pageable.unpaged(), true);
        assertEquals(1, result.getTotalElements());
        assertEquals("Test Agent", result.getContent().get(0).name());
    }

    @Test
    void getAllAgents_ExcludeInactive_ReturnsOnlyActive() {
        AgentEntity entity = new AgentEntity();
        entity.setId("agent-2");
        entity.setName("Active Agent");

        when(agentRepository.findAllByOrgIdAndActive(eq(TEST_ORG), eq(true), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));

        Page<AgentDefinition> result = service.getAllAgents(Pageable.unpaged(), false);
        assertEquals(1, result.getTotalElements());
        assertEquals("Active Agent", result.getContent().get(0).name());
    }

    @Test
    void getAgent_Exists_ReturnsDto() {
        AgentEntity entity = new AgentEntity();
        entity.setId("agent-1");
        when(agentRepository.findByIdAndOrgId("agent-1", TEST_ORG)).thenReturn(Optional.of(entity));

        AgentDefinition result = service.getAgent("agent-1");
        assertEquals("agent-1", result.id());
    }

    @Test
    void getAgent_NotFound_ThrowsResourceNotFoundException() {
        when(agentRepository.findByIdAndOrgId("missing", TEST_ORG)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getAgent("missing"));
    }

    @Test
    void createAgent_GeneratesIdAndAudits() throws Exception {
        AgentDefinition dto = mock(AgentDefinition.class);
        lenient().when(dto.name()).thenReturn("New Agent");

        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(i -> {
            AgentEntity ent = i.getArgument(0);
            return ent; // Return as-is, ID should be populated by service
        });
        
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        AgentDefinition result = service.createAgent(dto);

        assertNotNull(result.id()); // ID generated
        assertTrue(result.active()); // Active by default

        verify(auditRepository).save(any(AgentAuditEntity.class));
    }

    @Test
    void updateAgent_persistsHumanReview_fallbackModelIds_finOpsRiskTier_optimizationModelId() {
        // Pin the field-drift bug: AgentAdminService.updateAgent had a hand-rolled field
        // map missing humanReview (REQ-HR-1), fallbackModelIds (gap #8),
        // finOpsRiskTier, and optimizationModelId — all set on the create path via
        // mapFromDefinition but silently dropped on update. Surfaced live during the
        // HITL E2E demo: PUT humanReview returned 200 OK but the gate never fired.
        AgentEntity existing = new AgentEntity();
        existing.setId("agent-1");
        when(agentRepository.findByIdAndOrgId("agent-1", TEST_ORG)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        com.operativus.agentmanager.core.model.HumanReview hr =
                new com.operativus.agentmanager.core.model.HumanReview(
                        true, null, null,
                        com.operativus.agentmanager.core.model.enums.OnRejectPolicy.SKIP,
                        null, null, null, null, null);

        AgentDefinition dto = mock(AgentDefinition.class);
        when(dto.name()).thenReturn("n");
        when(dto.description()).thenReturn("d");
        when(dto.instructions()).thenReturn("i");
        when(dto.modelId()).thenReturn("m");
        when(dto.securityTier()).thenReturn(1);
        when(dto.complianceTier()).thenReturn(ComplianceTier.TIER_1_STANDARD);
        when(dto.humanReview()).thenReturn(hr);
        when(dto.fallbackModelIds()).thenReturn(List.of("fb-1", "fb-2"));
        when(dto.finOpsRiskTier()).thenReturn(FinOpsRiskTier.LOW_RISK);
        when(dto.optimizationModelId()).thenReturn("opt-model");

        service.updateAgent("agent-1", dto);

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(captor.capture());
        AgentEntity saved = captor.getValue();
        assertEquals(hr, saved.getHumanReview(), "humanReview must round-trip on update");
        assertEquals(List.of("fb-1", "fb-2"), saved.getFallbackModelIds());
        assertEquals(FinOpsRiskTier.LOW_RISK, saved.getFinOpsRiskTier());
        assertEquals("opt-model", saved.getOptimizationModelId());
    }

    @Test
    void updateAgent_OptimisticLocking_ThrowsStaleDataException() {
        AgentDefinition dto = mock(AgentDefinition.class);
        AgentEntity existing = new AgentEntity();
        existing.setId("agent-1");

        when(agentRepository.findByIdAndOrgId("agent-1", TEST_ORG)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(AgentEntity.class, "agent-1"));

        StaleDataException ex = assertThrows(StaleDataException.class, () -> service.updateAgent("agent-1", dto));
        assertEquals("Agent", ex.getEntityName());
        assertEquals("agent-1", ex.getIdentifier());
    }

    @Test
    void deleteAgent_HasActiveRuns_ThrowsException() {
        AgentEntity existing = new AgentEntity();
        existing.setId("agent-1");

        when(agentRepository.findByIdAndOrgId("agent-1", TEST_ORG)).thenReturn(Optional.of(existing));
        when(runRepository.countByAgentIdAndStatus("agent-1", RunStatus.RUNNING)).thenReturn(1L);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.deleteAgent("agent-1"));
        assertTrue(ex.getMessage().contains("Cannot delete an agent with active or blocked runs"));
    }

    @Test
    void deleteAgent_NoActiveRuns_SoftDeletesAndAudits() {
        AgentEntity existing = new AgentEntity();
        existing.setId("agent-1");
        existing.setActive(true);

        when(agentRepository.findByIdAndOrgId("agent-1", TEST_ORG)).thenReturn(Optional.of(existing));
        when(runRepository.countByAgentIdAndStatus("agent-1", RunStatus.RUNNING)).thenReturn(0L);
        when(runRepository.countByAgentIdAndStatus("agent-1", RunStatus.PAUSED)).thenReturn(0L);

        service.deleteAgent("agent-1");

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(captor.capture());
        
        assertFalse(captor.getValue().isActive());
        verify(auditRepository).save(any(AgentAuditEntity.class));
    }

    @Test
    void getAgentTopology_BuildsGraphCorrectly() {
        AgentEntity root = new AgentEntity();
        root.setId("root-1");
        root.setName("Root Agent");
        root.setTools(List.of("search"));
        root.setTeam(true);
        root.setMembers(List.of("child-1"));

        AgentEntity child = new AgentEntity();
        child.setId("child-1");
        child.setName("Child Agent");

        TransitionEdge edge = new TransitionEdge();
        edge.setId("edge-1");
        edge.setSourceAgentId("root-1");
        edge.setTargetAgentId("child-1");

        when(agentRepository.findByIdAndOrgId("root-1", TEST_ORG)).thenReturn(Optional.of(root));
        when(agentRepository.findById("child-1")).thenReturn(Optional.of(child));
        when(transitionEdgeRepository.findByTeamId("root-1")).thenReturn(List.of(edge));

        TopologyDTO topology = service.getAgentTopology("root-1");

        assertEquals(3, topology.nodes().size()); // Root + 1 Tool + 1 Member
        assertEquals(2, topology.edges().size()); // Root -> Tool, Root -> Member
        assertEquals(1, topology.transitionEdges().size());
        
        assertTrue(topology.nodes().stream().anyMatch(n -> "agent".equals(n.type()) && "root-1".equals(n.id())));
        assertTrue(topology.nodes().stream().anyMatch(n -> "tool".equals(n.type()) && "tool_search".equals(n.id())));
        assertTrue(topology.nodes().stream().anyMatch(n -> "member".equals(n.type()) && "child-1".equals(n.id())));
    }

    // F8 — admin cancel of a non-terminal run delegates to AgentOperations.cancelRun
    // (which routes through RunExecutionManager → AgentRunFinalizer). Direct
    // run.setStatus + save would race the contract owner's @Version-checked write.
    @Test
    void cancelRun_runningRow_delegatesToAgentOperations() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", TEST_ORG);
        run.setId("run-running");
        run.setStatus(RunStatus.RUNNING);
        // Type-narrow to RunOperations to disambiguate findById/save between
        // RunRepository's two parents (JpaRepository + RunOperations).
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        when(runOps.findById("run-running")).thenReturn(Optional.of(run));

        service.cancelRun("run-running");

        verify(agentOperations).cancelRun("run-running");
        verify(runOps, never()).save(any(AgentRun.class));
    }

    // F8 — admin-API contract: terminal-state rows surface a 4xx (BusinessValidationException)
    // rather than silently no-op'ing. This distinguishes "cancelled by you" from "already
    // in a terminal state" for the admin caller. Don't delegate; throw before reaching
    // AgentOperations.
    @Test
    void cancelRun_terminalRow_throwsAndDoesNotDelegate() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", TEST_ORG);
        run.setId("run-completed");
        run.setStatus(RunStatus.COMPLETED);
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        when(runOps.findById("run-completed")).thenReturn(Optional.of(run));

        assertThrows(BusinessValidationException.class, () -> service.cancelRun("run-completed"));
        verify(agentOperations, never()).cancelRun(any());
    }

    // PR #972 cross-tenant guard. Pre-fix (PR #969 only added the controller class
    // gate hasRole('ADMIN')), an admin from org A could cancel a run from org B by
    // knowing the runId. cancelRun() now compares run.orgId against callerOrgId()
    // and throws ResourceNotFoundException (404, not 403) on mismatch — matches the
    // existence-leak-protection pattern used by ComplianceController and the rest
    // of the tenant-scoped *AdminController paths.
    @Test
    void cancelRun_matchingOrgId_delegatesToAgentOperations() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", TEST_ORG);
        run.setId("run-same-org");
        run.setStatus(RunStatus.RUNNING);
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        when(runOps.findById("run-same-org")).thenReturn(Optional.of(run));

        service.cancelRun("run-same-org");

        verify(agentOperations).cancelRun("run-same-org");
    }

    @Test
    void cancelRun_foreignOrgRun_throws404_andDoesNotDelegate() {
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", "FOREIGN_ORG");
        run.setId("run-foreign-org");
        run.setStatus(RunStatus.RUNNING);
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        when(runOps.findById("run-foreign-org")).thenReturn(Optional.of(run));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelRun("run-foreign-org"));
        verify(agentOperations, never()).cancelRun(any());
    }

    @Test
    void cancelRun_legacyNullOrgRun_throws404_andDoesNotDelegate() {
        // Legacy rows from before tenant scoping was added may have null org_id.
        // The safe default is to refuse cancellation rather than allow it via any
        // org's admin — matches the .equals(null) → false semantics intentionally.
        AgentRun run = new AgentRun("agent-1", "session-1", "input", "user-1", null);
        run.setId("run-legacy-null-org");
        run.setStatus(RunStatus.RUNNING);
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        when(runOps.findById("run-legacy-null-org")).thenReturn(Optional.of(run));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelRun("run-legacy-null-org"));
        verify(agentOperations, never()).cancelRun(any());
    }
}
