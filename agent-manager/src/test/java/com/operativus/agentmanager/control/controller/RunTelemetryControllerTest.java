package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.AgentReflectionRepository;
import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.service.RunCostTreeService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.registry.RunOperations;
import com.operativus.agentmanager.core.entity.AgentReflectionEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import com.operativus.agentmanager.core.model.RunCostNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RunTelemetryControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private RunRepository runRepository;
    @Mock private OrchestrationDecisionRepository decisionRepository;
    @Mock private RunCostTreeService runCostTreeService;
    @Mock private AgentReflectionRepository reflectionRepository;

    private RunTelemetryController controller;

    @BeforeEach
    void setUp() {
        controller = new RunTelemetryController(runRepository, decisionRepository, runCostTreeService, reflectionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // --- Tree cost ---

    @Test
    void getTreeCost_ExistingRun_ReturnsRollup() throws Exception {
        Object[] row = new Object[]{"root-42", new BigDecimal("12.50"), 7L};
        when(runRepository.findTreeCostByAnyRunId("run-child-1"))
                .thenReturn(Collections.singletonList(row));

        mockMvc.perform(get("/api/v1/runs/run-child-1/tree-cost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootRunId").value("root-42"))
                .andExpect(jsonPath("$.treeTotalCostUsd").value(12.50))
                .andExpect(jsonPath("$.runCount").value(7));
    }

    @Test
    void getTreeCost_UnknownRun_Returns404() throws Exception {
        when(runRepository.findTreeCostByAnyRunId("missing"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/runs/missing/tree-cost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTreeCost_RowCountCastFromInteger_Works() throws Exception {
        // Postgres BIGINT may surface as either Long or BigInteger via JDBC; the
        // controller's ((Number) row[2]).longValue() conversion must handle both.
        Object[] row = new Object[]{"root-1", new BigDecimal("0.00"), java.math.BigInteger.valueOf(1L)};
        when(runRepository.findTreeCostByAnyRunId("root-1"))
                .thenReturn(Collections.singletonList(row));

        mockMvc.perform(get("/api/v1/runs/root-1/tree-cost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runCount").value(1));
    }

    // --- Orchestration decisions ---

    @Test
    void getOrchestrationDecisions_ReturnsTimelineInOrder() throws Exception {
        OrchestrationDecisionEntity d1 = decision(1L, "run-9", "org-a", "ROUTER", "SELECT_AGENT", "agent-x", Instant.parse("2026-04-23T12:00:00Z"));
        OrchestrationDecisionEntity d2 = decision(2L, "run-9", "org-a", "COORDINATOR", "DELEGATE", "agent-y", Instant.parse("2026-04-23T12:00:05Z"));
        when(decisionRepository.findByRunIdOrderByCreatedAtAsc("run-9"))
                .thenReturn(List.of(d1, d2));

        mockMvc.perform(get("/api/v1/runs/run-9/orchestration-decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].strategy").value("ROUTER"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].strategy").value("COORDINATOR"));
    }

    @Test
    void getOrchestrationDecisions_NoDecisions_ReturnsEmptyArray() throws Exception {
        when(decisionRepository.findByRunIdOrderByCreatedAtAsc("run-empty"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/runs/run-empty/orchestration-decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getOrchestrationDecisions_WithMatchingOrgHeader_IncludesRow() throws Exception {
        OrchestrationDecisionEntity d = decision(1L, "run-9", "org-a", "ROUTER", "SELECT_AGENT", "agent-x", Instant.now());
        when(decisionRepository.findByRunIdOrderByCreatedAtAsc("run-9"))
                .thenReturn(List.of(d));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-9/orchestration-decisions"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getOrchestrationDecisions_WithMismatchedBoundOrgId_DropsRow() throws Exception {
        OrchestrationDecisionEntity d = decision(1L, "run-9", "org-a", "ROUTER", "SELECT_AGENT", "agent-x", Instant.now());
        when(decisionRepository.findByRunIdOrderByCreatedAtAsc("run-9"))
                .thenReturn(List.of(d));

        ScopedValue.where(AgentContextHolder.orgId, "org-b").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-9/orchestration-decisions"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(0));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getOrchestrationDecisions_RowWithNullOrgId_NeverFiltered() throws Exception {
        // Decisions that were logged before tenant-scoping was wired have org_id=NULL.
        // These must not be hidden by a downstream org filter — they're legitimate
        // historical rows that predate multi-tenancy.
        OrchestrationDecisionEntity d = decision(1L, "run-9", null, "ROUTER", "SELECT_AGENT", "agent-x", Instant.now());
        when(decisionRepository.findByRunIdOrderByCreatedAtAsc("run-9"))
                .thenReturn(List.of(d));

        ScopedValue.where(AgentContextHolder.orgId, "org-b").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-9/orchestration-decisions"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    // --- Cost tree ---

    @Test
    void getCostTree_ExistingRun_ReturnsNestedTree() throws Exception {
        RunCostNode grandchild = new RunCostNode("gc", "agent-c", 2, 10L, 20L,
                new BigDecimal("0.50"), List.of());
        RunCostNode child = new RunCostNode("child", "agent-b", 1, 100L, 200L,
                new BigDecimal("2.00"), List.of(grandchild));
        RunCostNode root = new RunCostNode("root", "agent-a", 0, 500L, 1000L,
                new BigDecimal("12.50"), List.of(child));
        when(runCostTreeService.getCostTree("root", 10)).thenReturn(Optional.of(root));

        mockMvc.perform(get("/api/v1/runs/root/cost-tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("root"))
                .andExpect(jsonPath("$.depth").value(0))
                .andExpect(jsonPath("$.subRuns.length()").value(1))
                .andExpect(jsonPath("$.subRuns[0].id").value("child"))
                .andExpect(jsonPath("$.subRuns[0].subRuns[0].id").value("gc"))
                .andExpect(jsonPath("$.subRuns[0].subRuns[0].depth").value(2));
    }

    @Test
    void getCostTree_MissingRoot_Returns404() throws Exception {
        when(runCostTreeService.getCostTree("missing", 10)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/runs/missing/cost-tree"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCostTree_MaxDepthZero_ReturnsRootOnly() throws Exception {
        RunCostNode root = new RunCostNode("root", "agent-a", 0, 0L, 0L, BigDecimal.ZERO, List.of());
        when(runCostTreeService.getCostTree("root", 0)).thenReturn(Optional.of(root));

        mockMvc.perform(get("/api/v1/runs/root/cost-tree?maxDepth=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subRuns.length()").value(0));
    }

    @Test
    void getCostTree_MaxDepthAboveCap_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/runs/root/cost-tree?maxDepth=26"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCostTree_NegativeMaxDepth_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/runs/root/cost-tree?maxDepth=-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCostTree_MaxDepthAtCap_DelegatesToService() throws Exception {
        when(runCostTreeService.getCostTree("root", 25))
                .thenReturn(Optional.of(new RunCostNode("root", "agent-a", 0,
                        null, null, null, List.of())));

        mockMvc.perform(get("/api/v1/runs/root/cost-tree?maxDepth=25"))
                .andExpect(status().isOk());
    }

    // --- IDOR: tree-cost ---

    @Test
    void getTreeCost_MatchingBoundOrgId_Returns200() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("run-child-1")).thenReturn(Optional.of(run));
        Object[] row = new Object[]{"root-42", new BigDecimal("12.50"), 7L};
        when(runRepository.findTreeCostByAnyRunId("run-child-1"))
                .thenReturn(Collections.singletonList(row));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-child-1/tree-cost"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.rootRunId").value("root-42"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getTreeCost_MismatchedBoundOrgId_Returns404() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("run-child-1")).thenReturn(Optional.of(run));

        ScopedValue.where(AgentContextHolder.orgId, "org-b").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-child-1/tree-cost"))
                        .andExpect(status().isNotFound());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getTreeCost_UnknownRunWithBoundOrgId_Returns404() throws Exception {
        when(((RunOperations) runRepository).findById("missing")).thenReturn(Optional.empty());

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/missing/tree-cost"))
                        .andExpect(status().isNotFound());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    // --- IDOR: cost-tree ---

    @Test
    void getCostTree_MatchingBoundOrgId_Returns200() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("root")).thenReturn(Optional.of(run));
        RunCostNode node = new RunCostNode("root", "agent-a", 0, 0L, 0L, BigDecimal.ZERO, List.of());
        when(runCostTreeService.getCostTree("root", 10)).thenReturn(Optional.of(node));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/root/cost-tree"))
                        .andExpect(status().isOk());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getCostTree_MismatchedBoundOrgId_Returns404() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("root")).thenReturn(Optional.of(run));

        ScopedValue.where(AgentContextHolder.orgId, "org-b").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/root/cost-tree"))
                        .andExpect(status().isNotFound());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    // --- IDOR: reflections ---

    @Test
    void getReflections_MatchingBoundOrgId_Returns200() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("run-9")).thenReturn(Optional.of(run));
        when(reflectionRepository.findByRunIdOrderByStepIndexAsc("run-9")).thenReturn(List.of());

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-9/reflections"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(0));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getReflections_MismatchedBoundOrgId_Returns404() throws Exception {
        AgentRun run = new AgentRun("agent-1", "sess-1", "input", "user-1", "org-a");
        when(((RunOperations) runRepository).findById("run-9")).thenReturn(Optional.of(run));

        ScopedValue.where(AgentContextHolder.orgId, "org-b").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/run-9/reflections"))
                        .andExpect(status().isNotFound());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getReflections_UnknownRunWithBoundOrgId_Returns404() throws Exception {
        when(((RunOperations) runRepository).findById("missing")).thenReturn(Optional.empty());

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/runs/missing/reflections"))
                        .andExpect(status().isNotFound());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private OrchestrationDecisionEntity decision(Long id, String runId, String orgId, String strategy,
                                                 String type, String selectedAgent, Instant createdAt) {
        OrchestrationDecisionEntity e = new OrchestrationDecisionEntity();
        // id is @GeneratedValue with no setter; reflection path matches how JPA assigns
        // it on flush, so reflect to avoid adding a test-only setter to the entity.
        try {
            java.lang.reflect.Field f = OrchestrationDecisionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        e.setRunId(runId);
        e.setOrgId(orgId);
        e.setStrategy(strategy);
        e.setDecisionType(type);
        e.setSelectedAgentId(selectedAgent);
        e.setRationale("test rationale");
        e.setDecisionPayload(Map.of("k", "v"));
        e.setCreatedAt(createdAt);
        return e;
    }
}
