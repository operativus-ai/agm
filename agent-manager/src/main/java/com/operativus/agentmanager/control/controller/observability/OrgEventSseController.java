package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.service.RunEventSseService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Domain Responsibility: HTTP surface for the org-wide "all agents" live events view — streams every
 *     {@code agent_run_events} row for the caller's tenant (across all agents and runs) over SSE.
 *     The org-wide firehose counterpart to {@code RunEventSseController} (one run) and
 *     {@code AgentEventSseController} (one agent); like the latter it is long-lived and does NOT
 *     close on a single run's terminal event. Delegates to {@link RunEventSseService}.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability")
public class OrgEventSseController {

    private final RunEventSseService service;

    public OrgEventSseController(RunEventSseService service) {
        this.service = service;
    }

    /**
     * @summary Opens a long-lived SSE stream of the tenant's entire event timeline. Continues until
     *     the emitter times out or the client disconnects.
     * @logic
     * - Replays every {@code agent_run_events} row with {@code id > sinceId} for the caller's org,
     *   or — when {@code sinceId} is negative — starts at the live tail (current max id).
     * - Tenant scope is resolved from {@link AgentContextHolder#getOrgId()} and applied in the
     *   repository query, so one tenant can never tail another's events.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOrgEvents(
            @RequestParam(value = "sinceId", required = false) Long sinceId) {
        return service.streamByOrg(sinceId, AgentContextHolder.getOrgId());
    }
}
