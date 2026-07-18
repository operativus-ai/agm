package com.operativus.agentmanager.compute.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.operativus.agentmanager.control.approval.HitlPauseHandler;
import com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.core.event.AgentRunEventBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Domain Responsibility: pin the SDD G2 property-default session ceiling behaviour and the
 *   boot-log contract that announces its state (file:
 *   {@code docs/plans/agm-finops-cost-enforcement-36.md}).
 *
 * <p>Three things are pinned here:
 * <ol>
 *   <li><strong>Precedence:</strong> {@code AgentContextHolder.CONTEXT.remainingBudget()}
 *       (when bound) wins over the property. The property only applies when CONTEXT is
 *       unbound — i.e. on the primary {@code /api/agents/{id}/run} path.</li>
 *   <li><strong>Opt-in default:</strong> property unset → null → no enforcement (operators
 *       must opt in once they've observed real burn rates).</li>
 *   <li><strong>Boot log:</strong> exact ENABLED / DISABLED phrasing pinned so operator
 *       runbooks that grep for it don't silently break on refactor.</li>
 * </ol>
 *
 * State: Stateless (each test instantiates a fresh advisor + log-appender pair).
 */
class GenAiMetricsAdvisorPropertyCeilingTest {

    private SimpleMeterRegistry meterRegistry;
    private LiveValuationEngine valuationEngine;
    private HitlPauseHandler hitlPauseHandler;
    private AgentRunEventBus eventBus;
    private BurnRateMonitorService burnRateMonitor;

    private Logger advisorLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        meterRegistry    = new SimpleMeterRegistry();
        var mockRepo = mock(com.operativus.agentmanager.control.repository.FinOpsValuationRateRepository.class);
        valuationEngine  = new LiveValuationEngine(mockRepo, 300000L);
        hitlPauseHandler = mock(HitlPauseHandler.class);
        eventBus         = mock(AgentRunEventBus.class);
        burnRateMonitor  = mock(BurnRateMonitorService.class);

        advisorLogger = (Logger) LoggerFactory.getLogger(GenAiMetricsAdvisor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        advisorLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        advisorLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // -------------------------------------------------------------------------
    // Boot log — pinned ENABLED / DISABLED phrasing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Boot log announces DISABLED when property is null (opt-in default)")
    void bootLogDisabledWhenPropertyNull() {
        new GenAiMetricsAdvisor(meterRegistry, valuationEngine, hitlPauseHandler,
                eventBus, burnRateMonitor, null);

        assertThat(logAppender.list)
                .as("Operator runbook depends on this exact phrase — see GenAiMetricsAdvisor.STARTUP_LOG_DISABLED_MESSAGE")
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().equals(GenAiMetricsAdvisor.STARTUP_LOG_DISABLED_MESSAGE));
    }

    @Test
    @DisplayName("Boot log announces ENABLED with formatted dollar value when property is set")
    void bootLogEnabledWhenPropertySet() {
        new GenAiMetricsAdvisor(meterRegistry, valuationEngine, hitlPauseHandler,
                eventBus, burnRateMonitor, 25.00);

        assertThat(logAppender.list)
                .as("ENABLED phrasing is the operator-facing signal that enforcement is live; the dollar value must be formatted to 2 decimal places")
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("ENABLED at $25.00 per session")
                        && e.getFormattedMessage().contains("agentmanager.finops.default-session-ceiling-usd"));
    }

    // -------------------------------------------------------------------------
    // Precedence — CONTEXT-bound vs property-default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Property unset + CONTEXT unbound → no enforcement (current behaviour preserved)")
    void noEnforcementWhenPropertyUnsetAndContextUnbound() {
        GenAiMetricsAdvisor advisor = new GenAiMetricsAdvisor(meterRegistry, valuationEngine,
                hitlPauseHandler, eventBus, burnRateMonitor, null);

        // No CONTEXT bound, no property — the embedding cost path must NOT throw.
        // Pre-PR-2 behaviour is preserved exactly: dead-zero enforcement on the primary path.
        assertThatCode(() -> advisor.accumulateEmbeddingCost("sess-no-budget", "gpt-4o", 50_000L))
                .as("Property null + CONTEXT unbound is the explicit opt-in default — must not throw")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Property set + CONTEXT unbound → property is the active ceiling")
    void propertyAppliesWhenContextUnbound() {
        // Property set to a tiny ceiling so a single embedding call exceeds it.
        GenAiMetricsAdvisor advisor = new GenAiMetricsAdvisor(meterRegistry, valuationEngine,
                hitlPauseHandler, eventBus, burnRateMonitor, 0.0001);

        assertThatThrownBy(
                () -> advisor.accumulateEmbeddingCost("sess-prop", "gpt-4o", 50_000L))
                .as("Property fallback must trigger enforcement on the primary path")
                .isInstanceOf(FinOpsBudgetExhaustedException.class);
    }

    @Test
    @DisplayName("Property set + CONTEXT bound with tighter budget → CONTEXT wins (precedence safety)")
    void contextBudgetWinsOverPropertyWhenBound() {
        // Property = $100 (loose), CONTEXT remainingBudget = $0.0001 (tight).
        // CONTEXT must win — the OBO gateway's per-request budget is the bounded-autonomy
        // contract and must always take precedence. Reversing this is a safety regression.
        GenAiMetricsAdvisor advisor = new GenAiMetricsAdvisor(meterRegistry, valuationEngine,
                hitlPauseHandler, eventBus, burnRateMonitor, 100.00);

        com.operativus.agentmanager.control.security.AgentContextHolder.AgentContext ctx =
                new com.operativus.agentmanager.control.security.AgentContextHolder.AgentContext(
                        "team-precedence", "human-1", 0.0001, null);

        ScopedValue.where(com.operativus.agentmanager.core.callback.AgentContextHolder.currentRunId, "run-precedence-1")
                .where(com.operativus.agentmanager.core.callback.AgentContextHolder.sessionId, "sess-prec")
                .where(com.operativus.agentmanager.core.callback.AgentContextHolder.agentId, "agent-prec")
                .where(com.operativus.agentmanager.core.callback.AgentContextHolder.orgId, "org-prec")
                .where(com.operativus.agentmanager.control.security.AgentContextHolder.CONTEXT, ctx)
                .run(() -> assertThatThrownBy(
                        () -> advisor.accumulateEmbeddingCost("sess-prec", "gpt-4o", 50_000L))
                        .as("CONTEXT remainingBudget=$0.0001 must trip BEFORE the property's $100 ceiling — precedence is non-negotiable")
                        .isInstanceOf(FinOpsBudgetExhaustedException.class));
    }
}
