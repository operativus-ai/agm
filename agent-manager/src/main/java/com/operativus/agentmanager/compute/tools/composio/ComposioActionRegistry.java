package com.operativus.agentmanager.compute.tools.composio;

import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import com.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: Holds the canonical list of Composio actions enabled at startup,
 * applying the operator's cap-and-warn policy (audit Finding 11): hard cap at
 * `agent.tools.composio.max-actions` (default 50), soft warn threshold at
 * `agent.tools.composio.warn-actions-threshold` (default 25). Truncates over-cap rather than
 * throwing, so a misconfigured deployment does not crash startup.
 *
 * <p><strong>Source-of-truth precedence (Path B / PR-A):</strong> when the
 *   {@code composio_action_config} table has at least one enabled row, the DB is
 *   authoritative and properties-file values are ignored. When the DB is empty, the
 *   boot-time {@code @Value}-bound property list is used as the fallback. This is a hard
 *   switch, not a merge — first DB row flips the registry from properties-fallback to
 *   DB-authoritative on the next reload.
 *
 * <p><strong>Hot reload:</strong> {@code @EventListener(ComposioConfigChangedEvent.class)}
 *   re-reads the DB and atomically swaps the internal state via
 *   {@link AtomicReference}. Concurrent virtual-thread callers see either the old or the
 *   new {@code Set}, never a torn read.
 *
 * State: Stateful (single {@code AtomicReference} guards the per-state snapshot). Action
 *   names normalized to UPPERCASE.
 */
