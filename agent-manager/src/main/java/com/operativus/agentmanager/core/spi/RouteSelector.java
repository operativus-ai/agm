package com.operativus.agentmanager.core.spi;

import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;

/**
 * Domain Responsibility: SPI for resolving a ROUTER workflow step (REQ-DR-4)
 *     to one of its declared choice keys. Implementations are dispatched by
 *     {@link #selectorType()} — the workflow engine looks up the matching
 *     bean for the step's configured {@code selectorType}.
 *
 *     <p>Implementations:
 *     <ul>
 *       <li>{@code RULE} — JSONPath expression on prior step output.</li>
 *       <li>{@code LLM}  — classification prompt; lands in REQ-DR-4 PR-5.</li>
 *       <li>{@code HITL} — suspends the run; lands in REQ-DR-4 PR-4.</li>
 *     </ul>
 *
 *     <p>Implementations MUST NOT block on the calling thread for I/O — RULE is
 *     pure-compute, LLM uses Spring AI ChatClient, HITL returns a sentinel that
 *     the dispatcher translates into {@code RunStatus.AWAITING_ROUTE_SELECTION}.
 *
 * State: Stateless (Spring bean)
 */
public interface RouteSelector {

    /** The {@link RouteSelectorType} this implementation handles. Required for dispatch. */
    RouteSelectorType selectorType();

    /**
     * Resolve the prior-step output to a choice key. Returns the matched key
     * from {@code config.choices()} when the selector produces a recognized
     * value; returns {@code config.defaultChoice()} when the produced value is
     * not in the choices map; returns {@code null} when no match is possible
     * and there is no default (the dispatcher fails the run with an
     * IllegalStateException). HITL implementations return {@link #HITL_PENDING}
     * to signal "suspend the run".
     */
    String selectChoice(RouterStepConfig config, String priorStepOutput);

    /**
     * Sentinel returned by HITL implementations to instruct the dispatcher to
     * suspend the run with {@code RunStatus.AWAITING_ROUTE_SELECTION} instead
     * of proceeding to a branch.
     */
    String HITL_PENDING = "__HITL_PENDING__";
}
