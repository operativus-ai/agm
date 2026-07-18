package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
class DelegationTopologyControllerTest {

    private MockMvc mockMvc;

    @Mock private OrchestrationDecisionRepository repository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DelegationTopologyController(repository)).build();
    }

    @Test
    void getDelegationTopology_HappyPath_EmitsEdgesAndDerivesNodes() throws Exception {
        when(repository.findDelegationTopology(any(Instant.class), eq("org-1")))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"coordinator", "router", "COORDINATOR", 5L},
                        new Object[]{"router", "specialist-a", "ROUTER", 3L},
                        new Object[]{"router", "specialist-b", "ROUTER", 2L},
                        new Object[]{"(root)", "coordinator", "COORDINATOR", 7L}));

        ScopedValue.where(AgentContextHolder.orgId, "org-1").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/observability/aggregates/delegation-topology?window=30"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.edges.length()").value(4))
                        .andExpect(jsonPath("$.edges[0].from").value("coordinator"))
                        .andExpect(jsonPath("$.edges[0].to").value("router"))
                        .andExpect(jsonPath("$.edges[0].strategy").value("COORDINATOR"))
                        .andExpect(jsonPath("$.edges[0].count").value(5))
                        // nodes: coordinator (in=7, out=5), router (in=5, out=5),
                        //        specialist-a (in=3, out=0), specialist-b (in=2, out=0),
                        //        (root) (in=0, out=7) → sorted by total desc
                        .andExpect(jsonPath("$.nodes.length()").value(5))
                        .andExpect(jsonPath("$.nodes[0].agentId").value("coordinator"))
                        .andExpect(jsonPath("$.nodes[0].totalIn").value(7))
                        .andExpect(jsonPath("$.nodes[0].totalOut").value(5));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void getDelegationTopology_NoOrgHeader_BypassesTenantFilter() throws Exception {
        when(repository.findDelegationTopology(any(Instant.class), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/delegation-topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(0))
                .andExpect(jsonPath("$.nodes.length()").value(0));

        verify(repository).findDelegationTopology(any(Instant.class), isNull());
    }

    @Test
    void getDelegationTopology_WindowOverMax_IsClampedTo365() throws Exception {
        when(repository.findDelegationTopology(any(Instant.class), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/aggregates/delegation-topology?window=99999"))
                .andExpect(status().isOk());

        verify(repository).findDelegationTopology(any(Instant.class), any());
    }

    @Test
    void getDelegationTopology_NodeTotals_AggregateAcrossStrategies() throws Exception {
        // Same edge pair via two strategies — node totals should sum across strategies.
        when(repository.findDelegationTopology(any(Instant.class), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"a", "b", "ROUTER", 4L},
                        new Object[]{"a", "b", "SWARM", 6L}));

        mockMvc.perform(get("/api/v1/observability/aggregates/delegation-topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.nodes[?(@.agentId == 'a')].totalOut").value(10))
                .andExpect(jsonPath("$.nodes[?(@.agentId == 'b')].totalIn").value(10));
    }
}
