package ai.operativus.agentmanager.core.event;

import ai.operativus.agentmanager.control.repository.AgentRunEventRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunEventBusTest {

    @Mock
    private AgentRunEventRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AgentRunEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new AgentRunEventBus(repository, eventPublisher, true);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void publish_FansOutToEventPublisherAndRepository() throws Exception {
        AgentRunEvent event = AgentRunEvent.of(
                AgentRunEventType.RUN_START, "run-1", "agent-1", Map.of("k", "v"));

        when(repository.save(any(AgentRunEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        bus.publish(event);

        verify(eventPublisher, times(1)).publishEvent(event);

        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        waitForSave(captor, 1);
        AgentRunEventEntity saved = captor.getValue();
        assertEquals(AgentRunEventType.RUN_START, saved.getEventType());
        assertEquals("run-1", saved.getRunId());
        assertEquals("agent-1", saved.getAgentId());
        assertEquals(Map.of("k", "v"), saved.getPayload());
        assertNotNull(saved.getEventTs());
    }

    @Test
    void publish_NullEventIsIgnored() {
        bus.publish(null);
        verify(eventPublisher, never()).publishEvent(any());
        verify(repository, never()).save(any());
    }

    @Test
    void publish_EventPublisherFailure_DoesNotBlockPersistence() throws Exception {
        AgentRunEvent event = AgentRunEvent.of(
                AgentRunEventType.TOOL_INVOKED, "run-2", "agent-2", Map.of());

        doThrow(new RuntimeException("publisher down")).when(eventPublisher).publishEvent(any());
        when(repository.save(any(AgentRunEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        bus.publish(event);

        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        waitForSave(captor, 1);
        assertEquals("run-2", captor.getValue().getRunId());
    }

    @Test
    void publish_RepositoryFailure_DoesNotBlockPublisher() throws Exception {
        AgentRunEvent event = AgentRunEvent.of(
                AgentRunEventType.RUN_FAILED, "run-3", "agent-3", Map.of());

        when(repository.save(any(AgentRunEventEntity.class)))
                .thenThrow(new RuntimeException("db down"));

        bus.publish(event);

        verify(eventPublisher, times(1)).publishEvent(event);
        waitForSaveAttempts(3);
    }

    @Test
    void publish_TransientRepositoryFailure_RetriesUpToThreeTimes() throws Exception {
        AgentRunEvent event = AgentRunEvent.of(
                AgentRunEventType.RUN_COMPLETE, "run-4", "agent-4", Map.of());

        when(repository.save(any(AgentRunEventEntity.class)))
                .thenThrow(new RuntimeException("first"))
                .thenThrow(new RuntimeException("second"))
                .thenAnswer(inv -> inv.getArgument(0));

        bus.publish(event);

        waitForSaveAttempts(3);
        verify(repository, times(3)).save(any(AgentRunEventEntity.class));
    }

    // ─── Tiered granular-event gating ────────────────────────────────────────

    // NB: ApplicationEventPublisher has publishEvent(Object) AND publishEvent(ApplicationEvent).
    // AgentRunEvent is a plain record, so the real call resolves to publishEvent(Object); verifying
    // the exact event (or any(Object.class)) targets that overload — a bare any() binds to the
    // ApplicationEvent overload and silently never matches.

    @Test
    void publish_GranularEventSuppressed_WhenDefaultDisabledAndNoPerRunBinding() throws Exception {
        AgentRunEventBus disabledBus = new AgentRunEventBus(repository, eventPublisher, false);
        try {
            disabledBus.publish(AgentRunEvent.of(AgentRunEventType.TOOL_INVOKED, "run-g1", "agent", Map.of()));
            // Gate short-circuits before any fan-out: no in-process publish, no persist.
            verify(eventPublisher, never()).publishEvent(any(Object.class));
            Thread.sleep(150);
            verify(repository, never()).save(any());
        } finally {
            disabledBus.close();
        }
    }

    @Test
    void publish_LifecycleEventAlwaysEmitted_EvenWhenGranularDisabled() throws Exception {
        AgentRunEventBus disabledBus = new AgentRunEventBus(repository, eventPublisher, false);
        when(repository.save(any(AgentRunEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        try {
            AgentRunEvent event = AgentRunEvent.of(AgentRunEventType.RUN_START, "run-l1", "agent", Map.of());
            disabledBus.publish(event);
            verify(eventPublisher, times(1)).publishEvent(event);
            waitForSaveAttempts(1);
        } finally {
            disabledBus.close();
        }
    }

    @Test
    void publish_PerRunBindingFalse_SuppressesGranular_EvenWhenDefaultEnabled() throws Exception {
        // bus default = true (from setUp), but the per-run scoped flag is false → suppressed.
        ScopedValue.where(AgentContextHolder.emitGranularEvents, false).run(() ->
                bus.publish(AgentRunEvent.of(AgentRunEventType.LLM_REQUEST, "run-p1", "agent", Map.of())));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        Thread.sleep(150);
        verify(repository, never()).save(any());
    }

    @Test
    void publish_PerRunBindingTrue_EmitsGranular_EvenWhenDefaultDisabled() throws Exception {
        AgentRunEventBus disabledBus = new AgentRunEventBus(repository, eventPublisher, false);
        when(repository.save(any(AgentRunEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        try {
            AgentRunEvent event = AgentRunEvent.of(AgentRunEventType.TOOL_INVOKED, "run-p2", "agent", Map.of());
            ScopedValue.where(AgentContextHolder.emitGranularEvents, true).run(() -> disabledBus.publish(event));
            verify(eventPublisher, times(1)).publishEvent(event);
            waitForSaveAttempts(1);
        } finally {
            disabledBus.close();
        }
    }

    private void waitForSave(ArgumentCaptor<AgentRunEventEntity> captor, int expected) throws InterruptedException {
        pollUntil(() -> verify(repository, times(expected)).save(captor.capture()));
    }

    private void waitForSaveAttempts(int expected) throws InterruptedException {
        pollUntil(() -> verify(repository, atLeast(expected)).save(any(AgentRunEventEntity.class)));
    }

    private void pollUntil(Runnable check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        AssertionError last = null;
        while (System.nanoTime() < deadline) {
            try {
                check.run();
                return;
            } catch (AssertionError ae) {
                last = ae;
                Thread.sleep(20);
            }
        }
        if (last != null) throw last;
    }
}
