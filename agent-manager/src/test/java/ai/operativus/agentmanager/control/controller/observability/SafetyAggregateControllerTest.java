package ai.operativus.agentmanager.control.controller.observability;

import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
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
class SafetyAggregateControllerTest {

    private MockMvc mockMvc;

    @Mock private RunRepository runRepository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SafetyAggregateController(runRepository)).build();
    }

    @Test
    void getSafetyAggregates_HappyPath_ReturnsHeatmapAndFlaggedRuns() throws Exception {
        Timestamp d1 = Timestamp.from(Instant.parse("2026-04-25T00:00:00Z"));
        when(runRepository.findSafetyHeatmap(any(Instant.class), eq("org-1")))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"agent-a", d1, BigDecimal.valueOf(0.42), BigDecimal.valueOf(0.85), 2L, 7L},
                        new Object[]{"agent-b", d1, BigDecimal.valueOf(0.10), BigDecimal.valueOf(0.20), 0L, 5L}));

        Timestamp createdAt = Timestamp.from(Instant.parse("2026-04-25T10:00:00Z"));
        when(runRepository.findFlaggedRunsTop(any(Instant.class), eq("org-1"), eq(20)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"run-1", "agent-a", BigDecimal.valueOf(0.85), createdAt}));

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/safety?window=30"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.cells.length()").value(2))
                        .andExpect(jsonPath("$.cells[0].agentId").value("agent-a"))
                        .andExpect(jsonPath("$.cells[0].avgScore").value(0.42))
                        .andExpect(jsonPath("$.cells[0].maxScore").value(0.85))
                        .andExpect(jsonPath("$.cells[0].flagged").value(2))
                        .andExpect(jsonPath("$.cells[0].total").value(7))
                        .andExpect(jsonPath("$.flaggedRunsTopN.length()").value(1))
                        .andExpect(jsonPath("$.flaggedRunsTopN[0].runId").value("run-1"))
                        .andExpect(jsonPath("$.flaggedRunsTopN[0].score").value(0.85));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getSafetyAggregates_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(runRepository.findSafetyHeatmap(any(Instant.class), isNull())).thenReturn(List.<Object[]>of());
        when(runRepository.findFlaggedRunsTop(any(Instant.class), isNull(), eq(20))).thenReturn(List.<Object[]>of());

        mockMvc.perform(get("/api/v1/observability/aggregates/safety"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(0))
                .andExpect(jsonPath("$.flaggedRunsTopN.length()").value(0));

        verify(runRepository).findSafetyHeatmap(any(Instant.class), isNull());
        verify(runRepository).findFlaggedRunsTop(any(Instant.class), isNull(), eq(20));
    }

    @Test
    void getSafetyAggregates_WindowOverMax_IsClampedTo365() throws Exception {
        when(runRepository.findSafetyHeatmap(any(Instant.class), any())).thenReturn(List.<Object[]>of());
        when(runRepository.findFlaggedRunsTop(any(Instant.class), any(), eq(20))).thenReturn(List.<Object[]>of());

        mockMvc.perform(get("/api/v1/observability/aggregates/safety?window=99999"))
                .andExpect(status().isOk());
    }
}
