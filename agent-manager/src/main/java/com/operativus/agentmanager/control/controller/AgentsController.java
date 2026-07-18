package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.security.MediaUrlFetcher;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.queue.KnowledgeIngestionJobHandler;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.registry.RunOperations;

import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.EventType;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.security.SsrfGuard;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;


/**
 * Domain Responsibility: Exposes REST APIs for Agent lifecycle management, prompt execution, and streaming responses.
 * State: Stateless
 * Dependencies: AgentRegistry, AgentService
 */
@RestController
@RequestMapping("/api/agents")
public class AgentsController {

    private static final Logger log = LoggerFactory.getLogger(AgentsController.class);

    private final AgentRegistry agentRegistry;
    private final RunOperations runOperations;
    private final com.operativus.agentmanager.core.registry.AgentOperations agentOperations;
    private final PersistentJobQueueService jobQueueService;
    private final ObjectMapper objectMapper;
    private final MediaUrlFetcher mediaUrlFetcher;

    public AgentsController(AgentRegistry agentRegistry, RunOperations runOperations,
                            com.operativus.agentmanager.core.registry.AgentOperations agentOperations,
                            PersistentJobQueueService jobQueueService, ObjectMapper objectMapper,
                            MediaUrlFetcher mediaUrlFetcher) {
        this.agentRegistry = agentRegistry;
        this.runOperations = runOperations;
        this.agentOperations = agentOperations;
        this.jobQueueService = jobQueueService;
        this.objectMapper = objectMapper;
        this.mediaUrlFetcher = mediaUrlFetcher;
    }

    // --- Agent Management ---

