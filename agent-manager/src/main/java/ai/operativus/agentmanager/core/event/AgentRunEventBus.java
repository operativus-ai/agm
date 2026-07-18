package ai.operativus.agentmanager.core.event;

import ai.operativus.agentmanager.control.repository.AgentRunEventRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Single fan-out seam for {@link AgentRunEvent} emitted by orchestrators,
 *     advisors, and {@code AgentService}. Publishes to three destinations:
 *     (1) structured SLF4J log, (2) Spring {@link ApplicationEventPublisher} for in-process
 *     listeners (SSE, metrics), (3) insert-only {@code agent_run_events} audit table via a
 *     virtual-thread executor. Each destination is isolated: a failure in one never blocks
 *     or breaks the other two (Risk R-18). DB inserts use a bounded retry (Risk R-10).
 * State: Stateful (owns a virtual-thread executor for async persistence).
 */
@Component
public class AgentRunEventBus implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRunEventBus.class);
    private static final int DB_INSERT_MAX_ATTEMPTS = 3;
    private static final long DB_INSERT_BACKOFF_MS = 50L;
    /** Per-value cap for the rendered log line — keeps a stray large payload value from
     *  blowing up a single log record. The original length is appended when truncated. */
    private static final int MAX_PAYLOAD_VALUE_LEN = 200;

    private final AgentRunEventRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final ExecutorService persistenceExecutor;

    /**
     * Global default for the high-volume <b>granular</b> event tier when no per-run decision is
     * bound on {@link AgentContextHolder#emitGranularEvents} (i.e. events emitted outside a run
     * scope — streaming, workflow, system). Set to {@code false} to cut event-streaming overhead
     * (no insert, no NOTIFY, no SSE replay/fan-out) for granular events platform-wide. The
     * lifecycle tier is always emitted regardless of this flag.
     */
    private final boolean granularStreamingDefault;

    public AgentRunEventBus(AgentRunEventRepository repository, ApplicationEventPublisher eventPublisher,
                            @Value("${agm.events.granular-streaming.enabled:true}") boolean granularStreamingDefault) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.granularStreamingDefault = granularStreamingDefault;
        this.persistenceExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * @summary Fan-out entry point. Every call emits to all three destinations; destination
     *     failures are caught and logged, never propagated. Safe to call from hot paths
     *     (advisors, tool callbacks) and from forked virtual threads.
     */
    public void publish(AgentRunEvent event) {
        if (event == null) return;

        // Tiered gate (single producer choke point). Lifecycle/terminal events always emit;
        // the high-volume granular tier is suppressed when streaming is disabled for this run.
        // Short-circuiting here means zero cost downstream: no log, no in-process publish, no DB
        // insert + NOTIFY, hence no SSE replay/fan-out for the suppressed events.
        if (!event.eventType().isAlwaysEmitted() && !granularEventsEnabled()) {
            return;
        }

        try {
            logEvent(event);
        } catch (RuntimeException ex) {
            log.warn("AgentRunEventBus: log destination failed for event={} runId={}", event.eventType(), event.runId(), ex);
        }

        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException ex) {
            log.warn("AgentRunEventBus: in-process publisher failed for event={} runId={}", event.eventType(), event.runId(), ex);
        }

        try {
            persistenceExecutor.submit(ContextSnapshotFactory.builder().build().captureAll().wrap(() -> persistWithRetry(event)));
        } catch (RuntimeException ex) {
            log.warn("AgentRunEventBus: persistence submission failed for event={} runId={}", event.eventType(), event.runId(), ex);
        }
    }

    /**
     * @summary Resolves the granular-event decision for the emitting thread: the per-run flag bound
     *     on {@link AgentContextHolder#emitGranularEvents} when present (resolved once at run start),
     *     otherwise the configured global default for out-of-run-scope emissions.
     */
    private boolean granularEventsEnabled() {
        Boolean perRun = AgentContextHolder.getEmitGranularEvents();
        return perRun != null ? perRun : granularStreamingDefault;
    }

    private void logEvent(AgentRunEvent event) {
        log.info("agent_run_event type={} runId={} agentId={} parentRunId={} sessionId={} orgId={} depth={} | {}",
                event.eventType(),
                event.runId(),
                event.agentId(),
                event.parentRunId(),
                event.sessionId(),
                event.orgId(),
                event.orchestrationDepth(),
                formatPayload(event.payload()));
    }

    /**
     * @summary Renders the event payload as a readable {@code key=value · key=value} string for the
     *     structured log line, replacing the prior {@code payloadKeys=[...]} (keys only). The keys
     *     alone hid the data needed to follow a prompt's execution — model used, tool name, latency,
     *     token counts, delegation source/target, status — all of which live in the values.
     * @logic Keys are sorted for stable, greppable output. Each value is truncated to
     *     {@value #MAX_PAYLOAD_VALUE_LEN} chars (original length appended) so a stray large value
     *     can't blow up a log line. Payloads are PII-safe by construction: emit sites store
     *     lengths/metadata ({@code taskLength}, {@code promptLength}, {@code contentLength}), never raw
     *     prompt/response content. Appender-level Bearer/SSE-token masking still applies as defence
     *     in depth.
     */
    private static String formatPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(payload).entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(entry.getKey()).append('=').append(truncate(entry.getValue()));
        }
        return sb.toString();
    }

    private static String truncate(Object value) {
        if (value == null) return "null";
        String s = String.valueOf(value);
        if (s.length() <= MAX_PAYLOAD_VALUE_LEN) return s;
        return s.substring(0, MAX_PAYLOAD_VALUE_LEN) + "…(" + s.length() + " chars)";
    }

    private void persistWithRetry(AgentRunEvent event) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= DB_INSERT_MAX_ATTEMPTS; attempt++) {
            try {
                repository.save(toEntity(event));
                return;
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt < DB_INSERT_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(DB_INSERT_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("AgentRunEventBus: persistence retry interrupted runId={}", event.runId());
                        return;
                    }
                }
            }
        }
        log.error("AgentRunEventBus: persistence failed after {} attempts for event={} runId={}",
                DB_INSERT_MAX_ATTEMPTS, event.eventType(), event.runId(), lastError);
    }

    private AgentRunEventEntity toEntity(AgentRunEvent event) {
        AgentRunEventEntity entity = new AgentRunEventEntity();
        entity.setEventType(event.eventType());
        entity.setRunId(event.runId());
        entity.setAgentId(event.agentId());
        entity.setParentRunId(event.parentRunId());
        entity.setSessionId(event.sessionId());
        entity.setOrgId(event.orgId());
        entity.setOrchestrationDepth(event.orchestrationDepth());
        entity.setPayload(event.payload());
        entity.setEventTs(event.eventTs());
        return entity;
    }

    @Override
    public void close() {
        persistenceExecutor.shutdown();
    }
}
