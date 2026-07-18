package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Domain Responsibility: Wire-format response for
 *     {@code GET /api/v1/observability/aggregates/delegation-topology} (observability plan T037a).
 *     Carries the per-strategy edge list plus the derived node summary so the
 *     Delegation Topology heatmap renders both the matrix cells and the row/column
 *     totals from a single fetch.
 * State: Immutable value carrier.
 */
public record DelegationTopologyResponse(List<Edge> edges, List<Node> nodes) {

    /**
     * One delegation edge: {@code from} delegated to {@code to} via {@code strategy}
     * {@code count} times within the window. {@code from} is {@code "(root)"}
     * for runs without an owning agent.
     */
    public record Edge(String from, String to, String strategy, long count) {
    }

    /**
     * Per-agent fan-in / fan-out totals across all strategies.
     * {@code totalIn} = number of delegations that selected this agent.
     * {@code totalOut} = number of delegations originated by this agent.
     */
    public record Node(String agentId, long totalIn, long totalOut) {
    }
}
