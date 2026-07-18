package ai.operativus.agentmanager.core.entity;

/**
 * Domain Responsibility: Lifecycle states for a {@link TaskEntity} in the
 *     TeamMode.tasks orchestrator. Transition rules are enforced by the
 *     {@code TasksOrchestrator} worker loop, not the DB — the CHECK constraint
 *     on {@code tasks.status} only validates the value-set.
 *
 *     <p>Valid transitions:
 *     <ul>
 *       <li>{@link #PENDING} → {@link #IN_PROGRESS} via atomic status CAS at dispatch time</li>
 *       <li>{@link #PENDING} → {@link #BLOCKED} when a dependency transitions to {@link #FAILED}</li>
 *       <li>{@link #IN_PROGRESS} → {@link #COMPLETED} | {@link #FAILED} on assignee terminal state</li>
 *     </ul>
 *     {@link #COMPLETED}, {@link #FAILED}, {@link #BLOCKED} are terminal — no transitions out.
 *
 * State: Stateless (enum).
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED
}
