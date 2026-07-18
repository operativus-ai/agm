package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgentReflectionRepository;
import com.operativus.agentmanager.core.entity.AgentReflectionEntity;
import com.operativus.agentmanager.core.model.AgentReflectionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Read-side HTTP surface for {@code agent_reflections}
 * (observability plan §Phase 1 T002). Powers the per-agent Reflection Log tab (T026)
 * without exposing the full JPA entity graph — controller maps to
 * {@link AgentReflectionResponse} on the way out.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentReflectionController {

    private final AgentReflectionRepository reflectionRepository;

    public AgentReflectionController(AgentReflectionRepository reflectionRepository) {
        this.reflectionRepository = reflectionRepository;
    }

    /**
     * @summary Returns a paginated timeline of reflections authored by {@code agentId},
     *     newest first.
     * @logic
     * - Uses the existing {@link AgentReflectionRepository#findByAgentIdOrderByCreatedAtDesc}
     *   derived query.
     * - Maps each row to {@link AgentReflectionResponse}: the entity's {@code reasoning}
     *   column is the "content" field the UI surfaces — that's the free-form textual trace
     *   of the agent's self-critique.
     * - Empty page returns {@code 200 OK} with {@code content: []}.
     */
    @GetMapping("/{agentId}/reflections")
    public ResponseEntity<Page<AgentReflectionResponse>> getReflections(
            @PathVariable("agentId") String agentId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AgentReflectionResponse> response = reflectionRepository
                .findByAgentIdOrderByCreatedAtDesc(agentId, pageable)
                .map(AgentReflectionController::toDto);
        return ResponseEntity.ok(response);
    }

    private static AgentReflectionResponse toDto(AgentReflectionEntity e) {
        return new AgentReflectionResponse(
                e.getReflectionId(),
                e.getAgentId(),
                e.getReasoning(),
                e.getRunId(),
                e.getCreatedAt());
    }
}
