package com.operativus.agentmanager.control.finops.exception;

/**
 * Domain Responsibility: Signals that an org has hit its DAILY USD spend cap at run-admission time
 *     (REQ — FinOps daily budget enforcement). Thrown by {@code DailySpendService.enforceOrgDailyCap}
 *     as a PREFLIGHT gate — before a run row is created or any provider token is spent — so a
 *     capped org's request is rejected cleanly at the boundary.
 *
 *     <p>Distinct from {@code FinOpsBudgetExhaustedException} on purpose: that one fires mid-flight
 *     inside the advisor chain and routes through {@code HitlPauseHandler} to PAUSE the run for an
 *     operator top-up. A daily-cap breach is admission control, not a pausable in-flight event —
 *     there is no run to pause — so it carries no HITL coupling and maps straight to HTTP 402.
 * State: Immutable (RuntimeException)
 */
public class DailyBudgetExceededException extends RuntimeException {

    private final String orgId;
    private final double currentSpendUsd;
    private final double dailyCapUsd;

    public DailyBudgetExceededException(String orgId, double currentSpendUsd, double dailyCapUsd) {
        super(String.format(
                "Org [%s] daily budget exhausted: today's spend $%.4f has reached the cap of $%.4f",
                orgId, currentSpendUsd, dailyCapUsd));
        this.orgId = orgId;
        this.currentSpendUsd = currentSpendUsd;
        this.dailyCapUsd = dailyCapUsd;
    }

    public String getOrgId()            { return orgId; }
    public double getCurrentSpendUsd()  { return currentSpendUsd; }
    public double getDailyCapUsd()      { return dailyCapUsd; }
}
