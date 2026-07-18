package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.RunOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RunsControllerTest {

    private MockMvc mockMvc;

    @Mock private RunRepository runRepository;
    @Mock private AgentOperations agentOperations;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RunsController(runRepository, agentOperations)).build();
    }

    @Test
    void listRuns_NoFilter_DelegatesToFindAllByOrderByCreatedAtDesc() throws Exception {
        AgentRun run = run("r-1", "agent-1", "sess-1", RunStatus.COMPLETED, 1500L, new BigDecimal("0.012"));
        when(runRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("r-1"))
                .andExpect(jsonPath("$.content[0].durationMs").value(1500))
                .andExpect(jsonPath("$.content[0].totalCostUsd").value(0.012));

        verify(runRepository).findAllByOrderByCreatedAtDesc(any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_SessionIdFilter_DelegatesToFindBySession() throws Exception {
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(eq("sess-42"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/runs?sessionId=sess-42"))
                .andExpect(status().isOk());

        verify(runRepository).findBySessionIdOrderByCreatedAtDesc(eq("sess-42"), any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_AgentIdFilter_DelegatesToFindByAgent() throws Exception {
        when(runRepository.findByAgentIdOrderByCreatedAtDesc(eq("agent-7"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/runs?agentId=agent-7"))
                .andExpect(status().isOk());

        verify(runRepository).findByAgentIdOrderByCreatedAtDesc(eq("agent-7"), any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_StatusFilter_DelegatesToFindByStatus() throws Exception {
        when(runRepository.findByStatusOrderByCreatedAtDesc(eq(RunStatus.RUNNING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/runs?status=RUNNING"))
                .andExpect(status().isOk());

        verify(runRepository).findByStatusOrderByCreatedAtDesc(eq(RunStatus.RUNNING), any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_SessionAndAgentBothSet_SessionTakesPrecedence() throws Exception {
        // Documented precedence in the controller Javadoc: sessionId > agentId > status.
        // Pin it so a future refactor can't silently reorder.
        when(runRepository.findBySessionIdOrderByCreatedAtDesc(eq("sess-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/runs?sessionId=sess-1&agentId=agent-1"))
                .andExpect(status().isOk());

        verify(runRepository).findBySessionIdOrderByCreatedAtDesc(eq("sess-1"), any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_BlankSessionId_FallsThroughToAgentFilter() throws Exception {
        // "?sessionId=" (empty string from a UI that always sends the param) must not
        // trigger the sessionId branch — that would return an empty page and confuse
        // the user. Treat empty string as "no filter here."
        when(runRepository.findByAgentIdOrderByCreatedAtDesc(eq("agent-x"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/runs?sessionId=&agentId=agent-x"))
                .andExpect(status().isOk());

        verify(runRepository).findByAgentIdOrderByCreatedAtDesc(eq("agent-x"), any(Pageable.class));
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void listRuns_NullEnrichedFields_SerializeAsNullForPreTelemetryRuns() throws Exception {
        // Runs created before RunTelemetryAccumulator started flushing have null
        // tokens / duration / cost / safety (plan §4.3). UI renders "—" for nulls.
        AgentRun run = run("legacy-run", "agent-1", "sess-1", RunStatus.COMPLETED, null, null);
        when(runRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].durationMs").doesNotExist())
                .andExpect(jsonPath("$.content[0].totalCostUsd").doesNotExist());
    }

    @Test
    void getRun_Found_ReturnsDtoWithMappedFields() throws Exception {
        AgentRun r = run("r-42", "agent-1", "sess-1", RunStatus.COMPLETED, 2500L, new BigDecimal("0.0150"));
        // RunRepository extends both JpaRepository and RunOperations; both expose
        // findById, so a direct mock call is ambiguous. Pin the RunOperations view.
        RunOperations ops = runRepository;
        when(ops.findById("r-42")).thenReturn(Optional.of(r));

        mockMvc.perform(get("/api/v1/runs/r-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("r-42"))
                .andExpect(jsonPath("$.agentId").value("agent-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.durationMs").value(2500))
                .andExpect(jsonPath("$.totalCostUsd").value(0.0150));

        verify(ops).findById("r-42");
        verifyNoMoreInteractions(runRepository);
    }

    @Test
    void getRun_NotFound_Returns404() throws Exception {
        RunOperations ops = runRepository;
        when(ops.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/runs/missing"))
                .andExpect(status().isNotFound());

        verify(ops).findById("missing");
        verifyNoMoreInteractions(runRepository);
    }

    // ── PR #973: user-side cancel ─────────────────────────────────────────

    @Test
    void cancelRun_RunningRow_DelegatesToAgentOperations_AndReturns204() throws Exception {
        AgentRun r = run("r-99", "agent-1", "sess-1", RunStatus.RUNNING, null, null);
        RunOperations ops = runRepository;
        when(ops.findById("r-99")).thenReturn(Optional.of(r));

        mockMvc.perform(post("/api/v1/runs/r-99/cancel"))
                .andExpect(status().isNoContent());

        verify(agentOperations).cancelRun("r-99");
    }

    @Test
    void cancelRun_TerminalRow_SilentNoOp_Returns204_DoesNotDelegate() throws Exception {
        // User-API contract (documented in AgentAdminService.cancelRun): the user may not
        // know the latest row state; an already-terminal row returns 204 without delegating.
        // This intentionally differs from the admin path which surfaces a 400.
        AgentRun r = run("r-done", "agent-1", "sess-1", RunStatus.COMPLETED, 1000L, new BigDecimal("0.001"));
        RunOperations ops = runRepository;
        when(ops.findById("r-done")).thenReturn(Optional.of(r));

        mockMvc.perform(post("/api/v1/runs/r-done/cancel"))
                .andExpect(status().isNoContent());

        verify(agentOperations, never()).cancelRun(any());
    }

    @Test
    void cancelRun_NotFound_Returns404() throws Exception {
        RunOperations ops = runRepository;
        when(ops.findById("absent")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/runs/absent/cancel"))
                .andExpect(status().isNotFound());

        verify(agentOperations, never()).cancelRun(any());
    }

    private static AgentRun run(String id, String agentId, String sessionId, RunStatus status,
                                Long durationMs, BigDecimal cost) {
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setAgentId(agentId);
        r.setSessionId(sessionId);
        r.setStatus(status);
        r.setDurationMs(durationMs);
        r.setTotalCostUsd(cost);
        r.setCreatedAt(LocalDateTime.parse("2026-04-23T12:00:00"));
        r.setUpdatedAt(LocalDateTime.parse("2026-04-23T12:00:01.500"));
        return r;
    }
}
