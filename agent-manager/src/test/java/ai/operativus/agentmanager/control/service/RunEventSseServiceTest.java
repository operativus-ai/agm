package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.AgentRunEventRepository;
import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunEventSseServiceTest {

    private static final long FAST_POLL_MS = 20L;
    private static final long EMITTER_TIMEOUT_MS = 500L;
    private static final long AWAIT_MS = 2_000L;

    @Mock
    private AgentRunEventRepository repository;

    private RunEventSseService newService() {
        // null notifier → pump uses fixed-interval polling (the behavior these timing assertions rely on).
        return new RunEventSseService(repository, null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), FAST_POLL_MS, EMITTER_TIMEOUT_MS);
    }

    private AgentRunEventEntity entity(long id, AgentRunEventType type, String runId, String orgId) {
        AgentRunEventEntity e = new AgentRunEventEntity() {
            private final long fixedId = id;
            @Override public Long getId() { return fixedId; }
        };
        e.setEventType(type);
        e.setRunId(runId);
        e.setOrgId(orgId);
        e.setEventTs(Instant.parse("2026-04-23T12:00:00Z"));
        e.setPayload(Map.of("k", "v"));
        return e;
    }

    private int repoInvocationCount() {
        return Mockito.mockingDetails(repository).getInvocations().size();
    }

    private void assertPumpQuiesced() throws InterruptedException {
        Thread.sleep(FAST_POLL_MS * 5L);
        int a = repoInvocationCount();
        Thread.sleep(FAST_POLL_MS * 5L);
        int b = repoInvocationCount();
        assertEquals(a, b, "pump loop should stop after terminal event or cancellation");
    }

    @Test
    void stream_returnsEmitterAndStartsPumpLoop() {
        String runId = "run-0";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().stream(runId, null, null);

        assertNotNull(emitter, "stream must return an emitter");
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        emitter.complete(); // signal cancellation so the virtual thread exits
    }

    @Test
    void stream_emitsReplayThenStopsOnTerminalRunComplete() throws Exception {
        String runId = "run-1";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of(
                        entity(1L, AgentRunEventType.RUN_START, runId, null),
                        entity(2L, AgentRunEventType.TOOL_INVOKED, runId, null),
                        entity(3L, AgentRunEventType.RUN_COMPLETE, runId, null)));

        newService().stream(runId, null, null);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        assertPumpQuiesced();
    }

    @Test
    void stream_stopsOnTerminalRunFailed() throws Exception {
        String runId = "run-fail";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of(entity(9L, AgentRunEventType.RUN_FAILED, runId, null)));

        newService().stream(runId, null, null);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        assertPumpQuiesced();
    }

    @Test
    void stream_passesSinceIdCursorToRepository() throws Exception {
        String runId = "run-2";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 7L))
                .thenReturn(List.of(entity(8L, AgentRunEventType.RUN_COMPLETE, runId, null)));

        newService().stream(runId, null, 7L);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(7L));
        verify(repository, never())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        assertPumpQuiesced();
    }

    @Test
    void stream_nullSinceIdDefaultsToZero() throws Exception {
        String runId = "run-null";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of(entity(1L, AgentRunEventType.RUN_COMPLETE, runId, null)));

        newService().stream(runId, null, null);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        assertPumpQuiesced();
    }

    @Test
    void stream_advancesCursorAcrossPolls() throws Exception {
        String runId = "run-3";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of(entity(5L, AgentRunEventType.RUN_START, runId, null)));
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 5L))
                .thenReturn(List.of(entity(6L, AgentRunEventType.RUN_COMPLETE, runId, null)));

        newService().stream(runId, null, null);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(5L));
        assertPumpQuiesced();
    }

    @Test
    void stream_skipsNonMatchingOrgIdsButStillAdvancesCursor() throws Exception {
        String runId = "run-4";
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenReturn(List.of(
                        entity(1L, AgentRunEventType.RUN_START, runId, "other-org"),
                        entity(2L, AgentRunEventType.RUN_COMPLETE, runId, "my-org")));

        newService().stream(runId, "my-org", null);

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), eq(0L));
        assertPumpQuiesced();
    }

    @Test
    void stream_recoversFromTransientRepositoryException() throws Exception {
        String runId = "run-5";
        AtomicInteger calls = new AtomicInteger(0);
        when(repository.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, 0L))
                .thenAnswer(inv -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new RuntimeException("db boom");
                    }
                    return List.of(entity(1L, AgentRunEventType.RUN_FAILED, runId, null));
                });

        newService().stream(runId, null, null);

        verify(repository, timeout(AWAIT_MS).atLeast(2))
                .findByRunIdAndIdGreaterThanOrderByIdAsc(eq(runId), anyLong());
        assertPumpQuiesced();
    }

    @Test
    void streamByAgent_usesAgentAndOrgScopedQuery() {
        String agentId = "agent-x";
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "my-org", 0L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByAgent(agentId, null, "my-org");

        assertNotNull(emitter, "streamByAgent must return an emitter");
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("my-org"), eq(0L));
        // The agent stream must NEVER fall through to the run-scoped query (that one ignores orgId).
        verify(repository, never())
                .findByRunIdAndIdGreaterThanOrderByIdAsc(anyString(), anyLong());
        emitter.complete();
    }

    @Test
    void streamByOrg_usesOrgScopedQueryAndStaysOpen() {
        when(repository.findByOrgIdAndIdGreaterThanOrderByIdAsc("my-org", 0L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByOrg(null, "my-org");

        assertNotNull(emitter, "streamByOrg must return an emitter");
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByOrgIdAndIdGreaterThanOrderByIdAsc(eq("my-org"), eq(0L));
        // Org firehose must not fall through to the run- or agent-scoped queries.
        verify(repository, never()).findByRunIdAndIdGreaterThanOrderByIdAsc(anyString(), anyLong());
        verify(repository, never())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(anyString(), anyString(), anyLong());
        emitter.complete();
    }

    @Test
    void streamByOrg_startFromLatestSentinel_resolvesToMaxId() {
        when(repository.findMaxIdByOrgId("my-org")).thenReturn(500L);
        when(repository.findByOrgIdAndIdGreaterThanOrderByIdAsc("my-org", 500L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByOrg(-1L, "my-org");

        verify(repository, timeout(AWAIT_MS).atLeastOnce()).findMaxIdByOrgId("my-org");
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByOrgIdAndIdGreaterThanOrderByIdAsc(eq("my-org"), eq(500L));
        verify(repository, never())
                .findByOrgIdAndIdGreaterThanOrderByIdAsc(eq("my-org"), eq(0L));
        emitter.complete();
    }

    @Test
    void streamByAgent_doesNotCloseOnRunComplete() throws Exception {
        String agentId = "agent-live";
        // A single run's RUN_COMPLETE must NOT terminate the per-agent stream — an agent has many
        // runs over time. After consuming the terminal event the cursor advances to 1 and the pump
        // keeps polling at that cursor until the emitter timeout.
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "org-1", 0L))
                .thenReturn(List.of(entity(1L, AgentRunEventType.RUN_COMPLETE, "run-a", "org-1")));
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "org-1", 1L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByAgent(agentId, null, "org-1");

        // Repeated polls at the post-terminal cursor prove the stream stayed open across the
        // RUN_COMPLETE — the run path would have called emitter.complete() and stopped here.
        verify(repository, timeout(AWAIT_MS).atLeast(2))
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("org-1"), eq(1L));
        emitter.complete();
    }

    @Test
    void streamByAgent_startFromLatestSentinel_resolvesToMaxIdAndTails() {
        String agentId = "agent-tail";
        // sinceId < 0 = "start from latest": resolve to the agent's current max id and stream only
        // newer rows — never replay the full history.
        when(repository.findMaxIdByAgentIdAndOrgId(agentId, "org-1")).thenReturn(100L);
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "org-1", 100L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByAgent(agentId, -1L, "org-1");

        assertNotNull(emitter);
        verify(repository, timeout(AWAIT_MS).atLeastOnce()).findMaxIdByAgentIdAndOrgId(agentId, "org-1");
        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("org-1"), eq(100L));
        // Must NOT replay from 0 — that would dump the whole history the sentinel exists to avoid.
        verify(repository, never())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("org-1"), eq(0L));
        emitter.complete();
    }

    @Test
    void streamByAgent_startFromLatestSentinel_noEvents_startsAtZero() {
        String agentId = "agent-tail-empty";
        when(repository.findMaxIdByAgentIdAndOrgId(agentId, "org-1")).thenReturn(null);
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "org-1", 0L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByAgent(agentId, -1L, "org-1");

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("org-1"), eq(0L));
        emitter.complete();
    }

    @Test
    void streamByAgent_nonNegativeSinceId_doesNotConsultMaxId() {
        String agentId = "agent-resume";
        when(repository.findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(agentId, "org-1", 42L))
                .thenReturn(List.of());

        SseEmitter emitter = newService().streamByAgent(agentId, 42L, "org-1");

        verify(repository, timeout(AWAIT_MS).atLeastOnce())
                .findByAgentIdAndOrgIdAndIdGreaterThanOrderByIdAsc(eq(agentId), eq("org-1"), eq(42L));
        // A normal resume cursor must not trigger the max-id lookup.
        verify(repository, never()).findMaxIdByAgentIdAndOrgId(anyString(), anyString());
        emitter.complete();
    }

    @Test
    void toDto_preservesAllFields() {
        AgentRunEventEntity e = entity(42L, AgentRunEventType.LLM_REQUEST, "rx", "orgZ");
        e.setAgentId("agent-a");
        e.setParentRunId("parent-r");
        e.setSessionId("sess-1");
        e.setOrchestrationDepth(3);

        RunEventSseService.RunEventDto dto = RunEventSseService.toDto(e);

        assertNotNull(dto);
        assertEquals(42L, dto.id());
        assertEquals("LLM_REQUEST", dto.eventType());
        assertEquals("rx", dto.runId());
        assertEquals("agent-a", dto.agentId());
        assertEquals("parent-r", dto.parentRunId());
        assertEquals("sess-1", dto.sessionId());
        assertEquals("orgZ", dto.orgId());
        assertEquals(3, dto.orchestrationDepth());
        assertEquals("v", dto.payload().get("k"));
        assertEquals(Instant.parse("2026-04-23T12:00:00Z"), dto.eventTs());
    }

    @Test
    void toDto_handlesNullEventType() {
        AgentRunEventEntity e = new AgentRunEventEntity();
        e.setRunId("rx");
        e.setEventTs(Instant.EPOCH);

        RunEventSseService.RunEventDto dto = RunEventSseService.toDto(e);

        assertNotNull(dto);
        assertTrue(dto.eventType() == null, "null event type must map to null string, not explode");
    }
}
