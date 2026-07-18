package com.operativus.agentmanager.core.model;


/**
 * Domain Responsibility: Acts as an immutable data transfer object to record a single tool invocation within an agent's execution trace, fulfilling Requirement 3.1.2.
 * State: Stateless (Immutable Record carrier)
 */
public record ToolCallDTO(
    String toolName,
    String toolInput,   // JSON String
    String toolOutput,  // JSON String or Result
    Long durationMs,
    boolean isError
) {}
