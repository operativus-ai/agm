package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Defines the standardized set of event discriminators for real-time agent streaming communications.
 * State: Stateless (Enum)
 */
public enum EventType {
    START,                // Stream initialized
    REASONING_DELTA,      // "Thinking..." text
    CONTENT_DELTA,        // Final answer text
    CONTENT_DONE,         // Emitted immediately after all LLM answer tokens, before follow-ups are generated
    TOOL_START,           // "Calling tool: ..."
    TOOL_END,             // "Tool finished: ..."
    TOOL_ERROR,           // "Tool failed: ..."
    PAUSED,               // Human validation needed
    AGENT_SWITCH,         // Handoff to another agent
    METRICS,              // Telemetry data
    FOLLOWUP_SUGGESTION,  // Predictive context queries
    STOP,                 // Stream complete
    CANCELLED,            // Stream truncated by user cancellation (DELETE /runs/{id})
    ERROR
}
