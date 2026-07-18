package com.operativus.agentmanager.control.controller.observability;

import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.DelegationTopologyResponse;
import com.operativus.agentmanager.core.model.DelegationTopologyResponse.Edge;
import com.operativus.agentmanager.core.model.DelegationTopologyResponse.Node;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asLong;
import static com.operativus.agentmanager.control.controller.observability.AggregateRowConverters.asString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Read-side aggregate surface for the Delegation Topology tab
 *     (observability plan T037a). Returns the per-strategy edge list plus the
 *     derived per-agent node totals so the heatmap matrix can render row/column
 *     summaries without a second round-trip.
 *
 * <p>Per §4.10 the aggregate read paths are typically {@code @Cacheable} with a 60s TTL;
 * this controller is intentionally <b>not</b> cached yet — caching infrastructure can be
 * layered in later when a {@code CacheManager} bean is wired.
 *
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability/aggregates")
public class DelegationTopologyController {

    private static final int MAX_WINDOW_DAYS = 365;

    private final OrchestrationDecisionRepository repository;

    public DelegationTopologyController(OrchestrationDecisionRepository repository) {
        this.repository = repository;
    }

    /**
     * @summary Returns delegation edges + derived node totals for the last {@code window} days.
     * @logic
     * - {@code window} clamps to [1, 365] (default 30).
     * - Tenant filter resolved from {@link AgentContextHolder#getOrgId()} (JWT-bound);
     *   {@code null} (super-admin) bypasses the filter.
     * - Edges come straight from {@code findDelegationTopology}.
     * - Nodes are derived server-side: each unique {@code from}/{@code to} agent gets a row
     *   with {@code totalIn} (sum of counts where it appears as {@code to}) and
     *   {@code totalOut} (sum of counts where it appears as {@code from}).
     */
    @GetMapping("/delegation-topology")
    public DelegationTopologyResponse getDelegationTopology(
            @RequestParam(value = "window", defaultValue = "30") int window) {

        String orgId = AgentContextHolder.getOrgId();
        int safeWindow = Math.min(Math.max(window, 1), MAX_WINDOW_DAYS);
        Instant since = Instant.now().minus(Duration.ofDays(safeWindow));

        List<Object[]> rows = repository.findDelegationTopology(since, orgId);
        List<Edge> edges = new ArrayList<>(rows.size());
        Map<String, long[]> totals = new HashMap<>();
        for (Object[] row : rows) {
            String from = asString(row[0]);
            String to = asString(row[1]);
            String strategy = asString(row[2]);
            long count = asLong(row[3]);
            edges.add(new Edge(from, to, strategy, count));

            if (from != null) totals.computeIfAbsent(from, k -> new long[2])[1] += count;
            if (to != null) totals.computeIfAbsent(to, k -> new long[2])[0] += count;
        }

        List<Node> nodes = new ArrayList<>(totals.size());
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            long[] inOut = entry.getValue();
            nodes.add(new Node(entry.getKey(), inOut[0], inOut[1]));
        }
        nodes.sort((a, b) -> Long.compare(b.totalIn() + b.totalOut(), a.totalIn() + a.totalOut()));

        return new DelegationTopologyResponse(edges, nodes);
    }

}
