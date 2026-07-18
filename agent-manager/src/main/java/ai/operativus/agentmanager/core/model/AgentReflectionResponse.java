package ai.operativus.agentmanager.core.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain Responsibility: Typed carrier for a single agent-reflection row served by
 * {@code GET /api/v1/agents/{agentId}/reflections}. Flattens the
 * {@code AgentReflectionEntity} (observability plan Phase 1 T002) to the five fields
 * the UI needs: identity, agent, reflection text, source run, and timestamp.
 * State: Immutable value carrier.
 */
public record AgentReflectionResponse(
        UUID id,
        String agentId,
        String content,
        String sourceRunId,
        LocalDateTime createdAt) {
}
