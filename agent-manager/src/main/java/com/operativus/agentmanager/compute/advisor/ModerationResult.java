package com.operativus.agentmanager.compute.advisor;

import java.util.List;

/**
 * Domain Responsibility: Structured outcome of a {@link ModerationService#checkContent(String)} call.
 *     Carries a numeric risk score and the list of matched signal/category names so callers can
 *     publish observability (counters, distribution summaries) without re-running the policy logic.
 * State: Stateless (immutable record).
 *
 * <p><b>Score semantics.</b> {@code riskScore} is a 0.0–1.0 scalar. {@code 0.0} = clean,
 * {@code 1.0} = maximum risk. Implementations are free to choose the granularity. The current
 * {@link LocalRegexModerationService} returns {@link #clean()} on pass-through and throws
 * {@link SecurityException} on hard-block, so observed scores in production today are always 0.0;
 * future moderation backends (model-based, multi-signal regex, hybrid) can populate the score
 * without changing the advisor wiring.
 *
 * <p><b>Why the throw is preserved alongside the return.</b> Hard-block policy enforcement is a
 * security contract: a flagged response MUST NOT exit the LLM chain. Returning a result-with-block-flag
 * shifts that responsibility to every caller, multiplying the risk of an honest-mistake omission.
 * Soft signals (low-risk, suspicious-but-allowed) flow through the result; hard violations throw.
 */
public record ModerationResult(double riskScore, List<String> signals) {

    public ModerationResult {
        if (signals == null) signals = List.of();
        if (Double.isNaN(riskScore)) riskScore = 0.0;
        if (riskScore < 0.0) riskScore = 0.0;
        if (riskScore > 1.0) riskScore = 1.0;
    }

    /** Sentinel for the common "no signals, no risk" outcome. */
    public static ModerationResult clean() {
        return new ModerationResult(0.0, List.of());
    }
}
