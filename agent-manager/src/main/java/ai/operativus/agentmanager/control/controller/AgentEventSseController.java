package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.service.RunEventSseService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Domain Responsibility: HTTP surface for the per-agent realtime events page — streams every
 *     {@code agent_run_events} row for a single agent across all of its runs (historical replay
 *     + live follow-up) over Server-Sent Events. Unlike {@link RunEventSseController} (one run,
 *     closes on terminal event), this stream is long-lived: an agent has many runs over time, so
 *     it tails until the emitter timeout or client disconnect. Delegates to {@link RunEventSseService}.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentEventSseController {

    private final RunEventSseService service;

    public AgentEventSseController(RunEventSseService service) {
        this.service = service;
    }

    /**
     * @summary Opens a long-lived SSE stream of the agent's event timeline. The response continues
     *     until the emitter times out or the client disconnects — it does NOT close on any single
     *     run's {@code RUN_COMPLETE}/{@code RUN_FAILED}.
     * @logic
     * - Replays every {@code agent_run_events} row with {@code id > sinceId} for {@code agentId},
     *   strictly scoped to the caller's org.
     * - Polls for new rows on the configured cadence ({@code agent.run.events.sse.poll-interval-ms}).
     * - Tenant scope is resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound by
     *   {@code TenantContextFilter}) and applied in the repository query, so org B cannot tail
     *   org A's agent events.
     */
    @GetMapping(value = "/{agentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgentEvents(
            @PathVariable("agentId") String agentId,
            @RequestParam(value = "sinceId", required = false) Long sinceId) {
        return service.streamByAgent(agentId, sinceId, AgentContextHolder.getOrgId());
    }
}
