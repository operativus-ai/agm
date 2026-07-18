package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgentRunEventRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentRunEventEntity;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BudgetExceededControllerTest {

    private MockMvc mockMvc;

    @Mock private AgentRunEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BudgetExceededController(eventRepository)).build();
    }

    @Test
    void getFeed_WithEvents_NextCursorIsNewestEventTs() throws Exception {
        Instant t1 = Instant.parse("2026-04-25T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-25T10:30:00Z");
        when(eventRepository.findFeedByEventTypeAndOrg(
                eq(AgentRunEventType.BUDGET_EXCEEDED), any(Instant.class), eq("org-1"), any(Pageable.class)))
                .thenReturn(List.of(
                        budgetEvent(1L, "run-a", "agent-a", t1, Map.of("limit", "10.00", "actual", "12.50")),
                        budgetEvent(2L, "run-b", "agent-b", t2, Map.of("limit", "5.00", "actual", "5.50"))));

        // orgId now comes from AgentContextHolder (bound by TenantContextFilter in prod);
        // bind via ScopedValue.where() to simulate the filter-bound state. The X-Org-Id
        // header is no longer read by the controller — getFeed_HeaderIsIgnored pins that.
        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/observability/budget-exceeded-feed?since=2026-04-25T00:00:00Z"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.events.length()").value(2))
                        .andExpect(jsonPath("$.events[0].runId").value("run-a"))
                        .andExpect(jsonPath("$.events[0].payload.limit").value("10.00"))
                        .andExpect(jsonPath("$.events[1].runId").value("run-b"))
                        // nextCursor is the newest event's eventTs.
                        .andExpect(jsonPath("$.nextCursor").value("2026-04-25T10:30:00Z"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * IDOR pin: a stray {@code X-Org-Id} header on its own (no AgentContextHolder binding)
     * must NOT steer the query. Before the IDOR fix, the controller honored the header
     * directly via {@code @RequestHeader}; now it reads only from AgentContextHolder, so
     * the header is inert and the repo receives {@code null} (admin/unauthenticated path).
     */
    @Test
    void getFeed_StrayXOrgIdHeader_DoesNotSteerQuery() throws Exception {
        when(eventRepository.findFeedByEventTypeAndOrg(any(), any(Instant.class), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/observability/budget-exceeded-feed?since=2026-04-25T00:00:00Z")
                        .header("X-Org-Id", "victim-org"))
                .andExpect(status().isOk());

        verify(eventRepository).findFeedByEventTypeAndOrg(
                eq(AgentRunEventType.BUDGET_EXCEEDED), any(Instant.class), isNull(), any(Pageable.class));
    }

    @Test
    void getFeed_NoEvents_NextCursorEchoesSinceParam() throws Exception {
        when(eventRepository.findFeedByEventTypeAndOrg(any(), any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/observability/budget-exceeded-feed?since=2026-04-25T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0))
                // Empty result echoes the since param so the next poll reuses the same cursor.
                .andExpect(jsonPath("$.nextCursor").value("2026-04-25T00:00:00Z"));
    }

    @Test
    void getFeed_NoSinceParam_DefaultsToThirtyDayLookback() throws Exception {
        // The exact lookback boundary depends on Instant.now(); just confirm the call
        // happens with a non-null since (vs being skipped).
        when(eventRepository.findFeedByEventTypeAndOrg(any(), any(Instant.class), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/observability/budget-exceeded-feed"))
                .andExpect(status().isOk());

        verify(eventRepository).findFeedByEventTypeAndOrg(
                eq(AgentRunEventType.BUDGET_EXCEEDED), any(Instant.class), isNull(), any(Pageable.class));
    }

    @Test
    void getFeed_LimitOverMax_IsClampedTo200() throws Exception {
        when(eventRepository.findFeedByEventTypeAndOrg(any(), any(Instant.class), any(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/observability/budget-exceeded-feed?limit=99999"))
                .andExpect(status().isOk());

        verify(eventRepository).findFeedByEventTypeAndOrg(
                eq(AgentRunEventType.BUDGET_EXCEEDED),
                any(Instant.class),
                isNull(),
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 200));
    }

    private static AgentRunEventEntity budgetEvent(Long id, String runId, String agentId, Instant ts, Map<String, Object> payload) {
        AgentRunEventEntity e = new AgentRunEventEntity();
        // id is generated; mock framework allows getter return via reflection in tests but
        // for this stub we stick with public setters where they exist.
        try {
            java.lang.reflect.Field idField = AgentRunEventEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (ReflectiveOperationException ignore) { /* fall through */ }
        e.setEventType(AgentRunEventType.BUDGET_EXCEEDED);
        e.setRunId(runId);
        e.setAgentId(agentId);
        e.setEventTs(ts);
        e.setPayload(payload);
        return e;
    }
}
