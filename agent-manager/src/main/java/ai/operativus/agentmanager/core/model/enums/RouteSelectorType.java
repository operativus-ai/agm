package ai.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: Discriminator for ROUTER step selector strategy
 *     (REQ-DR-4). Stored as a string under {@code router_config.selectorType}
 *     in {@code workflow_steps.router_config} and dispatched at workflow run
 *     time by the {@code WorkflowService} ROUTER case.
 *
 *     <ul>
 *       <li>{@link #RULE} — JSONPath expression evaluated against the prior
 *           step's output; the matched leaf value is the choice key.</li>
 *       <li>{@link #LLM} — classification prompt; the model returns one of
 *           the choice keys directly.</li>
 *       <li>{@link #HITL} — suspends the run with
 *           {@code RunStatus.AWAITING_ROUTE_SELECTION}; resumed by
 *           {@code POST /api/v1/workflows/runs/{runId}/continue}.</li>
 *     </ul>
 * State: Stateless (Enum)
 */
public enum RouteSelectorType {
    RULE, LLM, HITL;

    /** Returns the enum constant for the given string, case-insensitive; null in / unknown in returns null. */
    public static RouteSelectorType fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
