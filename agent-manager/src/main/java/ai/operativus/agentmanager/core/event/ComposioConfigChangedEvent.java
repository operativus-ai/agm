package ai.operativus.agentmanager.core.event;

/**
 * Domain Responsibility: Signals that the DB-backed Composio runtime configuration
 *   ({@code composio_action_config} or {@code composio_connection_config}) has been
 *   mutated by an admin operation and downstream caches should reload.
 *   {@code ComposioActionRegistry} listens via {@code @EventListener} and atomically
 *   swaps its enabled-actions set without restarting the application.
 *
 * <p><strong>Publisher contract:</strong> emitted by {@code ComposioConfigService}
 *   after every successful action-config mutation (create/update/delete). Connection-
 *   config mutations do NOT publish — {@code ComposioToolCallback} reads connections
 *   per-call and there's nothing to cache.
 *
 * State: Stateless value type (record).
 *
 * @param reason short human-readable cause string for log correlation, e.g.
 *               {@code "action_create"}, {@code "action_delete"}. May be empty.
 */
public record ComposioConfigChangedEvent(String reason) {
}
