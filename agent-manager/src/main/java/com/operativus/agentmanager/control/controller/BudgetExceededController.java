package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.AgentRunEventRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentRunEventEntity;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.model.BudgetExceededFeedResponse;
import com.operativus.agentmanager.core.model.BudgetExceededFeedResponse.BudgetExceededEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Cursor-feed surface for budget-exceeded telemetry (observability
 *     plan T030a). Powers the dashboard banner widget (T030f) which polls every 60s and
 *     advances {@code nextCursor} to receive only newly recorded breaches.
 * State: Stateless controller.
 *
 * <p>This is intentionally <b>not</b> cached — cursor semantics break under cache-key reuse.
 * See plan §4.10 (cache strategy carve-outs).
 */
@RestController
@RequestMapping("/api/observability")
public class BudgetExceededController {

    private static final int MAX_LIMIT = 200;
    private static final Duration DEFAULT_LOOKBACK = Duration.ofDays(30);

    private final AgentRunEventRepository eventRepository;

    public BudgetExceededController(AgentRunEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * @summary Returns BUDGET_EXCEEDED events with {@code event_ts > since}, ordered
     *     ascending. The {@code nextCursor} is the {@code event_ts} of the newest event
     *     in the response (or {@code since} when the result is empty, so the client can
     *     reuse the same cursor on the next poll).
     * @logic
     * - {@code since} parses ISO-8601 (e.g. {@code 2026-04-25T00:00:00Z}). When absent,
     *   defaults to {@code now - 30 days} so the banner renders a useful initial window.
     * - {@code limit} clamps to [1, 200] (default 50).
     * - Tenant filter: resolved from {@link AgentContextHolder#getOrgId()}, which
     *   {@code TenantContextFilter} binds from the caller's JWT {@code org_id} claim
     *   (falling back to the {@code X-Org-Id} header, then the authenticated principal's
     *   {@code orgId}). A regular tenant user's JWT always carries the claim, so spoofing
     *   the {@code X-Org-Id} header is a no-op for them. When the resolved orgId is
     *   {@code null} (super-admin or unauthenticated path), the repository query falls
     *   through to the cross-org admin view via its {@code :orgId IS NULL} short-circuit.
     */
    @GetMapping("/budget-exceeded-feed")
    public BudgetExceededFeedResponse getFeed(
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        String orgId = AgentContextHolder.getOrgId();
        Instant effectiveSince = since != null ? since : Instant.now().minus(DEFAULT_LOOKBACK);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Pageable page = PageRequest.of(0, safeLimit);

        List<AgentRunEventEntity> rows = eventRepository.findFeedByEventTypeAndOrg(
                AgentRunEventType.BUDGET_EXCEEDED, effectiveSince, orgId, page);

        List<BudgetExceededEvent> events = rows.stream().map(BudgetExceededController::toDto).toList();
        Instant nextCursor = events.isEmpty()
                ? effectiveSince
                : events.get(events.size() - 1).eventTs();

        return new BudgetExceededFeedResponse(events, nextCursor);
    }

    private static BudgetExceededEvent toDto(AgentRunEventEntity e) {
        return new BudgetExceededEvent(
                e.getId(),
                e.getRunId(),
                e.getAgentId(),
                e.getPayload(),
                e.getEventTs());
    }
}
