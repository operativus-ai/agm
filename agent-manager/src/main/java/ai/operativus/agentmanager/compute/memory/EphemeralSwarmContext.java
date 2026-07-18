package ai.operativus.agentmanager.compute.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Provides a transient, in-memory key-value store scoped to a specific workflow run,
 * enabling sub-agents within a Swarm or Delegation chain to share ephemeral facts discovered during execution.
 * Memory is automatically evicted after a configurable TTL (default: 1 hour).
 * State: Stateful (In-Memory Concurrent Store)
 */
@Service
public class EphemeralSwarmContext {

    private static final Logger log = LoggerFactory.getLogger(EphemeralSwarmContext.class);

    /**
     * Eviction TTL in milliseconds. Externalised so tests can dial it down to sub-second
     * values and ops can tune it per-environment without a redeploy. Default: 1 hour.
     */
    @Value("${agentmanager.swarm.context-ttl-ms:3600000}")
    private long ttlMs;

    private record ContextEntry(Map<String, Object> data, Instant createdAt) {}

    private final ConcurrentHashMap<String, ContextEntry> contextStore = new ConcurrentHashMap<>();

    /**
     * @summary Stores a key-value fact into the ephemeral context for a specific workflow run.
     * @logic Lazily initializes the context map for the workflow and upserts the fact.
     */
    public void put(String workflowRunId, String key, Object value) {
        ContextEntry entry = contextStore.computeIfAbsent(workflowRunId,
                id -> new ContextEntry(new ConcurrentHashMap<>(), Instant.now()));
        entry.data().put(key, value);
        log.debug("EphemeralSwarmContext: Stored key '{}' for workflow '{}'", key, workflowRunId);
    }

    /**
     * @summary Retrieves a specific fact from the ephemeral context of a workflow run.
     * @logic Returns null if the workflow context or key does not exist.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String workflowRunId, String key, Class<T> type) {
        ContextEntry entry = contextStore.get(workflowRunId);
        if (entry == null) return null;
        Object value = entry.data().get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * @summary Retrieves the entire ephemeral context snapshot for a workflow run.
     * @logic Returns an unmodifiable view; returns an empty map if no context exists.
     */
    public Map<String, Object> getAll(String workflowRunId) {
        ContextEntry entry = contextStore.get(workflowRunId);
        if (entry == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(entry.data());
    }

    /**
     * @summary Merges all facts from a source workflow context into a target workflow context.
     * @logic Used during delegation to propagate parent execution facts to child agents.
     */
    public void mergeFrom(String sourceWorkflowRunId, String targetWorkflowRunId) {
        ContextEntry source = contextStore.get(sourceWorkflowRunId);
        if (source == null || source.data().isEmpty()) return;

        ContextEntry target = contextStore.computeIfAbsent(targetWorkflowRunId,
                id -> new ContextEntry(new ConcurrentHashMap<>(), Instant.now()));
        target.data().putAll(source.data());
        log.debug("EphemeralSwarmContext: Merged {} facts from '{}' → '{}'",
                source.data().size(), sourceWorkflowRunId, targetWorkflowRunId);
    }

    /**
     * @summary Flushes the ephemeral context for a completed workflow.
     * @logic Removes the context entry entirely from the in-memory store.
     */
    public void flush(String workflowRunId) {
        ContextEntry removed = contextStore.remove(workflowRunId);
        if (removed != null) {
            log.debug("EphemeralSwarmContext: Flushed context for workflow '{}' ({} facts)",
                    workflowRunId, removed.data().size());
        }
    }

    /**
     * @summary Scheduled TTL-based eviction of stale workflow contexts.
     * @logic Runs every 15 minutes; removes any context entries older than DEFAULT_TTL_MS.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.swarm-cleanup-ms:900000}")
    public void evictStaleContexts() {
        Instant cutoff = Instant.now().minusMillis(ttlMs);
        int evicted = 0;
        var iterator = contextStore.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                iterator.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("EphemeralSwarmContext: Evicted {} stale workflow contexts (TTL={}ms)", evicted, ttlMs);
        }
    }

    /**
     * @summary Returns the current number of active workflow contexts.
     */
    public int size() {
        return contextStore.size();
    }
}
