package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Central registry of Micrometer metric and observation names
 * used across the advisor chain and orchestration layer.
 * State: Static constants only — never instantiate.
 */
public final class MetricConstants {

    private MetricConstants() {}

    // --- Advisor timing ---
    public static final String ADVISOR_DURATION_MS = "advisor.duration_ms";

    // --- Orchestration observations ---
    public static final String ORCHESTRATION_OBSERVATION = "agm.orchestration";

    /** Public meter name pinned for downstream dashboards. Drift breaks alerts.
     *  Counter increments by 1 every time an orchestration run is rejected for
     *  exceeding a concurrency cap. Tag {@code scope} distinguishes the two paths:
     *  {@code per_agent} (per-agent FinOps cap from {@code AgentDefinition.maxConcurrentExecutions})
     *  vs {@code global} (JVM-wide {@code agent.orchestration.max-concurrent-calls}).
     *  Operators alert on a sustained non-zero rate of {@code scope=global} rejections —
     *  that signals a need to either scale out or raise the property. */
    public static final String ORCHESTRATION_CALLS_REJECTED = "agm.orchestration.calls.rejected";

    // --- Agent run observation ---
    public static final String AGENT_RUN_OBSERVATION = "agm.agent.run";

    // --- PII advisor ---
    public static final String PII_REDACTION_EVENTS    = "agm.security.pii.redaction_events";
    public static final String PII_SCANNED             = "agm.security.pii.scanned";

    // --- Prompt injection advisor ---
    public static final String PROMPT_INJECTION_SCANNED = "agm.security.prompt_injection.scanned";

    // --- Content safety advisor ---
    public static final String CONTENT_SAFETY_SCANNED     = "agm.security.content_safety.scanned";
    public static final String CONTENT_SAFETY_RISK_SCORE  = "agm.security.content_safety.risk_score";

    // --- HITL resume observability (Tier 2.4 PR 6) ---
    /** Counter incremented every time {@code AgentService.continueRun} returns a terminal outcome.
     *  Tag {@code outcome} ∈ {@code approved} | {@code rejected} | {@code error} distinguishes
     *  the resume path's three exit modes. Tag {@code tool} carries the resumed tool name when
     *  known (extracted from the paused row's {@code requiredAction} payload), otherwise
     *  {@code n/a}. SREs alert on {@code outcome=error} rate > 0 — exceptions escaping resume
     *  indicate stuck runs in production. */
    public static final String HITL_RESUME_TOTAL = "agm.hitl.resume.total";

    /** Counter incremented every time {@code AgentRunFinalizer.finalizeRun}'s 3-attempt
     *  optimistic-lock retry loop exhausts. Tag {@code status} carries the requested
     *  terminal status that was lost. SREs alert on rate > 0 — sustained contention signals
     *  cross-pod finalize races (T004b's terminal-state guard handles in-process; cross-pod
     *  is PR-7 territory). */
    public static final String HITL_FINALIZE_LOCK_EXHAUSTED_TOTAL = "agm.hitl.finalize.lock_exhausted_total";

    /** Span name for the HITL resume path — wraps {@code AgentService.continueRun} body so
     *  resume operations show up as siblings of the original run's span in the trace UI. */
    public static final String SPAN_HITL_RESUME = "agm.hitl.resume";

    // --- Memory search observability (M9) ---
    /** Counter incremented each time {@code MemoryService} executes a vector-store similarity
     *  search. Tag {@code source} ∈ {@code memories} (query-driven) | {@code user_memories}
     *  (userId semantic-rule retrieval). SREs use the rate to size the pgvector index and
     *  detect advisor-chain regressions that silently disable memory retrieval. */
    public static final String MEMORY_SEARCH_TOTAL = "agm.memory.search.total";

    // --- OTLP span names ---
    public static final String SPAN_LLM_CALL   = "agm.llm.call";
    public static final String SPAN_LLM_STREAM = "agm.llm.stream";

    // --- OTLP attribute keys ---
    public static final String ATTR_AGENT_ID   = "agm.agent.id";
    public static final String ATTR_RUN_ID     = "agm.run.id";
    public static final String ATTR_SESSION_ID = "agm.session.id";
    public static final String ATTR_LATENCY_MS = "agm.latency_ms";
}
