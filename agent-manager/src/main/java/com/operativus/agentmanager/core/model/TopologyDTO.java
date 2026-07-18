package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Domain Responsibility: Acts as an immutable data transfer object describing a graph structure (nodes, structural edges, and DAG transition constraints) for visualizing multi-agent topology or workflows.
 * State: Stateless (Immutable Record carrier)
 */
public record TopologyDTO(
    List<Node> nodes,
    List<Edge> edges,
    List<TransitionConstraint> transitionEdges
) {
    public record Node(String id, String label, String type) {}
    public record Edge(String id, String source, String target) {}
    public record TransitionConstraint(String id, String sourceAgentId, String targetAgentId) {}
}
