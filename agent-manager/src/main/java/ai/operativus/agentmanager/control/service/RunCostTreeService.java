package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.core.model.RunCostNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Assembles the depth-limited run-cost tree returned by
 * {@code GET /api/v1/runs/{runId}/cost-tree} (observability plan Phase 1 T006.2). Flat
 * CTE rows from {@link RunRepository#findRunCostTree} are grouped by
 * {@code parent_run_id} and linked into the nested {@link RunCostNode} structure the
 * UI consumes — the CTE does depth-bounded traversal, the service does tree assembly,
 * the repository stays query-only.
 *
 * <p>Every invocation records an {@code agm.observability.run_cost.tree} Timer tagged
 * with the actual depth reached, so operators can see when trees bump against the
 * {@code maxDepth} cap.
 * State: Stateless Spring-managed bean (dependencies injected via constructor).
 */
@Service
public class RunCostTreeService {

    private final RunRepository runRepository;
    private final MeterRegistry meterRegistry;

    public RunCostTreeService(RunRepository runRepository, MeterRegistry meterRegistry) {
        this.runRepository = runRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @summary Returns the depth-limited cost tree rooted at {@code rootRunId}, or
     *     {@link Optional#empty()} if no such run exists.
     * @logic
     * - Delegates to {@link RunRepository#findRunCostTree} which emits one row per
     *   descendant (including the root) up to {@code maxDepth} levels down.
     * - Groups rows by {@code parent_run_id} into a {@code Map<parentId, List<node>>},
     *   then walks from the root recursively to build the nested structure.
     * - Records the timer with {@code depth_reached} = the largest depth observed.
     *   Trees that hit the cap surface as {@code depth_reached = <maxDepth>}; callers
     *   can reason about "are we capping?" from Prometheus.
     */
    public Optional<RunCostNode> getCostTree(String rootRunId, int maxDepth) {
        long startNanos = System.nanoTime();
        List<Object[]> rows = runRepository.findRunCostTree(rootRunId, maxDepth);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<Row>> childrenByParent = new HashMap<>();
        Row rootRow = null;
        int deepest = 0;
        for (Object[] raw : rows) {
            Row r = new Row(
                    (String) raw[0],
                    (String) raw[1],
                    (String) raw[2],
                    ((Number) raw[3]).intValue(),
                    raw[4] == null ? null : ((Number) raw[4]).longValue(),
                    raw[5] == null ? null : ((Number) raw[5]).longValue(),
                    (BigDecimal) raw[6]);
            if (r.depth == 0) {
                rootRow = r;
            }
            if (r.parentId != null) {
                childrenByParent.computeIfAbsent(r.parentId, k -> new ArrayList<>()).add(r);
            }
            if (r.depth > deepest) {
                deepest = r.depth;
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        Timer.builder("agm.observability.run_cost.tree")
                .tag("depth_reached", Integer.toString(deepest))
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);

        if (rootRow == null) {
            // Defensive: CTE returned rows but none at depth=0 — should not happen, but
            // don't NPE the caller.
            return Optional.empty();
        }
        return Optional.of(toNode(rootRow, childrenByParent));
    }

    private RunCostNode toNode(Row row, Map<String, List<Row>> childrenByParent) {
        List<Row> kids = childrenByParent.getOrDefault(row.id, Collections.emptyList());
        List<RunCostNode> subRuns = new ArrayList<>(kids.size());
        for (Row k : kids) {
            subRuns.add(toNode(k, childrenByParent));
        }
        return new RunCostNode(row.id, row.agentId, row.depth,
                row.inputTokens, row.outputTokens, row.totalCostUsd, subRuns);
    }

    private record Row(String id, String parentId, String agentId, int depth,
                       Long inputTokens, Long outputTokens, BigDecimal totalCostUsd) {}
}
