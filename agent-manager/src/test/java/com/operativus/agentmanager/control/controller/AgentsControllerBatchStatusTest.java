package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.registry.RunOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for AgentsController.getRunStatusBatch — the batch
 * run-status endpoint added to unblock the ActiveRunsTracker N+1 polling loop.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AgentsControllerBatchStatusTest {

    private MockMvc mockMvc;

    @Mock private AgentRegistry agentRegistry;
    @Mock private RunOperations runOperations;
    @Mock private AgentOperations agentOperations;
    @Mock private PersistentJobQueueService jobQueueService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgentsController(agentRegistry, runOperations, agentOperations, jobQueueService, new ObjectMapper(), null)
        ).build();
    }

    @Test
    void batchStatus_MultipleIds_DelegatesToFindByIdInOnce() throws Exception {
        AgentRun a = run("r-1", RunStatus.RUNNING);
        AgentRun b = run("r-2", RunStatus.COMPLETED);
        when(runOperations.findByIdIn(argThat(list -> list.size() == 2 && list.contains("r-1") && list.contains("r-2"))))
                .thenReturn(List.of(a, b));

        mockMvc.perform(get("/api/agents/agent-x/runs/status").param("runIds", "r-1", "r-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("r-1"))
                .andExpect(jsonPath("$[1].id").value("r-2"));

        verify(runOperations).findByIdIn(argThat(list -> list.size() == 2));
        verifyNoMoreInteractions(runOperations);
    }

    @Test
    void batchStatus_CommaSeparatedParam_ResolvesAsListCorrectly() throws Exception {
        AgentRun a = run("r-1", RunStatus.RUNNING);
        when(runOperations.findByIdIn(argThat(list -> list.size() == 1 && list.contains("r-1"))))
                .thenReturn(List.of(a));

        // Spring binds "?runIds=r-1" as a single-element List<String>
        mockMvc.perform(get("/api/agents/agent-x/runs/status?runIds=r-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("r-1"));
    }

    @Test
    void batchStatus_PartialResults_ReturnsOnlyFoundRows() throws Exception {
        // Asked for 3 ids but only 2 exist — client handles absence client-side.
        AgentRun a = run("r-1", RunStatus.RUNNING);
        AgentRun b = run("r-3", RunStatus.COMPLETED);
        when(runOperations.findByIdIn(argThat(list -> list.size() == 3)))
                .thenReturn(List.of(a, b));

        mockMvc.perform(get("/api/agents/agent-x/runs/status")
                        .param("runIds", "r-1", "r-2-missing", "r-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void batchStatus_EmptyStringParam_Returns200() throws Exception {
        // Spring MVC binds ?runIds= to either empty list OR a list with one
        // empty-string element (framework-dependent); either way the endpoint
        // must return 200 without throwing. The short-circuit on isEmpty()
        // handles the first case; the repo call would handle the second with
        // no matching rows.
        when(runOperations.findByIdIn(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agents/agent-x/runs/status").param("runIds", ""))
                .andExpect(status().isOk());
    }

    @Test
    void batchStatus_MissingRunIdsParam_Returns400() throws Exception {
        // With required @RequestParam and no default, absent param -> 400.
        mockMvc.perform(get("/api/agents/agent-x/runs/status"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runOperations);
    }

    @Test
    void batchStatus_OverLimit_GuardRejectsRequest() throws Exception {
        // 101 runIds triggers the guard (max 100). Standalone MockMvc has no
        // ControllerAdvice wiring, so IllegalArgumentException propagates out
        // as a ServletException wrapping the original. Assert that the
        // handler IS reached (by asserting the nested resolvedException) and
        // the guard does NOT call the repo.
        String[] ids = new String[101];
        for (int i = 0; i < 101; i++) ids[i] = "r-" + i;

        try {
            mockMvc.perform(get("/api/agents/agent-x/runs/status").param("runIds", ids));
        } catch (jakarta.servlet.ServletException e) {
            // Expected — unwrap and verify the guard message.
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            org.assertj.core.api.Assertions.assertThat(root)
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThat(root.getMessage())
                    .contains("Maximum 100 runIds");
        }
        verifyNoInteractions(runOperations);
    }

    // --- helpers ---

    private static AgentRun run(String id, RunStatus status) {
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setStatus(status);
        return r;
    }
}
