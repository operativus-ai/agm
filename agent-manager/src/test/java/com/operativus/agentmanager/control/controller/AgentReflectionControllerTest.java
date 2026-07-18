package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgentReflectionRepository;
import com.operativus.agentmanager.core.entity.AgentReflectionEntity;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentReflectionControllerTest {

    private MockMvc mockMvc;

    @Mock private AgentReflectionRepository repository;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentReflectionController(repository)).build();
    }

    @Test
    void getReflections_ReturnsPagedDtoNewestFirst() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        AgentReflectionEntity r1 = reflection(id1, "agent-1", "run-A", "first reflection", LocalDateTime.parse("2026-04-23T12:00:00"));
        AgentReflectionEntity r2 = reflection(id2, "agent-1", "run-B", "second reflection", LocalDateTime.parse("2026-04-23T11:00:00"));
        Page<AgentReflectionEntity> page = new PageImpl<>(List.of(r1, r2), PageRequest.of(0, 20), 2);
        when(repository.findByAgentIdOrderByCreatedAtDesc(eq("agent-1"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/agents/agent-1/reflections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(id1.toString()))
                .andExpect(jsonPath("$.content[0].agentId").value("agent-1"))
                .andExpect(jsonPath("$.content[0].content").value("first reflection"))
                .andExpect(jsonPath("$.content[0].sourceRunId").value("run-A"))
                .andExpect(jsonPath("$.content[1].sourceRunId").value("run-B"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getReflections_UsesDefaultPageAndSize() throws Exception {
        when(repository.findByAgentIdOrderByCreatedAtDesc(eq("agent-2"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/agents/agent-2/reflections")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByAgentIdOrderByCreatedAtDesc(eq("agent-2"), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void getReflections_RespectsPageAndSizeParams() throws Exception {
        when(repository.findByAgentIdOrderByCreatedAtDesc(eq("agent-3"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        mockMvc.perform(get("/api/v1/agents/agent-3/reflections?page=2&size=50")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByAgentIdOrderByCreatedAtDesc(eq("agent-3"), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void getReflections_EmptyPage_ReturnsOkWithEmptyContent() throws Exception {
        when(repository.findByAgentIdOrderByCreatedAtDesc(eq("no-such-agent"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/agents/no-such-agent/reflections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getReflections_NullReasoning_SerializesAsNull() throws Exception {
        // Reflection rows with correctionApplied=false sometimes carry a null
        // reasoning column (only outputSummary is set). Make sure we don't NPE
        // and the DTO surfaces `content: null` for UI null-safe rendering.
        UUID id = UUID.randomUUID();
        AgentReflectionEntity r = reflection(id, "agent-4", "run-X", null, LocalDateTime.now());
        when(repository.findByAgentIdOrderByCreatedAtDesc(eq("agent-4"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/agents/agent-4/reflections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").doesNotExist());
        // jsonPath .doesNotExist matches Jackson's default of omitting nulls when configured;
        // equally matches a null-serialized field with .value(null) on some Jackson versions.
    }

    private static AgentReflectionEntity reflection(UUID id, String agentId, String runId,
                                                    String reasoning, LocalDateTime createdAt) {
        AgentReflectionEntity e = new AgentReflectionEntity();
        e.setReflectionId(id);
        e.setAgentId(agentId);
        e.setSessionId("sess-1");
        e.setRunId(runId);
        e.setStepIndex(0);
        e.setPhase("PLAN");
        e.setReasoning(reasoning);
        e.setCreatedAt(createdAt);
        return e;
    }
}
