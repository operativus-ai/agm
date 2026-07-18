package ai.operativus.agentmanager.core.model.workflow;

/**
 * Domain Responsibility: Centralizes the pause-kind labels a {@link StepOutput#pauseKind()} can
 *     carry when a DAG node bubbles up a HITL pause (REQ-DR-5, DAG-3c). Replaces the literal
 *     strings that were duplicated across the node executors, the resume status mapping, and the
 *     frontier snapshot — so the pause flavour is referenced from one place, never re-typed.
 * State: Stateless (constants holder).
 */
public final class PauseKind {

    private PauseKind() {}

    /** An AGENT node whose wrapped agent run paused on a HITL gate (the agent itself, not a tool). */
    public static final String AGENT = "agent";

    /** An AGENT node that paused awaiting a tool-call approval. */
    public static final String TOOL = "tool";

    /** A node paused awaiting a unified HumanReview decision (REQ-HR-3). */
    public static final String REVIEW = "review";

    /** A ROUTER node paused awaiting a HITL route (choice-key) selection (REQ-DR-4). */
    public static final String ROUTE = "route";
}
