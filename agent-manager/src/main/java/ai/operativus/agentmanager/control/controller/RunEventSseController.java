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
 * Domain Responsibility: HTTP surface for Logging Plan §5.17 — streams a single agent run's
 *     timeline (historical replay + live follow-up) over Server-Sent Events. Delegates all
 *     polling and emission to {@link RunEventSseService}.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunEventSseController {

    private final RunEventSseService service;

    public RunEventSseController(RunEventSseService service) {
        this.service = service;
    }

    /**
     * @summary Opens an SSE stream for the run's timeline. The response continues until a
     *     {@code RUN_COMPLETE}/{@code RUN_FAILED} event is emitted, the emitter times out,
     *     or the client disconnects.
     * @logic
     * - Replays every {@code agent_run_events} row with {@code id > sinceId} for {@code runId}.
     * - Polls for new rows on a 500ms cadence (configurable via
     *   {@code agent.run.events.sse.poll-interval-ms}).
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound
     *   by {@code TenantContextFilter}); {@code null} (super-admin path) skips the
     *   per-event tenant check inside the service.
     */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRunEvents(
            @PathVariable("runId") String runId,
            @RequestParam(value = "sinceId", required = false) Long sinceId) {
        return service.stream(runId, AgentContextHolder.getOrgId(), sinceId);
    }
}
