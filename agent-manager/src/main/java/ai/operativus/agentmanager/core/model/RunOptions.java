package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.control.finops.model.FinOpsRecords.A2aFinOpsBoundary;

/**
 * Domain Responsibility: Encapsulates optional per-run parameter overrides submitted by the user
 * at chat-time. Values are nullable — when absent, the system falls back to the Agent's configured defaults
 * from the AgentDefinition and its associated Model entity.
 *
 * The {@code finOpsBoundary} field carries A2A FinOps ceiling constraints through the execution
 * pipeline as structured data — never concatenated into the LLM prompt. The
 * {@code GenAiMetricsAdvisor} reads this boundary to enforce token ceilings mid-flight.
 *
 * State: Stateless (Immutable Record carrier)
 */
public record RunOptions(
    Double temperature,
    String model,
    String systemPrompt,
    Integer maxTokens,
    A2aFinOpsBoundary finOpsBoundary
) {
    /** Convenience constructor for non-A2A runs (no FinOps boundary). */
    public RunOptions(Double temperature, String model, String systemPrompt, Integer maxTokens) {
        this(temperature, model, systemPrompt, maxTokens, null);
    }
}
