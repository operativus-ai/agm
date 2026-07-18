package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class SessionAggregateControllerTest {

    private MockMvc mockMvc;

    @Mock private SessionRepository repository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionAggregateController(repository)).build();
    }

    @Test
    void getSessionAggregates_HappyPath_MapsRowsToBuckets() throws Exception {
        Timestamp d1 = Timestamp.from(Instant.parse("2026-04-23T00:00:00Z"));
        Timestamp d2 = Timestamp.from(Instant.parse("2026-04-24T00:00:00Z"));
        when(repository.findSessionAnalytics(any(Instant.class), eq("org-1")))
                .thenReturn(List.<Object[]>of(
                        new Object[]{d1, 4L, 12.5d, 80.0d, 2.25d},
                        new Object[]{d2, 7L, 15.0d, 90.0d, 3.10d}));

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/sessions?window=30"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.buckets.length()").value(2))
                        .andExpect(jsonPath("$.buckets[0].sessionCount").value(4))
                        .andExpect(jsonPath("$.buckets[0].p50DurationSeconds").value(12.5))
                        .andExpect(jsonPath("$.buckets[0].p95DurationSeconds").value(80.0))
                        .andExpect(jsonPath("$.buckets[0].avgRunsPerSession").value(2.25))
                        .andExpect(jsonPath("$.buckets[1].sessionCount").value(7))
                        .andExpect(jsonPath("$.buckets[1].avgRunsPerSession").value(3.10));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getSessionAggregates_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(repository.findSessionAnalytics(any(Instant.class), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(0));

        verify(repository).findSessionAnalytics(any(Instant.class), isNull());
    }

    @Test
    void getSessionAggregates_WindowOverMax_IsClampedTo365() throws Exception {
        when(repository.findSessionAnalytics(any(Instant.class), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/sessions?window=99999"))
                .andExpect(status().isOk());

        verify(repository).findSessionAnalytics(any(Instant.class), any());
    }

    @Test
    void getSessionAggregates_BucketAsLocalDateTime_IsHandled() throws Exception {
        // Mirrors #661/#662: SQL `date_trunc('day', s.created_at AT TIME ZONE 'UTC')`
        // returns timestamp without time zone → Hibernate maps to LocalDateTime.
        // Without an explicit branch the asInstant fallback would call
        // Instant.parse("2026-04-23T00:00") and throw at index 16.
        java.time.LocalDateTime b1 = java.time.LocalDateTime.of(2026, 4, 23, 0, 0);
        when(repository.findSessionAnalytics(any(Instant.class), isNull()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{b1, 4L, 12.5d, 80.0d, 2.25d}));

        mockMvc.perform(get("/api/v1/observability/aggregates/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].sessionCount").value(4));
    }

    @Test
    void getSessionAggregates_NullPercentilesAreCoercedToZero() throws Exception {
        Timestamp d1 = Timestamp.from(Instant.parse("2026-04-23T00:00:00Z"));
        when(repository.findSessionAnalytics(any(Instant.class), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{d1, 1L, null, null, null}));

        mockMvc.perform(get("/api/v1/observability/aggregates/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[0].sessionCount").value(1))
                .andExpect(jsonPath("$.buckets[0].p50DurationSeconds").value(0.0))
                .andExpect(jsonPath("$.buckets[0].p95DurationSeconds").value(0.0))
                .andExpect(jsonPath("$.buckets[0].avgRunsPerSession").value(0.0));
    }
}
