package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgentReflectionRepository;
import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.registry.RunOperations;
import com.operativus.agentmanager.control.service.RunCostTreeService;
import com.operativus.agentmanager.core.entity.AgentReflectionEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import com.operativus.agentmanager.core.model.AgentReflectionResponse;
import com.operativus.agentmanager.core.model.OrchestrationDecisionDTO;
import com.operativus.agentmanager.core.model.RunCostNode;
import com.operativus.agentmanager.core.model.RunTreeCostDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain Responsibility: Read-side HTTP surface for the forensic telemetry tables added
 *     by AGM logging plan §5.14 ({@code orchestration_decisions}) and §5.22
 *     ({@code vw_run_tree_cost}). Both are insert-only (or view-only) sources whose write
 *     path already exists — this controller closes the plan by surfacing them for client
 *     consumption.
 * State: Stateless controller.
 *
 * <p>Endpoints honour the same tenant-scoping convention used by
 * {@link RunEventSseController}: an optional {@code X-Org-Id} request header filters rows
 * whose {@code org_id} column disagrees. Absent header = no filter.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunTelemetryController {

    /**
     * Hard cap on {@code maxDepth} for the {@code /cost-tree} endpoint.
     * Prevents a pathological caller from traversing an unbounded subtree and
     * overwhelming the database. 25 is comfortably above real-world orchestration
     * nesting (the deepest production trees observed cap at ~10).
     */
    public static final int COST_TREE_MAX_DEPTH_CAP = 25;

    private final RunRepository runRepository;
    private final OrchestrationDecisionRepository decisionRepository;
    private final RunCostTreeService runCostTreeService;
    private final AgentReflectionRepository reflectionRepository;

    public RunTelemetryController(RunRepository runRepository,
                                  OrchestrationDecisionRepository decisionRepository,
                                  RunCostTreeService runCostTreeService,
                                  AgentReflectionRepository reflectionRepository) {
        this.runRepository = runRepository;
        this.decisionRepository = decisionRepository;
        this.runCostTreeService = runCostTreeService;
        this.reflectionRepository = reflectionRepository;
    }

    /**
     * @summary Returns the aggregate USD cost of the run tree that {@code runId} belongs to.
     * @logic
     * - Uses {@link RunRepository#findTreeCostByAnyRunId(String)}, which walks
     *   {@code parent_run_id} up to the root and joins {@code vw_run_tree_cost}.
     * - Returns 404 if the run ID does not exist or has no rollup row (e.g. the run and
     *   all its parents were purged by retention).
     * - The rollup is one row per root tree — {@code root_run_id},
     *   {@code tree_total_cost_usd} (summed with {@code COALESCE(SUM, 0)}), and
     *   {@code run_count}.
     */
    @GetMapping("/{runId}/tree-cost")
    public ResponseEntity<RunTreeCostDTO> getTreeCost(
            @PathVariable("runId") String runId) {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null) {
            RunOperations runOps = runRepository;
            Optional<AgentRun> run = runOps.findById(runId);
            if (run.isEmpty()) return ResponseEntity.notFound().build();
            String runOrg = run.get().getOrgId();
            if (runOrg != null && !orgId.equals(runOrg)) return ResponseEntity.notFound().build();
        }
        List<Object[]> rows = runRepository.findTreeCostByAnyRunId(runId);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Object[] row = rows.get(0);
        return ResponseEntity.ok(new RunTreeCostDTO(
                (String) row[0],
                (BigDecimal) row[1],
                ((Number) row[2]).longValue()));
    }

    /**
     * @summary Returns the orchestration-decision timeline for {@code runId} in insertion
     *     order. Reconstructs nested orchestration dispatches
     *     (e.g. Coordinator → Router → Swarm) for a single run.
     * @logic
     * - Uses {@link OrchestrationDecisionRepository#findByRunIdOrderByCreatedAtAsc(String)}.
     * - If {@code X-Org-Id} is set, rows whose {@code org_id} differs are dropped.
     * - Returns {@code 200 OK} with an empty array if the run has no decisions (distinct
     *   from a missing run — this endpoint is per-run-id and has no run-existence check).
     */
    @GetMapping("/{runId}/orchestration-decisions")
    public ResponseEntity<List<OrchestrationDecisionDTO>> getOrchestrationDecisions(
            @PathVariable("runId") String runId) {
        String orgId = AgentContextHolder.getOrgId();
        List<OrchestrationDecisionDTO> decisions = decisionRepository
                .findByRunIdOrderByCreatedAtAsc(runId)
                .stream()
                .filter(d -> orgId == null || d.getOrgId() == null || Objects.equals(orgId, d.getOrgId()))
                .map(RunTelemetryController::toDto)
                .toList();
        return ResponseEntity.ok(decisions);
    }

    /**
     * @summary Returns the depth-limited cost tree rooted at {@code runId}
     *     (observability plan Phase 1 T006.2). Callers pass any run in a tree — not
     *     just the root — but the tree is built downward from that run, not upward
     *     to the tree root (use {@code /tree-cost} for upward rollup).
     * @logic
     * - {@code maxDepth} default 10, capped at {@link #COST_TREE_MAX_DEPTH_CAP}.
     *   Larger values return {@code 400 Bad Request}. Negative values also 400.
     * - Delegates tree assembly to {@link RunCostTreeService#getCostTree}.
     * - {@code 404 Not Found} if the root run does not exist.
     * - Emits {@code agm.observability.run_cost.tree{depth_reached}} via the service.
     */
    @GetMapping("/{runId}/cost-tree")
    public ResponseEntity<RunCostNode> getCostTree(
            @PathVariable("runId") String runId,
            @RequestParam(value = "maxDepth", defaultValue = "10") int maxDepth) {
        if (maxDepth < 0 || maxDepth > COST_TREE_MAX_DEPTH_CAP) {
            return ResponseEntity.badRequest().build();
        }
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null) {
            RunOperations runOps = runRepository;
            Optional<AgentRun> run = runOps.findById(runId);
            if (run.isEmpty()) return ResponseEntity.notFound().build();
            String runOrg = run.get().getOrgId();
            if (runOrg != null && !orgId.equals(runOrg)) return ResponseEntity.notFound().build();
        }
        Optional<RunCostNode> tree = runCostTreeService.getCostTree(runId, maxDepth);
        return tree.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @summary Returns the agent_reflections rows authored during a single run, ordered
     *     by step_index (insertion order). Backs the per-run Reflection Log tab on
     *     RunDetailPage (§3 P3 ReflectionLog viewer).
     * @logic Reuses {@link AgentReflectionRepository#findByRunIdOrderByStepIndexAsc}.
     *     Returns {@code 200 OK} with an empty array if the run has no reflections —
     *     reflections are produced by self-critique advisors that only fire when an
     *     agent is configured to use them, so a run with zero reflections is normal.
     *     Unpaginated because reflection chains are bounded (typically 1–10 per run);
     *     a runaway count would be a bug worth seeing in full anyway.
     */
    @GetMapping("/{runId}/reflections")
    public ResponseEntity<List<AgentReflectionResponse>> getReflections(
            @PathVariable("runId") String runId) {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId != null) {
            RunOperations runOps = runRepository;
            Optional<AgentRun> run = runOps.findById(runId);
            if (run.isEmpty()) return ResponseEntity.notFound().build();
            String runOrg = run.get().getOrgId();
            if (runOrg != null && !orgId.equals(runOrg)) return ResponseEntity.notFound().build();
        }
        List<AgentReflectionResponse> reflections = reflectionRepository
                .findByRunIdOrderByStepIndexAsc(runId)
                .stream()
                .map(RunTelemetryController::reflectionToDto)
                .toList();
        return ResponseEntity.ok(reflections);
    }

    private static AgentReflectionResponse reflectionToDto(AgentReflectionEntity e) {
        return new AgentReflectionResponse(
                e.getReflectionId(),
                e.getAgentId(),
                e.getReasoning(),
                e.getRunId(),
                e.getCreatedAt());
    }

    private static OrchestrationDecisionDTO toDto(OrchestrationDecisionEntity e) {
        return new OrchestrationDecisionDTO(
                e.getId(),
                e.getRunId(),
                e.getOrgId(),
                e.getStrategy(),
                e.getDecisionType(),
                e.getSelectedAgentId(),
                e.getRationale(),
                e.getDecisionPayload(),
                e.getCreatedAt());
    }
}
