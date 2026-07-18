package ai.operativus.agentmanager.control.a2a.model;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Immutable capability profile record representing an agent's advertised
 * skills, constraints, and identity claims exchanged over A2A protocol boundaries.
 *
 * Gap 2.1 Implementation: AGM previously had no external discovery mechanism. An {@code AgentCard}
 * is the standard A2A interoperability artifact: it describes what a remote or local agent can do
 * so that a {@code PlannerOrchestrator} can discover and delegate to it without prior hardcoding.
 *
 * Published via {@code GET /api/v1/a2a/cards/{agentId}} and consumed by
 * {@code A2ACardResolver} when resolving external peer agents.
 *
 * Design:
 * - Immutable record (Java 21 value object). No setters.
 * - {@code capabilities} uses open strings to stay protocol-agnostic.
 * - {@code dataZone} seeds the 2027 sovereignty constraint (Gap 3.3) without wiring full logic yet.
 * - {@code endpointUrl} is null for local agents (no outbound HTTP needed).
 *
 * @param agentId          Stable identifier of the agent (UUID or slug).
 * @param name             Human-readable display name.
 * @param description      Brief description of the agent's function.
 * @param capabilities     List of capability tokens (e.g., "code-review", "sql-analysis").
 * @param securityTier     Numeric tier (1–3) matching AGM's {@code ComplianceTier} model.
 * @param dataZone         Optional data sovereignty zone tag (e.g., "EU-Central-1-Private").
 * @param endpointUrl      Base URL for remote agents; null for local agents.
 * @param modelId          Primary model identifier the agent uses for generation.
 * @param maxTokenBudget   Optional maximum token budget the agent is authorized to consume.
 * @param publishedAt      Timestamp when this card was last generated/updated.
 */
public record AgentCard(
    String agentId,
    String name,
    String description,
    List<String> capabilities,
    int securityTier,
    String dataZone,
    String endpointUrl,
    String modelId,
    Long maxTokenBudget,
    Instant publishedAt
) {
    /** Convenience factory for a local agent card with no external endpoint. */
    public static AgentCard local(
            String agentId, String name, String description,
            List<String> capabilities, int securityTier, String modelId, Long maxTokenBudget) {
        return new AgentCard(
            agentId, name, description, capabilities,
            securityTier, null, null, modelId, maxTokenBudget, Instant.now()
        );
    }
}
