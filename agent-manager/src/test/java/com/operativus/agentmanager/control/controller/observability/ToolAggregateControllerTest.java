package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.AgentRunEventRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ToolAggregateControllerTest {

    private MockMvc mockMvc;

    @Mock private AgentRunEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ToolAggregateController(eventRepository)).build();
    }

    @Test
    void getToolAggregates_HappyPath_ReturnsRollupAndTimeSeries() throws Exception {
        when(eventRepository.aggregateToolUsage(any(Instant.class), eq("org-1")))
                .thenReturn(List.of(
                        new Object[]{"web_search", 12L, 1L, BigDecimal.valueOf(245.5)},
                        new Object[]{"calculator", 5L, 0L, BigDecimal.valueOf(8.0)}));

        Timestamp t1 = Timestamp.from(Instant.parse("2026-04-25T00:00:00Z"));
        Timestamp t2 = Timestamp.from(Instant.parse("2026-04-26T00:00:00Z"));
        when(eventRepository.aggregateToolUsageOverTime(any(Instant.class), eq("org-1"), eq("day")))
                .thenReturn(List.of(
                        new Object[]{t1, "web_search", 5L},
                        new Object[]{t1, "calculator", 2L},
                        new Object[]{t2, "web_search", 7L},
                        new Object[]{t2, "calculator", 3L}));

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/tools?window=30&granularity=DAY"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tools.length()").value(2))
                        .andExpect(jsonPath("$.tools[0].toolName").value("web_search"))
                        .andExpect(jsonPath("$.tools[0].totalCount").value(12))
                        .andExpect(jsonPath("$.tools[0].errorCount").value(1))
                        .andExpect(jsonPath("$.tools[0].avgDurationMs").value(245.5))
                        .andExpect(jsonPath("$.overTime.length()").value(2))
                        .andExpect(jsonPath("$.overTime[0].perTool.web_search").value(5))
                        .andExpect(jsonPath("$.overTime[0].perTool.calculator").value(2));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getToolAggregates_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(eventRepository.aggregateToolUsage(any(Instant.class), isNull()))
                .thenReturn(List.of());
        when(eventRepository.aggregateToolUsageOverTime(any(Instant.class), isNull(), any(String.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools.length()").value(0))
                .andExpect(jsonPath("$.overTime.length()").value(0));

        verify(eventRepository).aggregateToolUsage(any(Instant.class), isNull());
        verify(eventRepository).aggregateToolUsageOverTime(any(Instant.class), isNull(), eq("day"));
    }

    @Test
    void getToolAggregates_UnknownGranularity_FallsBackToDay() throws Exception {
        when(eventRepository.aggregateToolUsage(any(), any())).thenReturn(List.of());
        when(eventRepository.aggregateToolUsageOverTime(any(), any(), eq("day"))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/tools?granularity=invalid"))
                .andExpect(status().isOk());

        verify(eventRepository).aggregateToolUsageOverTime(any(), any(), eq("day"));
    }

    @Test
    void getToolAggregates_BucketAsLocalDateTime_IsHandled() throws Exception {
        // Mirrors #661/#662: SQL `date_trunc(:granularity, event_ts AT TIME ZONE 'UTC')`
        // returns timestamp without time zone → Hibernate maps to LocalDateTime.
        // Without an explicit branch the asInstant fallback would call
        // Instant.parse("2026-04-25T00:00") and throw at index 16.
        java.time.LocalDateTime b1 = java.time.LocalDateTime.of(2026, 4, 25, 0, 0);
        java.time.LocalDateTime b2 = java.time.LocalDateTime.of(2026, 4, 26, 0, 0);
        when(eventRepository.aggregateToolUsage(any(Instant.class), isNull()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"web_search", 1L, 0L, BigDecimal.valueOf(100.0)}));
        when(eventRepository.aggregateToolUsageOverTime(any(Instant.class), isNull(), eq("day")))
                .thenReturn(List.<Object[]>of(
                        new Object[]{b1, "web_search", 5L},
                        new Object[]{b2, "web_search", 7L}));

        mockMvc.perform(get("/api/v1/observability/aggregates/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overTime.length()").value(2))
                .andExpect(jsonPath("$.overTime[0].perTool.web_search").value(5))
                .andExpect(jsonPath("$.overTime[1].perTool.web_search").value(7));
    }

    @Test
    void getToolAggregates_WindowOverMax_IsClampedTo365() throws Exception {
        when(eventRepository.aggregateToolUsage(any(), any())).thenReturn(List.of());
        when(eventRepository.aggregateToolUsageOverTime(any(), any(), any(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/tools?window=99999"))
                .andExpect(status().isOk());
    }
}
