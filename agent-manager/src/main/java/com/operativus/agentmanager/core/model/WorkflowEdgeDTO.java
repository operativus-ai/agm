package com.operativus.agentmanager.core.model;

/**
 * Wire shape for a workflow DAG edge (REQ-DR-5). {@code condition} is the port label:
 * {@code null} = unconditional, {@code "true"}/{@code "false"} = CONDITION branches,
 * a choice key = ROUTER branch.
 */
public record WorkflowEdgeDTO(String id, String fromStepId, String toStepId, String condition) {}