@Component
public class ComposioActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ComposioActionRegistry.class);

    /**
     * Canonical wording emitted at ERROR when the source list exceeds {@code maxActions}.
     * Pinned by tests so a refactor of the message text doesn't silently break log-based
     * alerting. The format string carries 4 SLF4J parameters: count, source, max, max.
     */
    static final String TRUNCATION_LOG_MESSAGE_FORMAT =
            "Composio enabled-actions count ({}) from {} exceeds hard cap ({}). "
            + "Truncating to first {} actions; review configuration.";

    /**
     * Canonical wording emitted at WARN when count is above {@code warnThreshold} but
     * below {@code maxActions}. 4 SLF4J parameters: count, source, warnThreshold, maxActions.
     */
    static final String WARN_THRESHOLD_LOG_MESSAGE_FORMAT =
            "Composio enabled-actions count ({}) from {} exceeds warn threshold ({}). "
            + "Hard cap is {}. Each enabled action adds tool descriptions to the LLM context window.";

    /**
     * Canonical wording for the OK path (count ≤ warnThreshold). 2 SLF4J parameters:
     * count, source.
     */
    static final String LOADED_LOG_MESSAGE_FORMAT =
            "Composio action registry loaded {} enabled action(s) from {}";

    private final ComposioActionConfigRepository repository;
    private final List<String> propertyFallbackActions;
    private final int maxActions;
    private final int warnThreshold;
    private final Counter truncationCounterDb;
    private final Counter truncationCounterProperties;

    private final AtomicReference<RegistryState> state = new AtomicReference<>();

    /**
     * Synchronous downstream subscribers invoked AFTER {@link #state} has been swapped to the
     * new snapshot. The async {@code applicationEventMulticaster} (see
     * {@code AgentManagerConfig}) dispatches each {@code @EventListener} on its own virtual
     * thread, so two listeners on the same event race; if a downstream listener (e.g.
     * {@code ComposioToolCallbackProvider}) reads from this registry, it can observe the old
     * snapshot. Subscribers registered here run in the registry's own listener vthread AFTER
     * the state swap, so they always see the fresh snapshot.
     */
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    public ComposioActionRegistry(
            ComposioActionConfigRepository repository,
            @Value("${agent.tools.composio.enabled-actions:}") List<String> configured,
            @Value("${agent.tools.composio.max-actions:50}") int maxActions,
            @Value("${agent.tools.composio.warn-actions-threshold:25}") int warnThreshold,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.propertyFallbackActions = configured == null ? List.of() : List.copyOf(configured);
        this.maxActions = maxActions;
        this.warnThreshold = warnThreshold;
        // Pre-register both source-tag variants so the Counter handles are cheap on the hot
        // path (truncation). Counter increment value carries the magnitude; the tag carries
        // the precedence side that produced it.
        this.truncationCounterDb = Counter.builder("agm.composio.actions.truncated")
                .description("Count of Composio actions dropped because the source list exceeded the hard cap.")
                .tag("source", "db")
                .register(meterRegistry);
        this.truncationCounterProperties = Counter.builder("agm.composio.actions.truncated")
                .description("Count of Composio actions dropped because the source list exceeded the hard cap.")
                .tag("source", "properties")
                .register(meterRegistry);
        this.state.set(buildState());
    }

    /**
     * Re-reads the DB-backed config (or falls back to properties when the table is empty)
     * and atomically swaps the internal snapshot. Invoked on
     * {@link ComposioConfigChangedEvent} publication and once at construction.
     */
    @EventListener
    public void onComposioConfigChanged(ComposioConfigChangedEvent event) {
        log.info("Reloading Composio action registry due to event: {}", event.reason());
        state.set(buildState());
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                // One misbehaving subscriber must not prevent the rest from being notified —
                // mirrors the async multicaster's LOG_AND_SUPPRESS_ERROR_HANDLER contract.
                log.warn("Composio reload listener threw — continuing with remaining listeners", ex);
            }
        }
    }

    /**
     * Registers a callback to be invoked synchronously after every successful state swap.
     * Use this instead of a separate {@code @EventListener(ComposioConfigChangedEvent.class)}
     * when the downstream component reads from this registry — a separate listener races with
     * the registry's own listener on the async multicaster and can observe stale state.
     *
     * <p>Subscribers are run in registration order on the registry's listener thread; any
     * thrown {@link RuntimeException} is logged and suppressed so siblings continue.
     */
    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    private RegistryState buildState() {
        List<String> source;
        String sourceLabel;
        try {
            List<ComposioActionConfig> dbRows = repository.findByEnabledTrueOrderByActionName();
            if (!dbRows.isEmpty()) {
                source = dbRows.stream().map(ComposioActionConfig::getActionName).toList();
                sourceLabel = "db";
            } else {
                source = propertyFallbackActions;
                sourceLabel = "properties-fallback";
            }
        } catch (RuntimeException ex) {
            log.warn("Composio DB-config read failed ({}); falling back to properties", ex.toString());
            source = propertyFallbackActions;
            sourceLabel = "properties-fallback-after-db-error";
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : source) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toUpperCase());
            }
        }

        boolean overCap;
        Set<String> finalSet;
        if (normalized.size() > maxActions) {
            log.error(TRUNCATION_LOG_MESSAGE_FORMAT,
                    normalized.size(), sourceLabel, maxActions, maxActions);
            int truncatedCount = normalized.size() - maxActions;
            counterFor(sourceLabel).increment(truncatedCount);
            finalSet = Collections.unmodifiableSet(
                    new LinkedHashSet<>(new ArrayList<>(normalized).subList(0, maxActions)));
            overCap = true;
        } else if (normalized.size() > warnThreshold) {
            log.warn(WARN_THRESHOLD_LOG_MESSAGE_FORMAT,
                    normalized.size(), sourceLabel, warnThreshold, maxActions);
            finalSet = Collections.unmodifiableSet(normalized);
            overCap = false;
        } else {
            log.info(LOADED_LOG_MESSAGE_FORMAT, normalized.size(), sourceLabel);
            finalSet = Collections.unmodifiableSet(normalized);
            overCap = false;
        }
        return new RegistryState(finalSet, overCap);
    }

    /**
     * Routes truncation magnitude to the {@code db}-tagged or {@code properties}-tagged
     * counter. Any source label other than {@code "db"} (i.e. properties-fallback or the
     * post-DB-error fallback) maps to the {@code properties} tag — operators reading the
     * metric only need to know whether the offending list came from the DB or from config.
     */
    private Counter counterFor(String sourceLabel) {
        return "db".equals(sourceLabel) ? truncationCounterDb : truncationCounterProperties;
    }

    public Set<String> getEnabledActions() {
        return state.get().enabledActions();
    }

    public boolean isEnabled(String actionName) {
        return actionName != null && state.get().enabledActions().contains(actionName.toUpperCase());
    }

    public int getEnabledCount() {
        return state.get().enabledActions().size();
    }

    public boolean wasTruncated() {
        return state.get().overCap();
    }

    /**
     * Per-snapshot tuple. Held in a single {@link AtomicReference} so reload swaps both
     * fields atomically — readers can never observe an enabled-actions set that doesn't
     * match its overCap flag.
     */
    private record RegistryState(Set<String> enabledActions, boolean overCap) {
    }
}
