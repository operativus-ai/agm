package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.security.CallerContext;
import com.operativus.agentmanager.control.a2a.A2ACardResolver;
import com.operativus.agentmanager.control.a2a.A2ATaskExecutor;
import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.control.a2a.model.AgentCard;
import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.a2a.model.PeerCancellationNotify;
import com.operativus.agentmanager.control.a2a.model.RemoteAgentRegistration;
import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.control.repository.A2aTaskEventRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.A2aTaskEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: REST entry points for the A2A (Agent-to-Agent) interoperability plane.
 *
 * Gap 2.1 — Discovery & Routing:
 *   {@code GET  /api/v1/a2a/cards}           List all local agent capability cards.
 *   {@code GET  /api/v1/a2a/cards/{agentId}} Publish a single agent's capability card.
 *   {@code POST /api/v1/a2a/peers}           Register a remote A2A peer agent.
 *   {@code GET  /api/v1/a2a/peers}           List all registered remote peer registrations.
 *   {@code DELETE /api/v1/a2a/peers/{alias}} Deregister a remote peer by alias.
 *
 * Gap 2.2 — Task Execution Sockets:
 *   {@code POST /api/v1/a2a/tasks}           Submit an inbound A2A task (returns SSE stream).
 *   {@code DELETE /api/v1/a2a/tasks/{taskId}} Cancel an active inbound A2A task.
 *
 * Authentication:
 *   All {@code /api/v1/a2a/**} routes accept the {@code X-A2A-Api-Key} header validated
 *   by {@code ApiKeyAuthenticationFilter} (Gap 2.3). Standard JWT Bearer auth is also
 *   accepted so internal admin tooling can call these endpoints.
 *
 * Architecture:
 * - Constructor injection only.
 * - No ApplicationEventPublisher.
 * - TaskId defaults to a UUID if the caller does not supply one (idempotent re-submission
 *   is the caller's responsibility).
 */
@RestController
@RequestMapping("/api/v1/a2a")
public class A2AController {

    private static final Logger log = LoggerFactory.getLogger(A2AController.class);

    private final A2ACardResolver cardResolver;
    private final A2ATaskExecutor taskExecutor;
    private final A2aTaskEventRepository taskEventRepository;
    private final A2aRemoteAgentRepository peerRepository;

    public A2AController(A2ACardResolver cardResolver,
                         A2ATaskExecutor taskExecutor,
                         A2aTaskEventRepository taskEventRepository,
                         A2aRemoteAgentRepository peerRepository) {
        this.cardResolver        = cardResolver;
        this.taskExecutor        = taskExecutor;
        this.taskEventRepository = taskEventRepository;
        this.peerRepository      = peerRepository;
    }

    // -----------------------------------------------------------------------
    // Gap 2.1 — Discovery & Routing
    // -----------------------------------------------------------------------

    /**
     * @summary Returns capability cards for all active local agents.
     * @logic Delegates to {@code A2ACardResolver#listLocalCards()} which reads the
     *        live {@code AgentRegistry}. Remote peers call this to discover what
     *        AGM can do before delegating tasks.
     */
    @GetMapping("/cards")
    public ResponseEntity<List<AgentCard>> listCards() {
        List<AgentCard> cards = cardResolver.listLocalCards();
        log.debug("A2A /cards listing {} local agents", cards.size());
        return ResponseEntity.ok(cards);
    }

    /**
     * @summary Returns the capability card for a specific local agent.
     * @logic Returns 404 if the agent is not found or not active.
     */
    @GetMapping("/cards/{agentId}")
    public ResponseEntity<AgentCard> getCard(@PathVariable String agentId) {
        return cardResolver.publishLocalCard(agentId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * @summary Registers a remote A2A peer agent with this AGM instance.
     * @logic The registration is stored in-memory by {@code A2ACardResolver}.
     *        A production deployment would also persist to the {@code a2a_remote_agents} DB table.
     *        Basic SSRF guards reject RFC-1918 / loopback / link-local base URLs.
     */
    @PostMapping("/peers")
    public ResponseEntity<RemoteAgentRegistration> registerPeer(
            @RequestBody RegisterPeerRequest request) {
        String orgId = AgentContextHolder.getOrgId();
        if (!StringUtils.hasText(request.remoteAgentId())
                || !StringUtils.hasText(request.baseUrl())
                || !StringUtils.hasText(request.alias())) {
            return ResponseEntity.badRequest().build();
        }

        String ssrfError = validateBaseUrl(request.baseUrl());
        if (ssrfError != null) {
            log.warn("A2A peer registration rejected — SSRF guard: {} (url={})", ssrfError, request.baseUrl());
            return ResponseEntity.badRequest().build();
        }

        // §22.5 follow-on duplicate-peer guard. If a row with the same (orgId, remoteAgentId)
        // already exists under a DIFFERENT alias, reject with 409 Conflict — accepting would
        // make PeerCancellationDispatcher.findByRemoteAgentId non-deterministic. The
        // SAME-alias case falls through to the existing upsert path in
        // A2ACardResolver.registerRemoteAgent (re-register with new baseUrl/apiKey is allowed).
        // Defense-in-depth: UNIQUE(org_id, remote_agent_id) (changeset 064) catches anything
        // that races past this check.
        var existing = peerRepository.findByRemoteAgentIdAndOrgId(request.remoteAgentId(), orgId);
        if (existing.isPresent() && !existing.get().getAlias().equals(request.alias())) {
            log.warn("A2A peer registration rejected — duplicate remoteAgentId={} under different alias (existing={}, requested={}) orgId={}",
                    request.remoteAgentId(), existing.get().getAlias(), request.alias(), orgId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        RemoteAgentRegistration registration = new RemoteAgentRegistration(
            UUID.randomUUID().toString(),
            request.remoteAgentId(),
            request.baseUrl(),
            request.alias(),
            request.apiKey(),
            null,
            Instant.now(),
            null
        );
        cardResolver.registerRemoteAgent(registration, orgId);
        log.info("A2A peer registered: alias={} remoteId={} orgId={}", request.alias(), request.remoteAgentId(), orgId);
        return ResponseEntity.ok(registration);
    }

    /**
     * @summary Validates that a peer base URL does not target internal/private network ranges.
     * @logic Delegates to the shared
     *     {@link com.operativus.agentmanager.core.security.SsrfGuard#validate} so all
     *     outbound-URL callers in the codebase share one SSRF policy. Production peer
     *     registration always uses strict mode (allowLoopback=false). The full
     *     127.0.0.0/8 loopback range, IPv4-mapped IPv6 loopback, decimal-encoded IPv4,
     *     and cloud-metadata 169.254/16 are all caught — pinned by {@code A2aSsrfGuardRuntimeTest}.
     */
    private String validateBaseUrl(String rawUrl) {
        return com.operativus.agentmanager.core.security.SsrfGuard.validate(rawUrl, false);
    }

    /**
     * @summary Lists remote peer agents registered under the caller's tenant.
     * @logic §22.7 — scopes by {@code X-Org-Id}. A missing header matches legacy
     *        peer rows that predate multi-tenant scoping.
     */
    @GetMapping("/peers")
    public ResponseEntity<List<RemoteAgentRegistration>> listPeers() {
        return ResponseEntity.ok(cardResolver.listRemoteRegistrations(AgentContextHolder.getOrgId()));
    }

    /**
     * @summary Deregisters a remote peer by alias within the caller's tenant.
     * @logic §22.7 — a caller in org A cannot drop a peer registered under org B.
     */
    @DeleteMapping("/peers/{alias}")
    public ResponseEntity<Void> deregisterPeer(@PathVariable String alias) {
        boolean removed = cardResolver.deregisterRemoteAgent(alias, AgentContextHolder.getOrgId());
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // -----------------------------------------------------------------------
    // Gap 2.2 — Task Execution Sockets & Stream Emitters
    // -----------------------------------------------------------------------

    /**
     * @summary Accepts an inbound A2A task and streams lifecycle events back to the caller.
     * @logic
     * 1. Assigns a UUID task ID if the request does not carry one.
     * 2. Delegates execution to {@code A2ATaskExecutor#submitTask}, which spawns a virtual
     *    thread and returns an {@link SseEmitter} immediately.
     * 3. The caller receives real-time lifecycle events:
     *    {@code SUBMITTED → WORKING → COMPLETED | FAILED | CANCELLED | BUDGET_HALT}
     *
     * The SSE stream closes automatically on terminal events. Callers that do not support
     * SSE may use the synchronous variant via {@code runInBackground} and poll the run status
     * via {@code GET /api/v1/runs/{runId}}.
     */
    @PostMapping(value = "/tasks", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitTask(@Valid @RequestBody A2aTaskRequest request) {
        // Resolve trace correlation id on the HTTP thread while MDC is bound by
        // A2aTraceContextFilter. The virtual thread spawned by submitTask sees
        // an empty MDC, so resolving inside the executor would fragment the
        // trace_id across audit rows (SUBMITTED audited from this thread vs
        // WORKING/COMPLETED audited from the virtual thread). Stash the
        // resolved value on the effective request so audit() always reads it
        // from a single source of truth.
        String effectiveTraceId = request.traceId() != null
            ? request.traceId()
            : org.slf4j.MDC.get(
                com.operativus.agentmanager.control.security.A2aTraceContextFilter.MDC_TRACE_ID_KEY);

        A2aTaskRequest effectiveRequest = new A2aTaskRequest(
            request.taskId() != null ? request.taskId() : UUID.randomUUID().toString(),
            request.targetAgentId(),
            request.input(),
            request.initiatingAgentId(),
            request.sessionId(),
            effectiveTraceId,
            request.finOpsBoundary()
        );

        // Resolve the caller's org from the SecurityContext on the HTTP thread —
        // the virtual thread spawned by submitTask cannot read SecurityContextHolder.
        // Without this, agentOperations.run was called with orgId=null and the
        // registry's findById fell back to a non-tenant-scoped lookup, allowing
        // any caller to invoke any agent regardless of org ownership.
        String callerOrgId = CallerContext.resolveCallerOrgId();

        log.info("A2A task submitted: taskId={} targetAgent={} initiator={} callerOrg={} traceId={}",
            effectiveRequest.taskId(), effectiveRequest.targetAgentId(),
            effectiveRequest.initiatingAgentId(), callerOrgId, effectiveTraceId);

        return taskExecutor.submitTask(effectiveRequest, callerOrgId);
    }

    /**
     * @summary Cancels an active inbound A2A task.
     * @logic Issues an interrupt via {@code A2ATaskExecutor#cancelTask}.
     *        Returns 404 if the task is unknown or already terminal.
     */
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> cancelTask(@PathVariable String taskId) {
        boolean cancelled = taskExecutor.cancelTask(taskId);
        return cancelled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * @summary §22.5 inbound hook — receives a cross-peer cancellation notify.
     * @logic A peer whose task we originated calls this after it has cancelled
     *        the execution on its side. We append an audit row to
     *        {@code a2a_task_events} with status=CANCELLED and a message tagged
     *        {@code "notify-received"} so operators can reconstruct the
     *        propagation trail. The write is synchronous — callers expect a
     *        durable acknowledgement. Returns 204 on success; 400 if the payload
     *        is missing the correlation {@code taskId}.
     */
    @PostMapping("/peers/cancel-notify")
    public ResponseEntity<Void> receiveCancellationNotify(@RequestBody PeerCancellationNotify body) {
        if (body == null || !StringUtils.hasText(body.taskId())) {
            return ResponseEntity.badRequest().build();
        }
        A2aTaskEventEntity event = new A2aTaskEventEntity();
        event.setTaskId(body.taskId());
        event.setTargetAgentId("peer-notify-inbound");
        event.setStatus(A2aTaskStatus.CANCELLED);
        event.setMessage("notify-received: " + (body.reason() != null ? body.reason() : ""));
        event.setEventTs(LocalDateTime.now());
        taskEventRepository.save(event);
        log.info("A2A cancel-notify received: taskId={} reason={}", body.taskId(), body.reason());
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Request body records
    // -----------------------------------------------------------------------

    /**
     * Wire format for {@code POST /api/v1/a2a/peers}.
     */
    public record RegisterPeerRequest(
        String remoteAgentId,
        String baseUrl,
        String alias,
        String apiKey
    ) {}
}
