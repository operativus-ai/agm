package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.repository.WorkflowStepRepository;
import com.operativus.agentmanager.control.websocket.WorkflowWebSocketHandler;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.WorkflowStepDTO;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Targeted unit coverage for the Tier 2.4 PR 7 F-A workflow REJECTED cascade —
 * pins {@link WorkflowService#cancelWorkflowRun} contract: idempotent on terminal
 * states, mutates non-terminal rows to CANCELLED, no-op on missing rows.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowStepRepository workflowStepRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private com.operativus.agentmanager.control.repository.SessionRepository sessionRepository;
    @Mock private WorkflowWebSocketHandler webSocketHandler;
    @Mock private AgentOperations agentOperations;
    @Mock private Tracer tracer;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowRepository, workflowStepRepository, workflowRunRepository,
                agentRepository, sessionRepository, webSocketHandler, agentOperations, tracer,
                List.<WorkflowStepExecutorExtension>of(), new SimpleMeterRegistry(),
                List.<com.operativus.agentmanager.core.spi.RouteSelector>of(),
                null,
                null,
                null,
                null);
    }

    @Test
    void cancelWorkflowRun_pausedRow_setsCancelledAndSaves() {
        WorkflowRun wr = new WorkflowRun();
        wr.setId("wr-1");
        wr.setStatus(RunStatus.PAUSED);
        when(workflowRunRepository.findById("wr-1")).thenReturn(Optional.of(wr));

        service.cancelWorkflowRun("wr-1", "rejected reason");

        assertEquals(RunStatus.CANCELLED, wr.getStatus());
        verify(workflowRunRepository).save(wr);
    }

    @Test
    void cancelWorkflowRun_alreadyCancelled_isIdempotentNoOp() {
        WorkflowRun wr = new WorkflowRun();
        wr.setId("wr-2");
        wr.setStatus(RunStatus.CANCELLED);
        when(workflowRunRepository.findById("wr-2")).thenReturn(Optional.of(wr));

        service.cancelWorkflowRun("wr-2", "rejected reason");

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void cancelWorkflowRun_alreadyCompleted_isIdempotentNoOp() {
        WorkflowRun wr = new WorkflowRun();
        wr.setId("wr-3");
        wr.setStatus(RunStatus.COMPLETED);
        when(workflowRunRepository.findById("wr-3")).thenReturn(Optional.of(wr));

        service.cancelWorkflowRun("wr-3", "rejected reason");

        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void cancelWorkflowRun_missingRow_isIdempotentNoOp() {
        when(workflowRunRepository.findById("wr-missing")).thenReturn(Optional.empty());

        service.cancelWorkflowRun("wr-missing", "reason");

        verify(workflowRunRepository, never()).save(any());
    }

    // ---- REQ-DR-4 PR-1: addWorkflowStep router_config validation ----

    private WorkflowStepDTO routerStepDto(RouterStepConfig cfg) {
        return new WorkflowStepDTO(null, "wf-1", 1, null, "ROUTER", cfg, null, null, null, null, null, null);
    }

    @Test
    void addWorkflowStep_routerWithNullConfig_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", routerStepDto(null)));
        assertEquals("ROUTER step requires routerConfig", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_routerWithMissingSelectorType_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        RouterStepConfig cfg = new RouterStepConfig(null, "$.x", java.util.Map.of("k", "step-1"), null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", routerStepDto(cfg)));
        assertEquals("ROUTER step requires routerConfig.selectorType", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_routerWithEmptyChoices_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        RouterStepConfig cfg = new RouterStepConfig(RouteSelectorType.RULE, "$.x", java.util.Map.of(), null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", routerStepDto(cfg)));
        assertEquals("ROUTER step requires routerConfig.choices (non-empty map)", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_routerWithNullChoices_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        RouterStepConfig cfg = new RouterStepConfig(RouteSelectorType.HITL, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", routerStepDto(cfg)));
        assertEquals("ROUTER step requires routerConfig.choices (non-empty map)", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_routerHappyPath_savesWithRouterConfig() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.save(any(WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RouterStepConfig cfg = new RouterStepConfig(
                RouteSelectorType.RULE, "$.decision",
                java.util.Map.of("approve", "step-a", "reject", "step-b"), "approve");

        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", routerStepDto(cfg));

        assertEquals("ROUTER", saved.action());
        assertEquals(cfg, saved.routerConfig());
        org.mockito.ArgumentCaptor<WorkflowStep> captor = org.mockito.ArgumentCaptor.forClass(WorkflowStep.class);
        verify(workflowStepRepository).save(captor.capture());
        assertEquals(cfg, captor.getValue().getRouterConfig());
    }

    @Test
    void addWorkflowStep_nonRouterWithRouterConfig_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        RouterStepConfig cfg = new RouterStepConfig(
                RouteSelectorType.RULE, "$.x", java.util.Map.of("k", "step-1"), null);
        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, null, "AGENT", cfg, null, null, null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        assertEquals("routerConfig is only valid for ROUTER steps", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    // ---- REQ-DR-6 PR-2: addWorkflowStep onReject validation ----

    @Test
    void addWorkflowStep_conditionWithSkipPolicy_succeeds() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // The CONDITION step stores its expression in the agent_id field, so we
        // pass a placeholder string ("contains:foo") that bypasses the
        // agent-exists check (only enforced when agentId is non-null AND not the
        // placeholder); but we still need the agentExists mock for the lookup.

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "SKIP", null, null, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        assertEquals("SKIP", saved.onReject());
    }

    @Test
    void addWorkflowStep_conditionWithCancelPolicy_succeeds() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "CANCEL", null, null, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        assertEquals("CANCEL", saved.onReject());
    }

    @Test
    void addWorkflowStep_nonConditionWithOnReject_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(agentRepository.existsByIdAndOrgId(any(), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "agent-1", "AGENT",
                null, "CANCEL", null, null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        assertEquals("onReject is only valid for CONDITION steps", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_conditionWithUnknownPolicy_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "GARBAGE_POLICY", null, null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("Unknown onReject policy"),
                "Expected 'Unknown onReject policy' message; got: " + ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    // ---- REQ-DR-6 PR-3: ELSE_BRANCH validation ----

    @Test
    void addWorkflowStep_elseBranchWithoutElseStepId_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "ELSE_BRANCH", null, null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        assertEquals("onReject=ELSE_BRANCH requires elseStepId", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_elseStepIdWithoutElseBranch_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "SKIP", "step-orphan", null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        assertEquals("elseStepId is only valid when onReject=ELSE_BRANCH", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_elseBranchTargetCrossWorkflow_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        // Target step exists, but belongs to wf-OTHER → must be rejected.
        com.operativus.agentmanager.core.entity.WorkflowStep otherStep =
                new com.operativus.agentmanager.core.entity.WorkflowStep(
                        "step-other", "wf-OTHER", 1, "agent-x", "AGENT");
        when(workflowStepRepository.findById("step-other")).thenReturn(Optional.of(otherStep));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "ELSE_BRANCH", "step-other", null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("must reference a step in the same workflow"),
                "got: " + ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_elseBranchTargetMissing_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.findById("step-missing")).thenReturn(Optional.empty());

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "ELSE_BRANCH", "step-missing", null, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("must reference a step in the same workflow"),
                "got: " + ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_elseBranchHappyPath_succeeds() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        com.operativus.agentmanager.core.entity.WorkflowStep targetStep =
                new com.operativus.agentmanager.core.entity.WorkflowStep(
                        "step-else", "wf-1", 3, "agent-x", "AGENT");
        when(workflowStepRepository.findById("step-else")).thenReturn(Optional.of(targetStep));
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "ELSE_BRANCH", "step-else", null, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        assertEquals("ELSE_BRANCH", saved.onReject());
        assertEquals("step-else", saved.elseStepId());
    }

    @Test
    void addWorkflowStep_conditionWithNullOnReject_succeeds_preservingDefault() {
        // null onReject is the default SKIP behavior — must not be rejected.
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, null, null, null, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        org.junit.jupiter.api.Assertions.assertNull(saved.onReject());
    }

    // ---- REQ-DR-6 PR-4: requiresConfirmation validation ----

    @Test
    void addWorkflowStep_requiresConfirmationOnNonCondition_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(agentRepository.existsByIdAndOrgId(any(), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "agent-1", "AGENT",
                null, null, null, true, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        assertEquals("requiresConfirmation is only valid for CONDITION steps", ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_requiresConfirmationWithCancelPolicy_throws() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "CANCEL", null, true, null, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addWorkflowStep("wf-1", dto));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("not allowed with onReject=CANCEL"),
                "got: " + ex.getMessage());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void addWorkflowStep_requiresConfirmationOnCondition_succeeds() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "contains:foo", "CONDITION",
                null, "SKIP", null, true, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        assertEquals(true, saved.requiresConfirmation());
    }

    @Test
    void addWorkflowStep_requiresConfirmationFalse_succeedsOnAnyStep() {
        when(workflowRepository.existsByIdAndOrgId(eq("wf-1"), any())).thenReturn(true);
        when(agentRepository.existsByIdAndOrgId(any(), any())).thenReturn(true);
        when(workflowStepRepository.save(any(com.operativus.agentmanager.core.entity.WorkflowStep.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowStepDTO dto = new WorkflowStepDTO(null, "wf-1", 1, "agent-1", "AGENT",
                null, null, null, false, null, null, null);
        WorkflowStepDTO saved = service.addWorkflowStep("wf-1", dto);
        // entity.isRequiresConfirmation() returns primitive false → DTO carries false
        assertEquals(false, saved.requiresConfirmation());
    }

    // ---- REQ-DR-4 PR-4: continueAfterRouteSelection validation paths ----

    @Test
    void continueAfterRouteSelection_blankChoiceKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.continueAfterRouteSelection("wr-1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.continueAfterRouteSelection("wr-1", null));
    }

    @Test
    void continueAfterRouteSelection_unknownRunId_throws() {
        when(workflowRunRepository.findById("wr-missing")).thenReturn(Optional.empty());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.continueAfterRouteSelection("wr-missing", "approve"));
        assertEquals("WorkflowRun not found: wr-missing", ex.getMessage());
    }

    @Test
    void continueAfterRouteSelection_nonAwaitingState_isSilentNoOp() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wr-running");
        run.setStatus(RunStatus.RUNNING);
        run.setOrgId(com.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG);
        when(workflowRunRepository.findById("wr-running")).thenReturn(Optional.of(run));

        // Should log + return silently rather than throw; downstream resume is not invoked.
        service.continueAfterRouteSelection("wr-running", "approve");

        verify(workflowStepRepository, never()).findByWorkflowIdOrderByStepOrderAsc(any());
    }

    @Test
    void continueAfterRouteSelection_invalidChoiceKey_throws() {
        WorkflowRun run = new WorkflowRun();
        run.setId("wr-await");
        run.setStatus(RunStatus.AWAITING_ROUTE_SELECTION);
        run.setWorkflowId("wf-1");
        run.setCurrentStepOrder(1);
        run.setOrgId(com.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG);
        when(workflowRunRepository.findById("wr-await")).thenReturn(Optional.of(run));

        com.operativus.agentmanager.core.entity.WorkflowStep routerStep =
                new com.operativus.agentmanager.core.entity.WorkflowStep(
                        "step-1", "wf-1", 1, null, "ROUTER",
                        new RouterStepConfig(RouteSelectorType.HITL, null,
                                java.util.Map.of("approve", "step-2"), null));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc("wf-1"))
                .thenReturn(List.of(routerStep));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.continueAfterRouteSelection("wr-await", "reject"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("not a declared choice"),
                "Expected 'not a declared choice' in message: " + ex.getMessage());
    }
}
