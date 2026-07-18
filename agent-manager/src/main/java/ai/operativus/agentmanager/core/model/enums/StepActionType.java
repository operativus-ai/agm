package ai.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: Step-action discriminator for workflow steps. Stored as a string
 *     in {@code workflow_steps.action} and parsed back via {@link #fromString} at dispatch
 *     time in {@code WorkflowService}.
 *
 *     <ul>
 *       <li>{@link #AGENT} — executes an agent / team referenced by {@code agentId}.
 *           Production-default path for the dispatcher's {@code default} branch.</li>
 *       <li>{@link #CONDITION} — evaluates an expression and skips the next step when
 *           the condition is false (see {@code WorkflowService.evaluateCondition}).</li>
 *       <li>{@link #PARALLEL} — fan-out group; consecutive steps with the same
 *           {@code stepOrder} execute in parallel.</li>
 *       <li>{@link #WEBHOOK} — delegated to a {@code WorkflowStepExecutorExtension} SPI.</li>
 *       <li>{@link #LOOP} — repeats the next step until {@code max:N|until:condition}
 *           bounds are met.</li>
 *       <li>{@link #SEQUENTIAL} — explicit marker for linear execution. Functionally
 *           equivalent to {@link #AGENT} when paired with an {@code agentId}; the explicit
 *           value documents intent in serialized DAGs (REQ-DR-5).</li>
 *       <li>{@link #FUNCTION} — DAG-only (REQ-DR-5, DAG-6): dispatches to a registered
 *           {@code WorkflowFunctionStep} bean keyed by the step's {@code agent_id} value.
 *           On the flat-list path it falls through to AGENT behavior like JOIN.</li>
 *       <li>{@link #WORKFLOW} — DAG-only (REQ-DR-5, DAG-6): nested sub-workflow whose id
 *           lives in the step's {@code agent_id} value, depth-guarded. Flat path falls
 *           through to AGENT behavior like JOIN/FUNCTION.</li>
 *       <li>{@link #ROUTER} — placeholder for REQ-DR-4 (Workflow Router step). The real
 *           selector-based dispatch lands in a follow-up PR; before then, the
 *           {@code WorkflowService} dispatcher will fall through to AGENT behavior on
 *           ROUTER, which is documented but not the final semantics.</li>
 *     </ul>
 * State: Stateless (Enum)
 */
public enum StepActionType {
    AGENT, CONDITION, PARALLEL, WEBHOOK, LOOP, SEQUENTIAL, ROUTER, JOIN, FUNCTION, WORKFLOW;

    /** Returns the enum constant for the given string, case-insensitive; falls back to {@link #AGENT}. */
    public static StepActionType fromString(String value) {
        if (value == null) return AGENT;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}
