package com.operativus.agentmanager.control.service;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Event-driven wake-up source for the SSE event streams. Holds one dedicated
 *     {@code LISTEN agent_run_events} connection and dispatches each Postgres NOTIFY (fired by the
 *     {@code trg_agent_run_events_notify} trigger on insert) to the matching {@link Subscription}s,
 *     so {@link RunEventSseService}'s pump loops wake the instant a row is committed instead of
 *     polling on a fixed timer.
 * State: Stateful — owns a background listener virtual thread, a dedicated DB connection, and a live
 *     {@code Subscription} registry.
 *
 * <p><b>Correctness is independent of NOTIFY.</b> Subscriptions are an optimization layer only: the
 * pump still replays from {@code agent_run_events} by id ({@code sinceId}) on every wake. If the
 * listener connection is down (startup race, DB restart) {@link Subscription#await} simply times out
 * at the caller's interval, degrading transparently to the original timer-based polling. A missed or
 * dropped notification therefore only delays delivery to the next heartbeat — it never loses events.
 *
 * <p><b>Resource cost:</b> one pooled DB connection is held for the lifetime of an active LISTEN.
 */
@Component
public class AgentRunEventNotifier implements SmartLifecycle {

    static final String CHANNEL = "agent_run_events";

    private static final Logger log = LoggerFactory.getLogger(AgentRunEventNotifier.class);

    private final DataSource dataSource;
    /** How long the listen loop blocks for notifications before looping (also the reconnect cadence). */
    private final int heartbeatMs;
    private final long reconnectBackoffMs;

    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private volatile boolean running = false;
    private volatile boolean healthy = false;
    private volatile Thread listenThread;

    public AgentRunEventNotifier(
            DataSource dataSource,
            @Value("${agent.run.events.notify.heartbeat-ms:5000}") int heartbeatMs,
            @Value("${agent.run.events.notify.reconnect-backoff-ms:2000}") long reconnectBackoffMs) {
        this.dataSource = dataSource;
        this.heartbeatMs = heartbeatMs;
        this.reconnectBackoffMs = reconnectBackoffMs;
    }

    /** True when the LISTEN connection is established and notifications are flowing. */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Registers a wake-up subscription for {@code orgId}. The returned {@link Subscription} wakes on
     * notifications whose payload (the inserted row's org_id) matches — or on every notification when
     * {@code orgId} is null (super-admin / unscoped streams). Always close it when the stream ends.
     */
    public Subscription subscribe(String orgId) {
        Subscription s = new Subscription(orgId);
        subscribers.add(s);
        return s;
    }

    @Override
    public void start() {
        running = true;
        listenThread = Thread.ofVirtual().name("agent-run-event-listener").start(this::listenLoop);
        log.info("AgentRunEventNotifier started");
    }

    @Override
    public void stop() {
        running = false;
        healthy = false;
        Thread t = listenThread;
        if (t != null) {
            t.interrupt();
        }
        // Release any pump loops parked on a subscription so they fall back to their own deadlines.
        subscribers.forEach(Subscription::signal);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenLoop() {
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN " + CHANNEL);
                }
                PGConnection pg = conn.unwrap(PGConnection.class);
                healthy = true;
                log.info("AgentRunEventNotifier: LISTEN {} established", CHANNEL);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(heartbeatMs);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            wake(n.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                healthy = false;
                if (!running) {
                    break;
                }
                log.warn("AgentRunEventNotifier: LISTEN connection lost; reconnecting after {}ms",
                        reconnectBackoffMs, e);
                sleep(reconnectBackoffMs);
            }
        }
        healthy = false;
        log.info("AgentRunEventNotifier stopped");
    }

    private void wake(String payload) {
        for (Subscription s : subscribers) {
            if (s.matches(payload)) {
                s.signal();
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A single stream's wake-up handle. {@link #await} parks the pump's virtual thread until a
     * matching notification arrives or the caller's heartbeat elapses; multiple notifications between
     * awaits coalesce into a single wake.
     */
    public final class Subscription implements AutoCloseable {
        private final String orgId;
        private final Semaphore permit = new Semaphore(0);

        private Subscription(String orgId) {
            this.orgId = orgId;
        }

        /**
         * Wake on: an unscoped (null-org) subscriber, a notification with no org payload, or an
         * exact org match. Errs toward waking — a spurious wake just triggers an empty re-fetch.
         */
        private boolean matches(String payload) {
            return orgId == null || payload == null || payload.isBlank() || payload.equals(orgId);
        }

        private void signal() {
            // Cap at one outstanding permit; await() drains, so a single wake covers any burst.
            if (permit.availablePermits() == 0) {
                permit.release();
            }
        }

        /**
         * Blocks until woken by a matching notification or until {@code timeoutMs} elapses (the
         * heartbeat / max-staleness bound). Returns {@code true} to continue the pump loop, {@code
         * false} only if the thread was interrupted (the pump should then exit). Coalesces bursts.
         */
        public boolean await(long timeoutMs) {
            try {
                permit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
                permit.drainPermits();
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void close() {
            subscribers.remove(this);
        }
    }
}
