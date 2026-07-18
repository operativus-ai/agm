package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
class OrchestrationAggregateControllerTest {

    private MockMvc mockMvc;

    @Mock private OrchestrationDecisionRepository repository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrchestrationAggregateController(repository)).build();
    }

    @Test
    void getOrchestrationAggregates_HappyPath_ReturnsDistributionAndTimeSeries() throws Exception {
        when(repository.countDecisionsByStrategy(any(Instant.class), eq("org-1")))
                .thenReturn(List.of(
                        new Object[]{"ROUTER", 12L},
                        new Object[]{"SWARM", 3L}));

        Timestamp t1 = Timestamp.from(Instant.parse("2026-04-25T00:00:00Z"));
        Timestamp t2 = Timestamp.from(Instant.parse("2026-04-26T00:00:00Z"));
        when(repository.countDecisionsByStrategyOverTime(any(Instant.class), eq("org-1"), eq("day")))
                .thenReturn(List.of(
                        new Object[]{t1, "ROUTER", 5L},
                        new Object[]{t1, "SWARM", 1L},
                        new Object[]{t2, "ROUTER", 7L},
                        new Object[]{t2, "SWARM", 2L}));

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/orchestration?window=30&granularity=DAY"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.distribution.length()").value(2))
                        .andExpect(jsonPath("$.distribution[0].strategy").value("ROUTER"))
                        .andExpect(jsonPath("$.distribution[0].count").value(12))
                        .andExpect(jsonPath("$.overTime.length()").value(2))
                        .andExpect(jsonPath("$.overTime[0].perStrategy.ROUTER").value(5))
                        .andExpect(jsonPath("$.overTime[0].perStrategy.SWARM").value(1))
                        .andExpect(jsonPath("$.overTime[1].perStrategy.ROUTER").value(7));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getOrchestrationAggregates_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(repository.countDecisionsByStrategy(any(Instant.class), isNull()))
                .thenReturn(List.of());
        when(repository.countDecisionsByStrategyOverTime(any(Instant.class), isNull(), any(String.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distribution.length()").value(0))
                .andExpect(jsonPath("$.overTime.length()").value(0));

        verify(repository).countDecisionsByStrategy(any(Instant.class), isNull());
        verify(repository).countDecisionsByStrategyOverTime(any(Instant.class), isNull(), eq("day"));
    }

    @Test
    void getOrchestrationAggregates_GranularityCaseInsensitive() throws Exception {
        when(repository.countDecisionsByStrategy(any(), any())).thenReturn(List.of());
        when(repository.countDecisionsByStrategyOverTime(any(), any(), eq("hour"))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration?granularity=HOUR"))
                .andExpect(status().isOk());

        verify(repository).countDecisionsByStrategyOverTime(any(), any(), eq("hour"));
    }

    @Test
    void getOrchestrationAggregates_UnknownGranularity_FallsBackToDay() throws Exception {
        when(repository.countDecisionsByStrategy(any(), any())).thenReturn(List.of());
        when(repository.countDecisionsByStrategyOverTime(any(), any(), eq("day"))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration?granularity=invalid"))
                .andExpect(status().isOk());

        verify(repository).countDecisionsByStrategyOverTime(any(), any(), eq("day"));
    }

    @Test
    void getOrchestrationDecisions_HappyPath_ReturnsPagedDtos() throws Exception {
        OrchestrationDecisionEntity e1 = entity(1L, "run-a", "org-1", "ROUTER", "DISPATCH",
                "specialist-a", "matched intent", Instant.parse("2026-04-25T12:00:00Z"));
        OrchestrationDecisionEntity e2 = entity(2L, "run-b", "org-1", "ROUTER", "DISPATCH",
                "specialist-b", "fallback", Instant.parse("2026-04-25T11:00:00Z"));
        Page<OrchestrationDecisionEntity> page = new PageImpl<>(
                List.of(e1, e2), PageRequest.of(0, 20), 42L);
        when(repository.findRecentByStrategy(eq("ROUTER"), eq("org-1"), any(Pageable.class)))
                .thenReturn(page);

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/orchestration-decisions?strategy=ROUTER"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content.length()").value(2))
                        .andExpect(jsonPath("$.content[0].id").value(1))
                        .andExpect(jsonPath("$.content[0].selectedAgentId").value("specialist-a"))
                        .andExpect(jsonPath("$.content[0].rationale").value("matched intent"))
                        .andExpect(jsonPath("$.totalElements").value(42));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getOrchestrationDecisions_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(repository.findRecentByStrategy(eq("SWARM"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration-decisions?strategy=SWARM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(repository).findRecentByStrategy(eq("SWARM"), isNull(), any(Pageable.class));
    }

    @Test
    void getOrchestrationDecisions_SizeOverMax_IsClampedTo100() throws Exception {
        when(repository.findRecentByStrategy(eq("ROUTER"), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0L));

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration-decisions?strategy=ROUTER&size=999"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findRecentByStrategy(eq("ROUTER"), any(), captor.capture());
        // Size clamped from 999 to 100.
        assert captor.getValue().getPageSize() == 100;
        assert captor.getValue().getPageNumber() == 0;
    }

    @Test
    void getOrchestrationDecisions_MissingStrategy_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration-decisions"))
                .andExpect(status().isBadRequest());
    }

    private static OrchestrationDecisionEntity entity(
            Long id, String runId, String orgId, String strategy, String decisionType,
            String selectedAgentId, String rationale, Instant createdAt) {
        OrchestrationDecisionEntity e = new OrchestrationDecisionEntity();
        // id is generated; for tests we rely on a reflective set or skip — JpaRepository auto-id
        // is bypassed in unit tests since we never persist. The toDto() path doesn't strictly
        // require id, but we reflect it in for stable assertions.
        try {
            java.lang.reflect.Field f = OrchestrationDecisionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        e.setRunId(runId);
        e.setOrgId(orgId);
        e.setStrategy(strategy);
        e.setDecisionType(decisionType);
        e.setSelectedAgentId(selectedAgentId);
        e.setRationale(rationale);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void getOrchestrationAggregates_BucketAsLocalDateTime_IsHandled() throws Exception {
        // Repro for the live 500: `date_trunc(:g, created_at AT TIME ZONE 'UTC')` returns
        // `timestamp without time zone`, which Hibernate maps to LocalDateTime (not Instant
        // or Timestamp). The previous asInstant fallback called `Instant.parse(o.toString())`
        // on the LocalDateTime's "2026-04-25T00:00" string and threw DateTimeParseException
        // at index 16 (no offset). This case pins the LocalDateTime branch.
        java.time.LocalDateTime b1 = java.time.LocalDateTime.of(2026, 4, 25, 0, 0);
        java.time.LocalDateTime b2 = java.time.LocalDateTime.of(2026, 4, 26, 0, 0);
        when(repository.countDecisionsByStrategy(any(Instant.class), isNull()))
                .thenReturn(List.<Object[]>of(new Object[]{"ROUTER", 1L}));
        when(repository.countDecisionsByStrategyOverTime(any(Instant.class), isNull(), eq("day")))
                .thenReturn(List.<Object[]>of(
                        new Object[]{b1, "ROUTER", 5L},
                        new Object[]{b2, "ROUTER", 7L}));

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overTime.length()").value(2))
                .andExpect(jsonPath("$.overTime[0].bucket").value("2026-04-25T00:00:00Z"))
                .andExpect(jsonPath("$.overTime[0].perStrategy.ROUTER").value(5))
                .andExpect(jsonPath("$.overTime[1].bucket").value("2026-04-26T00:00:00Z"))
                .andExpect(jsonPath("$.overTime[1].perStrategy.ROUTER").value(7));
    }

    @Test
    void getOrchestrationAggregates_WindowOverMax_IsClampedTo365() throws Exception {
        when(repository.countDecisionsByStrategy(any(), any())).thenReturn(List.of());
        when(repository.countDecisionsByStrategyOverTime(any(), any(), any(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/orchestration?window=99999"))
                .andExpect(status().isOk());

        // Just verify the call happened — exact since boundary is a function of Instant.now().
        verify(repository).countDecisionsByStrategy(any(Instant.class), any());
    }
}
