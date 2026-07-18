package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.AgentRunEventRepository;
import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/**
 * Domain Responsibility: Streams a single agent run's timeline to a connected client over SSE,
 *     combining historical replay (events with {@code id > sinceId}) and live follow-up of
 *     new rows appended to {@code agent_run_events} by {@link ai.operativus.agentmanager.core.event.AgentRunEventBus}.
 *     Backs {@code RunEventSseController} per Logging Plan §5.17.
 * State: Stateless service; each {@link #stream} invocation owns a virtual thread and
 *     {@link SseEmitter} for one connection. No shared mutable state between invocations.
 *
 * <p><b>Cursor contract (R-21):</b> pagination is on the monotonic {@code id BIGSERIAL},
 * never on {@code event_ts}, to avoid timestamp ties and clock-skew ambiguity. Clients
 * resume by passing the last SSE event id as {@code sinceId} on reconnect.
 *
 * <p><b>Tenant scoping (R-11):</b> if the caller provides an {@code orgId}, events whose
 * {@code orgId} differs are filtered out. A null {@code orgId} matches legacy pre-tenant rows.
 *
 * <p><b>Termination:</b> the stream closes after emitting a {@link AgentRunEventType#RUN_COMPLETE}
 * or {@link AgentRunEventType#RUN_FAILED} event for the subscribed run, after the emitter
 * timeout fires, or after the client disconnects. Repository failures are logged and retried
 * on the next poll tick; they do not tear down the stream.
 */
@Service
public class RunEventSseService {

    private static final Logger log = LoggerFactory.getLogger(RunEventSseService.class);

    private static final Set<AgentRunEventType> TERMINAL_EVENT_TYPES = Set.of(
            AgentRunEventType.RUN_COMPLETE,
            AgentRunEventType.RUN_FAILED,
            AgentRunEventType.RUN_CANCELLED
    );

    private final AgentRunEventRepository repository;
    /**
     * Event-driven wake-up source (Postgres LISTEN/NOTIFY). Nullable: when absent (unit tests, or a
     * deployment without the listener) the pump falls back to fixed-interval polling on
     * {@link #pollIntervalMs}, which is also the behavior when the listener is merely unhealthy.
     */
    private final AgentRunEventNotifier notifier;
    private final long pollIntervalMs;
    private final long emitterTimeoutMs;
    private final Counter connectionOpenCounter;
    private final Counter closeTerminalCounter;
    private final Counter closeTimeoutCounter;
    private final Counter closeErrorCounter;
    private final Counter closeClientCounter;

    public RunEventSseService(
            AgentRunEventRepository repository,
            AgentRunEventNotifier notifier,
            MeterRegistry meterRegistry,
            @Value("${agent.run.events.sse.poll-interval-ms:500}") long pollIntervalMs,
            @Value("${agent.run.events.sse.emitter-timeout-ms:1800000}") long emitterTimeoutMs) {
        this.repository = repository;
        this.notifier = notifier;
        this.pollIntervalMs = pollIntervalMs;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.connectionOpenCounter = Counter.builder("agm.sse.connection.open").register(meterRegistry);
        this.closeTerminalCounter = Counter.builder("agm.sse.connection.close")
                .tag("reason", "terminal").register(meterRegistry);
        this.closeTimeoutCounter = Counter.builder("agm.sse.connection.close")
                .tag("reason", "timeout").register(meterRegistry);
        this.closeErrorCounter = Counter.builder("agm.sse.connection.close")
                .tag("reason", "error").register(meterRegistry);
        this.closeClientCounter = Counter.builder("agm.sse.connection.close")
                .tag("reason", "client").register(meterRegistry);
    }

