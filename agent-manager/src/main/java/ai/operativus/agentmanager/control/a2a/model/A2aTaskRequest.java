package ai.operativus.agentmanager.control.a2a.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound A2A task request payload deserialized from an external
 * peer agent calling AGM's task execution endpoint.
 *
 * Gap 2.2 Implementation: Defines the wire format for inter-platform task dispatch.
 * The {@code finOpsBoundary} field carries the initiating peer's token ceiling so that
 * AGM's {@code GenAiMetricsAdvisor} can enforce it mid-flight, triggering a
 * {@link A2aTaskStatus#BUDGET_HALT} if the ceiling is breached.
 *
 * The {@code traceId} field is the OpenTelemetry Trace ID propagated from the calling
 * system (Gap 2.3) so all spans across the A2A boundary are correlated in the trace backend.
 *
 * @param taskId             Caller-generated idempotency ID for this task (UUID).
 * @param targetAgentId      The AGM agent or team ID that should handle this task.
 * @param input              Natural-language or structured task payload.
 * @param initiatingAgentId  Remote peer agent that is delegating this task (optional).
 * @param sessionId          Caller's session context — used to bind memory and audit records.
 * @param traceId            OTel Trace ID for cross-boundary span correlation (optional).
 * @param finOpsBoundary     Optional FinOps ceiling constraints from the initiating agent.
 */
public record A2aTaskRequest(
    // taskId is intentionally nullable: the controller generates a UUID when omitted,
    // so @NotBlank here would reject callers that rely on that convenience.
    String taskId,

    @NotBlank(message = "targetAgentId is required")
    @Size(max = 255)
    String targetAgentId,

    @NotBlank(message = "input payload cannot be blank")
    String input,

    String initiatingAgentId,   // Optional — anonymous peers are permitted
    String sessionId,
    String traceId,
    ai.operativus.agentmanager.control.finops.model.FinOpsRecords.A2aFinOpsBoundary finOpsBoundary
) {}