    /**
     * @summary Retrieves all defined agents from the registry.
     * @logic
     * - Delegates to AgentRegistry to fetch all AgentDefinition objects.
     */
    @GetMapping
    public List<AgentDefinition> listAgents(@RequestParam(defaultValue = "false", required = false) boolean includeInactive) {
        return agentRegistry.findAll(includeInactive,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
    }

    /**
     * @summary Retrieves a specific agent definition by its ID.
     * @logic
     * - Looks up the AgentDefinition by agentId in the registry.
     * - Returns 200 OK if found, or 404 Not Found otherwise.
     */
    @GetMapping("/{agentId}")
    public ResponseEntity<AgentDefinition> getAgent(@PathVariable String agentId) {
        AgentDefinition def = agentRegistry.findById(agentId,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
        return def != null ? ResponseEntity.ok(def) : ResponseEntity.notFound().build();
    }
    
    /**
     * @summary Triggers an asynchronous knowledge loading process for a specific agent.
     * @logic
     * - Logs the incoming load request.
     * - Calls the AgentService to ingest knowledge sources defined for this agent.
     * - Returns 200 OK immediately.
     */
    @PostMapping("/{agentId}/knowledge/load")
    public ResponseEntity<Map<String, String>> loadKnowledge(@PathVariable String agentId) throws Exception {
        log.info("API request to load knowledge for agent: {}", agentId);
        String payload = objectMapper.writeValueAsString(new KnowledgeIngestionJobHandler.Payload(agentId));
        var job = jobQueueService.enqueue(KnowledgeIngestionJobHandler.JOB_TYPE, agentId, payload, null, null);
        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    /**
     * @summary Clears the Spring Cache for agents, forcing a database reload on the next fetch.
     * @logic
     * - Uses `@CacheEvict` to purge the "agents" and "allAgents" caches.
     * - Returns a success message.
     */
    @PostMapping("/cache/clear")
    @org.springframework.cache.annotation.CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public ResponseEntity<String> clearAgentCache() {
        return ResponseEntity.ok("Agent Registry cache cleared. Configurations will be reloaded from the database on the next request.");
    }

    // --- Runs & Execution ---

    record MediaInput(String type, String data) {}
    record RunRequest(String message, String sessionId, String userId, String orgId, Boolean stream, List<MediaInput> media, Boolean generateFollowups, RunOptions options) {}

    /**
     * @summary Executes an agent run synchronously and returns the final text response.
     * @logic
     * - Resolves or generates a unique session ID.
     * - Logs the run request details.
     * - Calls the AgentService to execute the prompt with optional media.
     */
    @PostMapping("/{agentId}/runs")

    public RunResponse run(@PathVariable String agentId, @RequestBody RunRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        String orgId = resolveCallerOrgId(request);
        String userId = resolveCallerUserId(request);
        log.info("Received run request for agentId: {}, sessionId: {}", agentId, sessionId);
        Boolean followups = request.generateFollowups() != null ? request.generateFollowups() : false;
        return agentOperations.run(agentId, request.message(), mapMedia(request.media(), mediaUrlFetcher), sessionId, userId, orgId, followups, request.options());
    }

    /**
     * @summary Executes an agent run and streams tokens back as Server-Sent Events (SSE).
     * @logic
     * - Resolves session ID and logs the request.
     * - Calls the AgentService to establish a reactive Flux stream of AgentStreamEvents.
     * - Returns the Flux with MediaType TEXT_EVENT_STREAM_VALUE.
     */
    @PostMapping(value = "/{agentId}/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)

    public Flux<AgentStreamEvent> stream(@PathVariable String agentId, @RequestBody RunRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        String orgId = resolveCallerOrgId(request);
        String userId = resolveCallerUserId(request);
        log.info("Received streaming run request for agentId: {}, sessionId: {}", agentId, sessionId);
        Boolean followups = request.generateFollowups() != null ? request.generateFollowups() : false;
        return agentOperations.stream(agentId, request.message(), mapMedia(request.media(), mediaUrlFetcher), sessionId, userId, orgId, followups, request.options())
                .onErrorResume(ex -> {
                    // A text/event-stream response must never let an exception escape to the servlet
                    // error dispatch: BasicErrorController would try to write its error-attributes Map
                    // with Content-Type text/event-stream and fail with HttpMessageNotWritableException,
                    // masking the real cause (e.g. an invalid provider key). Convert any error into a
                    // terminal ERROR event so the client renders the reason and the stream closes cleanly.
                    log.warn("Streaming run failed for agentId {}: {}", agentId, ex.toString());
                    String detail = (ex.getMessage() != null && !ex.getMessage().isBlank())
                            ? ex.getMessage() : ex.getClass().getSimpleName();
                    return Flux.just(new AgentStreamEvent(EventType.ERROR, detail, System.currentTimeMillis()));
                });
    }
    
    /**
     * @summary Submits an agent run to execute asynchronously in the background.
     * @logic
     * - Resolves session ID.
     * - Calls the AgentService to queue or spawn a virtual thread for the run.
     * - Immediately returns the generated run_id and a QUEUED status.
     */
    @PostMapping("/{agentId}/runs/background")
    public com.operativus.agentmanager.core.model.AgentRunStatusDTO runBackground(@PathVariable String agentId, @RequestBody RunRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        String orgId = resolveCallerOrgId(request);
        String userId = resolveCallerUserId(request);
        Boolean followups = request.generateFollowups() != null ? request.generateFollowups() : false;
        String runId = agentOperations.runInBackground(agentId, request.message(), mapMedia(request.media(), mediaUrlFetcher), sessionId, userId, orgId, followups, request.options());
        return new com.operativus.agentmanager.core.model.AgentRunStatusDTO(runId, "QUEUED");
    }

    /**
     * Resolves the run's tenant from the JWT-bound {@link AgentContextHolder#getOrgId()}.
     * Falls back to the request body's {@code orgId} only when the principal context is
     * unbound — that is, super-admin / unauthenticated paths per the §28 RBAC pattern.
     *
     * <p>Pre-fix the controller passed {@code request.orgId()} unconditionally. Any caller
     * could attribute an agent run to any orgId by setting the body field — a cross-tenant
     * spoof on the audit trail, budget policy lookup, and downstream tenant scoping.
     * Post-fix, regular tenant users get their JWT-bound orgId and the body field is
     * ignored for them.
     */
    private static String resolveCallerOrgId(RunRequest request) {
        String bound = AgentContextHolder.getOrgId();
        return (bound != null && !bound.isBlank()) ? bound : request.orgId();
    }

    /**
     * Resolves the caller's user identity from the Spring Security {@link Authentication}.
     * Returns the principal's username (or {@code UserDetailsImpl.getId().toString()} when
     * available) and falls back to the request body's {@code userId} only when no
     * authentication is bound. Same rationale as {@link #resolveCallerOrgId} — closes the
     * spoof where any caller could attribute runs to any user via the body field.
     */
    private static String resolveCallerUserId(RunRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getId() != null ? ud.getId().toString() : ud.getUsername();
        }
        return request.userId();
    }

    /**
     * @summary Fetches status for multiple background runs in a single request.
     * @logic
     * - Resolves all provided runIds in one repository query (via RunOperations.findByIdIn).
     * - Returns a list (not a map) so clients can handle partial results — runIds with no
     *   persisted row are simply absent from the response rather than returning 404.
     * - The agentId path parameter is a routing hint; the actual filter is runId-only,
     *   matching the behaviour of the single-run status endpoint.
     * - Unlocks the ActiveRunsTracker N+1 polling loop: 10 concurrent runs × 3s cadence
     *   drops from ~200 req/min to ~20 req/min (single batched call per tick).
     */
    @GetMapping("/{agentId}/runs/status")
    public List<com.operativus.agentmanager.core.entity.AgentRun> getRunStatusBatch(
            @PathVariable String agentId,
            @RequestParam List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        if (runIds.size() > 100) {
            throw new IllegalArgumentException("Maximum 100 runIds per request (received " + runIds.size() + ")");
        }
        return runOperations.findByIdIn(runIds);
    }
    
    /**
     * @summary Cancels or terminates an active in-flight agent run.
     * @logic
     * - Issues a cancellation signal to the AgentService for the given runId.
     * - Returns an HTTP 204 No Content response.
     */
    @DeleteMapping("/{agentId}/runs/{runId}")

    public ResponseEntity<Void> cancelRun(@PathVariable String agentId, @PathVariable String runId) {
        agentOperations.cancelRun(runId);
        return ResponseEntity.noContent().build();
    }

    // --- Helpers ---

    private String resolveSessionId(String sessionId) {
        return sessionId != null ? sessionId : UUID.randomUUID().toString();
    }

    // Package-private static (not private instance) so MediaMappingSsrfTest can invoke
    // directly without spinning up the controller with all its service dependencies.
    // Pure mapping function — fetcher threaded through so reject-before-fetch tests
    // (SSRF rejection, base64-inline) can pass null.
    static List<org.springframework.ai.content.Media> mapMedia(List<MediaInput> inputs,
                                                                MediaUrlFetcher fetcher) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        return inputs.stream()
            .map(input -> {
                try {
                    org.springframework.util.MimeType mimeType = org.springframework.util.MimeTypeUtils.parseMimeType(input.type());
                    if (input.data().startsWith("http")) {
                        // SSRF guard (RFC-1918 / loopback / 169.254 / 0.0.0.0) — must pass
                        // before any network I/O. Mirrors UniversalDispatchController.mapMedia.
                        String ssrfError = SsrfGuard.validate(input.data(), false);
                        if (ssrfError != null) {
                            throw new IllegalArgumentException(
                                    "Media URL rejected by SSRF guard: " + ssrfError);
                        }
                        // DoS guard — MediaUrlFetcher enforces timeouts + size cap, then we
                        // hand bytes to Spring AI (not a UrlResource), bypassing its
                        // unbounded eager-fetch path on URLs.
                        byte[] bytes = fetcher.fetch(input.data());
                        return new org.springframework.ai.content.Media(mimeType,
                                new org.springframework.core.io.ByteArrayResource(bytes));
                    } else {
                        byte[] bytes = java.util.Base64.getDecoder().decode(input.data());
                        return new org.springframework.ai.content.Media(mimeType, new org.springframework.core.io.ByteArrayResource(bytes));
                    }
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (MediaUrlFetcher.MediaFetchException e) {
                    throw new IllegalArgumentException("Media URL fetch refused: " + e.getMessage());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to process media input: " + e.getMessage(), e);
                }
            })
            .collect(java.util.stream.Collectors.toList());
    }
}
