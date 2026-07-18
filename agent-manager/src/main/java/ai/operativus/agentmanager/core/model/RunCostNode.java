package ai.operativus.agentmanager.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Domain Responsibility: Tree node for the depth-limited run-cost view served by
 * {@code GET /api/v1/runs/{runId}/cost-tree?maxDepth=} (observability plan Phase 1
 * T006.2). Each node carries its own cost metrics plus the nested subtree underneath
 * it; leaf nodes (beyond {@code maxDepth} or with no children) carry {@code []}.
 * State: Immutable value carrier.
 */
public record RunCostNode(
        String id,
        String agentId,
        Integer depth,
        Long inputTokens,
        Long outputTokens,
        BigDecimal totalCostUsd,
        List<RunCostNode> subRuns) {
}
