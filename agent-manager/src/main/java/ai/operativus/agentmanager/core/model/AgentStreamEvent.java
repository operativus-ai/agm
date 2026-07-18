package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Represents a standard streaming event (SSE chunk) payload for real-time agent responses, fulfilling Requirement 3.1.2 B.
 * State: Stateless (Immutable Record carrier)
 */
public record AgentStreamEvent(
    EventType event,      // Enum discriminator
    String data,          // Text delta or JSON payload
    Long timestamp
) {}