    /**
     * @summary Opens an SSE stream for {@code runId}, replaying events with {@code id > sinceId}
     *     and then polling for new rows until a terminal event, timeout, or disconnect.
     * @param runId   target run identifier; events with a different {@code run_id} are never sent.
     * @param orgId   optional tenant scope; if non-null, mismatching rows are skipped.
     * @param sinceId cursor — only rows with {@code id > sinceId} are streamed. Null is treated as 0.
     */
    public SseEmitter stream(String runId, String orgId, Long sinceId) {
        return open(
                "run-event-sse-" + runId,
                orgId,
                sinceId,
                lastId -> repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, lastId),
                true);
    }

    /**
     * @summary Opens a <b>long-lived</b> SSE stream of every event for {@code agentId} (across all
     *     of its runs), replaying events with {@code id > sinceId} then polling for new rows.
     *     Unlike {@link #stream}, it does <b>not</b> close on a single run's terminal event — an
     *     agent has many runs over time — so it tails until the emitter timeout or client disconnect.
     * @param agentId target agent identifier; the repository query is agent- and org-scoped.
     * @param sinceId cursor — only rows with {@code id > sinceId} are streamed. Null is treated as 0
     *     (full replay). A <b>negative</b> value is the "start from latest" sentinel: the stream
     *     resolves it to the agent's current max event id and tails only new events from there,
     *     avoiding a full replay of a large history. Clients resume after a drop with the last real
     *     (non-negative) id.
     * @param orgId   tenant scope (caller's org); strictly applied in the repository query so org B
     *     cannot tail org A's agent events. A null {@code orgId} matches only legacy null-org rows.
     */
    public SseEmitter streamByAgent(String agentId, Long sinceId, String orgId) {
        long effectiveSinceId;
        if (sinceId != null && sinceId < 0) {
            Long max = repository.findMaxIdByAgentIdAndOrgId(agentId, orgId);
            effectiveSinceId = max == null ? 0L : max;
        } else {
            effectiveSinceId = sinceId == null ? 0L : sinceId;
        }
        return open(
                "agent-event-sse-" + agentId,
                orgId,
                effectiveSinceId,
                lastId -> repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, orgId, lastId),
                false);
    }

    /**
     * @summary Opens a <b>long-lived, org-wide</b> SSE stream of every event for the caller's tenant
     *     (across all agents and runs), replaying events with {@code id > sinceId} then polling for
     *     new rows. Like {@link #streamByAgent} it never closes on a single run's terminal event.
     * @param sinceId cursor — {@code null}/0 replays full org history; a <b>negative</b> value is the
     *     "start from latest" sentinel (resolve to the org's current max id and tail). Clients resume
     *     after a drop with the last real (non-negative) id.
     * @param orgId   tenant scope (caller's org); strictly applied in the repository query so one
     *     tenant can never tail another's events. A null {@code orgId} matches only null-org rows.
     */
    public SseEmitter streamByOrg(Long sinceId, String orgId) {
        long effectiveSinceId;
        if (sinceId != null && sinceId < 0) {
            Long max = repository.findMaxIdByOrgId(orgId);
            effectiveSinceId = max == null ? 0L : max;
        } else {
            effectiveSinceId = sinceId == null ? 0L : sinceId;
        }
        return open(
                "org-event-sse-" + orgId,
                orgId,
                effectiveSinceId,
                lastId -> repository.findByOrgIdAndIdGreaterThanOrderByIdAsc(orgId, lastId),
                false);
    }

    /**
     * Shared connection setup for both the per-run and per-agent streams. {@code fetch} maps the
     * current cursor to the next batch; {@code terminateOnRunComplete} closes the stream when a
     * terminal event is seen (run path) or keeps it open across runs (agent path).
     */
    private SseEmitter open(String streamLabel, String orgId, Long sinceId,
                            LongFunction<List<AgentRunEventEntity>> fetch,
                            boolean terminateOnRunComplete) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // Tracks why the connection ended. Default "client" — fires when onCompletion runs
        // without the pump loop having tagged a terminal/timeout/error reason first. The
        // pump CAS-es from "client" to its specific reason; further close callbacks see the
        // already-set value and don't overwrite.
        AtomicReference<String> closeReason = new AtomicReference<>("client");
        long effectiveSinceId = sinceId == null ? 0L : sinceId;

        emitter.onCompletion(() -> {
            cancelled.set(true);
            recordCloseOnce(closeReason.get());
        });
        emitter.onTimeout(() -> {
            closeReason.compareAndSet("client", "timeout");
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> {
            closeReason.compareAndSet("client", "error");
            cancelled.set(true);
        });

        AgentRunEventNotifier.Subscription wakeup = notifier == null ? null : notifier.subscribe(orgId);

        connectionOpenCounter.increment();
        Thread.ofVirtual()
                .name(streamLabel)
                .start(() -> pumpLoop(streamLabel, orgId, effectiveSinceId, emitter, cancelled,
                        closeReason, fetch, terminateOnRunComplete, wakeup));

        return emitter;
    }

    private void recordCloseOnce(String reason) {
        switch (reason) {
            case "terminal" -> closeTerminalCounter.increment();
            case "timeout" -> closeTimeoutCounter.increment();
            case "error" -> closeErrorCounter.increment();
            default -> closeClientCounter.increment();
        }
    }

    private void pumpLoop(String streamLabel, String orgId, long startingSinceId,
                          SseEmitter emitter, AtomicBoolean cancelled,
                          AtomicReference<String> closeReason,
                          LongFunction<List<AgentRunEventEntity>> fetch,
                          boolean terminateOnRunComplete,
                          AgentRunEventNotifier.Subscription wakeup) {
        long lastId = startingSinceId;
        long startMs = System.currentTimeMillis();
        long hardDeadlineMs = emitterTimeoutMs + Math.min(emitterTimeoutMs, 30_000L);
        try {
            while (!cancelled.get()) {
                if (System.currentTimeMillis() - startMs > hardDeadlineMs) {
                    log.debug("RunEventSse: hard deadline reached stream={}", streamLabel);
                    closeReason.compareAndSet("client", "timeout");
                    try {
                        emitter.complete();
                    } catch (RuntimeException ignored) {
                        // emitter already closed
                    }
                    return;
                }
                List<AgentRunEventEntity> batch;
                try {
                    batch = fetch.apply(lastId);
                } catch (RuntimeException ex) {
                    log.warn("RunEventSse: repo query failed stream={} lastId={}", streamLabel, lastId, ex);
                    sleepQuietly();
                    continue;
                }

                boolean terminalSeen = false;
                for (AgentRunEventEntity entity : batch) {
                    if (cancelled.get()) return;
                    lastId = entity.getId();
                    if (orgId != null && entity.getOrgId() != null && !orgId.equals(entity.getOrgId())) {
                        continue;
                    }
                    if (!sendEvent(emitter, entity)) {
                        closeReason.compareAndSet("client", "error");
                        cancelled.set(true);
                        return;
                    }
                    if (terminateOnRunComplete && TERMINAL_EVENT_TYPES.contains(entity.getEventType())) {
                        terminalSeen = true;
                    }
                }

                if (terminalSeen) {
                    closeReason.compareAndSet("client", "terminal");
                    try {
                        emitter.complete();
                    } catch (RuntimeException ignored) {
                        // emitter already closed — nothing to do
                    }
                    return;
                }

                // Wait for the next batch: park on the NOTIFY wake-up when available (delivery is
                // near-instant on insert), else fall back to fixed-interval polling. Either way the
                // timeout bounds max staleness; correctness comes from the id-cursor replay above.
                if (!awaitNext(wakeup)) return;
            }
        } catch (RuntimeException ex) {
            log.warn("RunEventSse: pump loop aborted stream={}", streamLabel, ex);
            closeReason.compareAndSet("client", "error");
            try {
                emitter.completeWithError(ex);
            } catch (RuntimeException ignored) {
                // already closed
            }
        } finally {
            if (wakeup != null) {
                wakeup.close();
            }
        }
    }

    /**
     * Waits for the next poll cycle: blocks on the NOTIFY {@code wakeup} when present (waking early on
     * a committed insert), otherwise sleeps {@code pollIntervalMs}. Returns {@code false} only when
     * interrupted, signalling the pump to stop.
     */
    private boolean awaitNext(AgentRunEventNotifier.Subscription wakeup) {
        return wakeup != null ? wakeup.await(pollIntervalMs) : sleepQuietly();
    }

    private boolean sendEvent(SseEmitter emitter, AgentRunEventEntity entity) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(entity.getId()))
                    .name(entity.getEventType().name())
                    .data(toDto(entity)));
            return true;
        } catch (IOException ioe) {
            log.debug("RunEventSse: sink closed runId={} eventId={}",
                    entity.getRunId(), entity.getId());
            return false;
        } catch (IllegalStateException ise) {
            log.debug("RunEventSse: emitter already completed runId={} eventId={}",
                    entity.getRunId(), entity.getId());
            return false;
        }
    }

    private boolean sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static RunEventDto toDto(AgentRunEventEntity entity) {
        return new RunEventDto(
                entity.getId(),
                entity.getEventType() == null ? null : entity.getEventType().name(),
                entity.getRunId(),
                entity.getAgentId(),
                entity.getParentRunId(),
                entity.getSessionId(),
                entity.getOrgId(),
                entity.getOrchestrationDepth(),
                entity.getPayload(),
                entity.getEventTs()
        );
    }

    /**
     * @summary Wire-format payload for a single SSE event. Mirrors {@link AgentRunEventEntity}
     *     column layout with the {@code eventType} serialized as its enum name.
     */
    public record RunEventDto(
            Long id,
            String eventType,
            String runId,
            String agentId,
            String parentRunId,
            String sessionId,
            String orgId,
            Integer orchestrationDepth,
            Map<String, Object> payload,
            Instant eventTs
    ) {}
}
