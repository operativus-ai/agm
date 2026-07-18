package com.operativus.agentmanager.core.model;

import java.math.BigDecimal;

/**
 * Domain Responsibility: Carries the output of the {@code vw_run_tree_cost} recursive
 * CTE view (logging plan §5.22) — the USD cost rollup aggregated across every run in a
 * parent_run_id tree along with the run count in that subtree.
 * State: Immutable value carrier.
 */
public record RunTreeCostDTO(String rootRunId, BigDecimal treeTotalCostUsd, long runCount) {
}
