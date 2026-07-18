package com.operativus.agentmanager.control.finops.service;

import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.finops.exception.DailyBudgetExceededException;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Domain Responsibility: Org-level DAILY FinOps budget — computes today's USD spend for an org from
 *     {@code agent_runs.total_cost_usd} and enforces a per-org daily cap as a PREFLIGHT admission
 *     gate at run start ({@code AgentService.run}). Complements the existing controls rather than
 *     replacing them: {@code GenAiMetricsAdvisor} enforces a per-session cumulative ceiling
 *     mid-flight (HITL-pausable); this adds the missing daily window, rejecting a capped org's
 *     request before a run row or any provider token is created.
 *
 *     <p><b>Window:</b> calendar day in UTC (matches the {@code date_trunc('day', ... AT TIME ZONE
 *     'UTC')} idiom used elsewhere in {@code RunRepository}). <b>Scope:</b> org only — every
 *     {@code agent_runs} row carries {@code org_id}; team-level daily caps need a {@code team_id}
 *     attribution that does not exist yet.
 *
 *     <p><b>Config:</b> {@code agentmanager.finops.org-daily-cap-usd} — a global default cap (USD).
 *     Unset / blank / {@code <= 0} = DISABLED (no daily gate), mirroring the disabled-by-default
 *     rollout of {@code default-session-ceiling-usd}. Per-org overrides are a follow-up (no
 *     {@code organizations} table to hang a column on today).
 * State: Stateless (Spring singleton).
 */
@Service
public class DailySpendService {

    private static final Logger log = LoggerFactory.getLogger(DailySpendService.class);

    private final RunRepository runRepository;
    private final AgentRunEventBus eventBus;
    private final Double orgDailyCapUsd;
    /** UTC clock for the daily-window boundary; injected so tests can pin a fixed day. */
    private final Clock clock;

    public DailySpendService(RunRepository runRepository,
                             AgentRunEventBus eventBus,
                             @Value("${agentmanager.finops.org-daily-cap-usd:#{null}}") Double orgDailyCapUsd,
                             Clock clock) {
        this.runRepository = runRepository;
        this.eventBus = eventBus;
        this.orgDailyCapUsd = orgDailyCapUsd;
        this.clock = clock;
    }

    /** Today's (UTC calendar day) total USD spend for the org; 0 when the org has no runs today. */
    public double currentDailySpendUsd(String orgId) {
        if (orgId == null || orgId.isBlank()) return 0.0;
        LocalDateTime startOfDayUtc = LocalDate.now(clock).atStartOfDay();
        BigDecimal sum = runRepository.sumCostUsdByOrgSince(orgId, startOfDayUtc);
        return sum == null ? 0.0 : sum.doubleValue();
    }

    /**
     * Today's (UTC calendar day) total USD spend attributable to a team — the sum over its member
     * agents' {@code agent_runs}. Returns 0 for a team with no members (and never issues an
     * {@code IN ()} query). Display metric for team health; not an admission gate — team-cap
     * enforcement at run start is still blocked on per-run team attribution ({@code agent_runs} has
     * {@code org_id} but no {@code team_id}, and an agent may belong to several teams).
     */
    public double currentTeamDailySpendUsd(java.util.Collection<String> memberAgentIds) {
        if (memberAgentIds == null || memberAgentIds.isEmpty()) return 0.0;
        LocalDateTime startOfDayUtc = LocalDate.now(clock).atStartOfDay();
        BigDecimal sum = runRepository.sumCostUsdByAgentIdsSince(memberAgentIds, startOfDayUtc);
        return sum == null ? 0.0 : sum.doubleValue();
    }

    /** The configured daily cap, or empty when daily enforcement is disabled. */
    public java.util.Optional<Double> dailyCapUsd() {
        return (orgDailyCapUsd != null && orgDailyCapUsd > 0) ? java.util.Optional.of(orgDailyCapUsd)
                                                              : java.util.Optional.empty();
    }

    /**
     * Preflight admission gate: throws {@link DailyBudgetExceededException} (→ HTTP 402) when the
     * org has already reached its daily cap, after emitting a best-effort {@code BUDGET_EXCEEDED}
     * timeline event. No-op when the cap is unset or the org is unknown. Called before any spend.
     */
    public void enforceOrgDailyCap(String orgId) {
        if (orgDailyCapUsd == null || orgDailyCapUsd <= 0) return; // daily enforcement disabled
        if (orgId == null || orgId.isBlank()) return;              // unattributable — session ceiling still guards
        double spent = currentDailySpendUsd(orgId);
        if (spent < orgDailyCapUsd) return;
        log.warn("FinOps daily cap reached — orgId={}, todaySpend=${}, cap=${}; rejecting run admission",
                orgId, spent, orgDailyCapUsd);
        publishBudgetExceeded(orgId, spent);
        throw new DailyBudgetExceededException(orgId, spent, orgDailyCapUsd);
    }

    /** Best-effort org-scoped BUDGET_EXCEEDED event so alerting/feeds see the daily halt; never blocks the gate. */
    private void publishBudgetExceeded(String orgId, double spent) {
        if (eventBus == null) return;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orgId", orgId);
            payload.put("cumulativeUsd", spent);
            payload.put("budgetCeilingUsd", orgDailyCapUsd);
            payload.put("costKind", "daily");
            payload.put("phase", "preflight");
            // No run row exists yet (admission gate) — a synthetic id keeps the timeline event valid
            // and correlatable; agent_run_events has no FK to agent_runs.
            eventBus.publish(new AgentRunEvent(
                    AgentRunEventType.BUDGET_EXCEEDED, "preflight-" + UUID.randomUUID(),
                    null, null, null, orgId, 0, payload, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("Failed to publish daily BUDGET_EXCEEDED event for orgId={}", orgId, ex);
        }
    }
}
