package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.control.service.MemoryService;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.queue.MemoryOptimizationJobHandler;
import com.operativus.agentmanager.core.model.AddMemoryRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Exposes REST APIs for interacting with the semantic MemoryService.
 * State: Stateless
 * Dependencies: MemoryService
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;

    public MemoryController(MemoryService memoryService,
                            PersistentJobQueueService jobQueueService,
                            ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
    }

    /**
     * @summary Manually injects a new long-term semantic memory payload.
     * @logic
     * - Validates presence of the "content" field.
     * - Forwards raw content to MemoryService.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> addMemory(@RequestBody @Valid AddMemoryRequest request) {
        log.info("Adding new memory entry");
        memoryService.addMemory(request.content());
        return ResponseEntity.ok(Map.of("message", "Memory saved"));
    }

    /**
     * @summary Performs a semantic vector search across the caller's Memory store.
     * @logic
     * - Tenant scope is enforced at the MemoryService layer.
     * - For ROLE_USER callers, the search is also user-scoped — the vector filter
     *   adds a {@code userId} predicate so semantic matches from other users in the
     *   same org never surface. Pre-fix the search was org-scoped only: any user
     *   could craft a query and surface another user's memories within the org.
     * - ROLE_ADMIN bypasses the user filter (cross-user view within their org).
     */
    @GetMapping
    public List<String> searchMemories(@RequestParam String query) {
        log.debug("Searching memories for query: {}", query);
        String permittedUserId = isCurrentUserAdmin() ? null : currentUserId();
        return memoryService.searchMemories(query, permittedUserId);
    }

    /**
     * @summary Removes specific memory payloads by a list of document IDs.
     * @logic
     * - Streams IDs to the underlying MemoryService for deletion.
     */
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteMemories(@RequestBody List<String> ids) {
        log.info("Deleting memory entries: {}", ids);
        memoryService.deleteMemories(ids);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * @summary Triggers an async background optimization task to clean, deduplicate, and summarize memory objects.
     * @logic
     * - Falls back to a "default-user" if userId parameter is unsupplied.
     * - Instructs MemoryService to begin an LLM-driven optimization sweep.
     */
    @PostMapping("/optimize")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> optimizeMemories(@RequestParam(required = false) String userId) throws Exception {
        String uid = com.operativus.agentmanager.control.config.SecurityContextUtils.resolveUserId(userId);
        log.info("Triggering memory optimization for user: {}", uid);
        String payload = objectMapper.writeValueAsString(new MemoryOptimizationJobHandler.Payload(uid));
        var job = jobQueueService.enqueue(MemoryOptimizationJobHandler.JOB_TYPE, null, payload, null, "MEMORY_OPT_" + uid);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }
    
    /**
     * @summary Retrieves meta-statistics about the stored memory context (Token usage, count).
     * @logic
     * - Tenant scope is enforced at the MemoryService layer.
     * - For ROLE_USER callers, the {@code userId} query param is IGNORED and the stats
     *   are scoped to the caller's own user id. Pre-fix this used
     *   {@code SecurityContextUtils.resolveUserId(userId)} which trusts the explicit
     *   param verbatim — a regular tenant user could enumerate any other user's
     *   memory stats within their org by setting {@code ?userId=other-user}.
     * - ROLE_ADMIN retains full filter access.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getMemoryStats(@RequestParam(required = false) String userId) {
        return ResponseEntity.ok(memoryService.getMemoryStats(resolvePermittedUserId(userId)));
    }

    /**
     * @summary Retrieves discovered High-Level themes/topics derived from raw Memories.
     * @logic
     * - Same tenant + user scoping as {@link #getMemoryStats}: ROLE_USER callers see
     *   only their own topics regardless of the {@code ?userId=} query param value;
     *   ROLE_ADMIN can read any user in their org.
     */
    @GetMapping("/topics")
    public List<String> getMemoryTopics(@RequestParam(required = false) String userId) {
        return memoryService.getMemoryTopics(resolvePermittedUserId(userId));
    }

    /**
     * Resolves the userId filter to apply: if the caller is admin, honor the explicit
     * param (or fall through to the authenticated principal when blank); if not admin,
     * forcibly override with the principal's own id so the caller cannot enumerate
     * another user's memory within their org. Mirrors the G5/G6 pattern from
     * SessionController.
     */
    private static String resolvePermittedUserId(String explicitUserId) {
        if (isCurrentUserAdmin()) {
            return com.operativus.agentmanager.control.config.SecurityContextUtils.resolveUserId(explicitUserId);
        }
        String boundUserId = currentUserId();
        return boundUserId != null
                ? boundUserId
                : com.operativus.agentmanager.control.config.SecurityContextUtils.resolveUserId(null);
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : ud.getUsername();
        }
        return null;
    }

    private static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority())) return true;
        }
        return false;
    }

}
