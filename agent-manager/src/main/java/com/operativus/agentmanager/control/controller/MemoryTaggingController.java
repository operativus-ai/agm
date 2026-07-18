package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Domain Responsibility: Memory tagging, timeline, and export over {@code agentic_memory}.
 *     Access is scoped two ways: (1) tenant — every query is filtered by the caller's org
 *     ({@link AgentContextHolder}); rows carry an {@code org_id} and the content is sensitive,
 *     and when no org resolves the controller refuses. (2) user — agentic memory is personal,
 *     so a caller may only reach their OWN userId's memories; a per-org ADMIN (RoleHierarchy:
 *     SUPER_ADMIN &gt; ADMIN) may reach any user within their org for support/ops. Cross-user
 *     access by a non-admin is 403. Tenant contract is pinned by
 *     {@code MemorySearchTenantIsolationRuntimeTest} / {@code MemoryTaggingTenantIsolationRuntimeTest}.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryTaggingController {

    private final AgenticMemoryRepository memoryRepository;

    public MemoryTaggingController(AgenticMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /**
     * Returns a timeline view of the user's memories within the caller's org, newest first.
     * Non-admin callers may only request their own userId.
     */
    @GetMapping("/timeline/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getTimeline(@PathVariable String userId) {
        if (!mayAccessUser(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String orgId = callerOrgId();
        if (orgId == null) {
            return ResponseEntity.ok(List.of());
        }
        List<Map<String, Object>> timeline = memoryRepository.findByUserIdAndOrgId(userId, orgId).stream()
                .sorted(Comparator.comparing(AgenticMemoryEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", m.getMemoryId());
                    entry.put("memory", m.getMemory());
                    entry.put("topics", m.getTopics() != null ? m.getTopics() : List.of());
                    entry.put("tier", m.getMemoryTier());
                    entry.put("agentId", m.getAgentId());
                    entry.put("createdAt", m.getCreatedAt());
                    return entry;
                })
                .toList();

        return ResponseEntity.ok(timeline);
    }

    /**
     * Tags a memory with one or more topics. Cross-org ids return 404; another user's memory
     * returns 403 for a non-admin caller.
     */
    @PutMapping("/{memoryId}/tags")
    public ResponseEntity<Void> tagMemory(
            @PathVariable UUID memoryId,
            @RequestBody List<String> tags) {
        String orgId = callerOrgId();
        AgenticMemoryEntity memory = (orgId == null ? Optional.<AgenticMemoryEntity>empty()
                : memoryRepository.findByMemoryIdAndOrgId(memoryId, orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Memory", memoryId.toString()));
        if (!mayAccessUser(memory.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        memory.setTopics(tags);
        memoryRepository.save(memory);
        return ResponseEntity.noContent().build();
    }

    /**
     * Exports the user's memories within the caller's org as JSON. Non-admin callers may only
     * request their own userId.
     */
    @GetMapping("/export/{userId}")
    public ResponseEntity<List<AgenticMemoryEntity>> exportMemories(@PathVariable String userId) {
        if (!mayAccessUser(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String orgId = callerOrgId();
        if (orgId == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(memoryRepository.findByUserIdAndOrgId(userId, orgId));
    }

    /** A caller may reach their own memories; a per-org ADMIN may reach any user in their org. */
    private static boolean mayAccessUser(String targetUserId) {
        if (callerIsAdmin()) {
            return true;
        }
        String caller = AgentContextHolder.getUserId();
        return caller != null && caller.equals(targetUserId);
    }

    private static boolean callerIsAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : null;
    }
}
