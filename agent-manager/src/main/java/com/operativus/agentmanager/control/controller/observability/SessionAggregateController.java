package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.SessionAggregateResponse;
import com.operativus.agentmanager.core.model.SessionAggregateResponse.Bucket;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asDouble;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asInstant;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asLong;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain Responsibility: Read-side aggregate surface for the Sessions Analytics tab
 *     (observability plan T039a). Returns a per-day rollup so the tab renders both
 *     the session-count area chart and the p50/p95 duration dual-line from a single
 *     fetch.
 *
 * <p>Per §4.10 the aggregate read paths are typically {@code @Cacheable} with a 60s TTL;
 * this controller is intentionally <b>not</b> cached yet — caching infrastructure can be
 * layered in later when a {@code CacheManager} bean is wired.
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/aggregates")
public class SessionAggregateController {

    private static final int MAX_WINDOW_DAYS = 365;

    private final SessionRepository repository;

    public SessionAggregateController(SessionRepository repository) {
        this.repository = repository;
    }

    /**
     * @summary Returns per-day session analytics for the last {@code window} days.
     * @logic
     * - {@code window} clamps to [1, 365] (default 30).
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()}, which
     *   {@code TenantContextFilter} binds from the caller's JWT {@code org_id} claim.
     *   {@code null} (super-admin path) falls through to the cross-org admin view
     *   via the repository's {@code :orgId IS NULL} short-circuit.
     * - Maps {@code findSessionAnalytics} rows to {@link Bucket} records.
     */
    @GetMapping("/sessions")
    public SessionAggregateResponse getSessionAggregates(
            @RequestParam(value = "window", defaultValue = "30") int window) {

        String orgId = AgentContextHolder.getOrgId();
        int safeWindow = Math.min(Math.max(window, 1), MAX_WINDOW_DAYS);
        Instant since = Instant.now().minus(Duration.ofDays(safeWindow));

        List<Object[]> rows = repository.findSessionAnalytics(since, orgId);
        List<Bucket> buckets = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            buckets.add(new Bucket(
                    asInstant(row[0]),
                    asLong(row[1]),
                    asDouble(row[2]),
                    asDouble(row[3]),
                    asDouble(row[4])));
        }
        return new SessionAggregateResponse(buckets);
    }

}
