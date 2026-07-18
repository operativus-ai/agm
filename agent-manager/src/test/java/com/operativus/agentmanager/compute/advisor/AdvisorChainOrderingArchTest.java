package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.compute.monitoring.GenAiMetricsAdvisor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the non-PII-boundary architectural invariants of the advisor chain.
 *
 * <p>{@link AdvisorPiiBoundaryContractTest} already pins everything relative to
 * the PII redactor at order 10. This test pins the OTHER invariants the chain
 * depends on — most importantly the Bug #2 "conversation-id sandwich" around
 * Spring AI's {@code MessageChatMemoryAdvisor} at {@code HIGHEST_PRECEDENCE + 1000},
 * plus the terminal-chain slots that own per-request telemetry.
 *
 * <p>A silent reorder of any of these would not be caught by the boundary test:
 * if {@link ConversationIdResponseAdvisor} drifted BEFORE MCM, every memory-enabled
 * agent's second turn would crash with {@code conversationId cannot be null} again
 * (the original Bug #2 turn-2 failure). If {@link GenAiMetricsAdvisor} moved off
 * {@code MAX_VALUE}, FinOps cost attribution would race terminal advisors and miss
 * the gen-ai usage Usage metadata.
 *
 * <p>Cases:
 * <ol>
 *   <li>Bug #2 sandwich: injection &lt; MCM(MIN+1000) &lt; response.</li>
 *   <li>Conversation-id injection pinned at MIN+100.</li>
 *   <li>Conversation-id response pinned at MIN+1500.</li>
 *   <li>{@code GenAiMetricsAdvisor} at exactly {@code Integer.MAX_VALUE} (terminal).</li>
 *   <li>{@code OtlpSpanExportAdvisor} at {@code MAX_VALUE-1}. (HallucinationDetectionAdvisor previously shared this slot; removed pre-launch.)</li>
 *   <li>{@code CircuitBreakerAdvisor} at order 1 (fail-fast).</li>
 *   <li>{@code HitlAdvisor} at order 0 (app-tier gate before terminal call).</li>
 *   <li>{@code StructuredOutputRetryAdvisor} at order 1.</li>
 *   <li>{@code AgentLoggingAdvisor} at order 0.</li>
 *   <li>Full chain snapshot — single table the reviewer reads on diff.</li>
 * </ol>
 */
class AdvisorChainOrderingArchTest {

    /**
     * Spring AI's {@code MessageChatMemoryAdvisor} (auto-registered by the builder
     * when memory is enabled) returns {@code Ordered.HIGHEST_PRECEDENCE + 1000}.
     * Hardcoded here so the chain-around-MCM invariants stay legible without
     * loading Spring AI's class at test time.
     */
    static final int MCM_DEFAULT_ORDER = Integer.MIN_VALUE + 1000;

    @Test
    void bug2Sandwich_conversationIdAdvisors_straddle_MCM() {
        int injection = new ConversationIdInjectionAdvisor("sess").getOrder();
        int response  = new ConversationIdResponseAdvisor().getOrder();

        assertThat(injection)
                .as("ConversationIdInjectionAdvisor MUST run BEFORE MCM so its before() reads the seeded conversationId key")
                .isLessThan(MCM_DEFAULT_ORDER);
        assertThat(response)
                .as("ConversationIdResponseAdvisor MUST run AFTER MCM so on the chain unwind it executes BEFORE MCM.after() reads response.context()")
                .isGreaterThan(MCM_DEFAULT_ORDER);
    }

    @Test
    void conversationIdInjection_pinnedAt_MIN_VALUE_plus_100() {
        // Exact value chosen so any future "we'll just nudge this lower" change
        // requires conscious update of the constant. The 100 offset leaves room
        // for genuinely earlier advisors without colliding at MIN_VALUE itself
        // (Spring's Ordered.HIGHEST_PRECEDENCE).
        assertThat(new ConversationIdInjectionAdvisor("sess").getOrder())
                .isEqualTo(Integer.MIN_VALUE + 100);
    }

    @Test
    void conversationIdResponse_pinnedAt_MIN_VALUE_plus_1500() {
        // 500 above MCM_DEFAULT_ORDER. Direct neighbor slot — there is no advisor
        // between MCM and this one. If a new advisor needs to sit between MCM and
        // its response companion, justify it in this test's docs first.
        assertThat(new ConversationIdResponseAdvisor().getOrder())
                .isEqualTo(Integer.MIN_VALUE + 1500);
    }

    @Test
    void genAiMetricsAdvisor_pinnedAt_MAX_VALUE_terminalSlot() {
        // Telemetry depends on running LAST so it observes Usage metadata and
        // the final ChatResponse shape. Any drift off MAX_VALUE could race
        // OtlpSpanExportAdvisor (MAX-1) and break per-request cost attribution.
        GenAiMetricsAdvisor advisor = new GenAiMetricsAdvisor(
                new SimpleMeterRegistry(),
                mock(com.operativus.agentmanager.control.finops.service.LiveValuationEngine.class),
                mock(com.operativus.agentmanager.control.approval.HitlPauseHandler.class),
                mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class),
                mock(com.operativus.agentmanager.control.finops.service.BurnRateMonitorService.class),
                null);
        assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void lateChainAdvisors_pinnedAt_MAX_VALUE_minus_1() {
        // OtlpSpanExportAdvisor emits OTLP spans around the model call. Must run
        // after content generation, just before terminal telemetry.
        // (HallucinationDetectionAdvisor used to share this slot; removed pre-launch.)
        int otlp = new OtlpSpanExportAdvisor(mock(Tracer.class), false, new SimpleMeterRegistry()).getOrder();

        assertThat(otlp).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void circuitBreakerAdvisor_atOrder1_failsFast() {
        // CB sits at 1, not 0, so it runs AFTER PromptInjectionAdvisor (order 0)
        // and AgentLoggingAdvisor (order 0). The "fail fast" goal targets LLM calls,
        // not malformed/malicious requests that should be rejected by injection
        // detection first.
        CircuitBreakerAdvisor cb = new CircuitBreakerAdvisor(
                50f, 10, 3, 30_000L, 2, 60_000L, 80f, new SimpleMeterRegistry());
        assertThat(cb.getOrder()).isEqualTo(1);
    }

    @Test
    void hitlAdvisor_atOrder0_appTierBeforeTerminal() {
        // HitlAdvisor sits at order 0 but is conceptually "late" in the app tier —
        // it gates tool calls just before they execute. Don't move it AFTER the
        // PII boundary (10): tool-name → tier resolution doesn't need PII redaction,
        // and we want the HITL pause to happen before any embedding/cache writes
        // that order-15 advisors trigger.
        HitlAdvisor hitl = new HitlAdvisor(
                mock(com.operativus.agentmanager.core.registry.ApprovalOperations.class),
                mock(com.operativus.agentmanager.control.repository.AgentRepository.class),
                mock(com.operativus.agentmanager.control.service.HumanReviewService.class),
                new SimpleMeterRegistry(),
                Collections.emptyList(),
                false, java.util.Set.of());
        assertThat(hitl.getOrder()).isEqualTo(0);
    }

    @Test
    void structuredOutputRetryAdvisor_atOrder1() {
        // Sits at the same slot as CircuitBreakerAdvisor — both are "wrap the LLM
        // call but stay outside PII/safety." Don't tighten without re-auditing.
        StructuredOutputRetryAdvisor advisor = new StructuredOutputRetryAdvisor(3, new SimpleMeterRegistry());
        assertThat(advisor.getOrder()).isEqualTo(1);
    }

    @Test
    void agentLoggingAdvisor_atOrder0() {
        AgentLoggingAdvisor advisor = new AgentLoggingAdvisor(
                mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class),
                new SimpleMeterRegistry());
        assertThat(advisor.getOrder()).isEqualTo(0);
    }

    /**
     * Full-chain snapshot. The PII boundary slots (10 / 15 / 20 / 49 / 50) are
     * NOT re-asserted here — {@link AdvisorPiiBoundaryContractTest} owns them.
     * This snapshot covers the slots outside the PII boundary range so the two
     * tests partition the contract surface cleanly.
     */
    @Test
    void chainSnapshot_documentsAllNonPiiOrders() {
        assertThat(new ConversationIdInjectionAdvisor("sess").getOrder())
                .as("ConversationIdInjectionAdvisor").isEqualTo(Integer.MIN_VALUE + 100);
        assertThat(MCM_DEFAULT_ORDER)
                .as("MessageChatMemoryAdvisor (Spring AI auto)").isEqualTo(Integer.MIN_VALUE + 1000);
        assertThat(new ConversationIdResponseAdvisor().getOrder())
                .as("ConversationIdResponseAdvisor").isEqualTo(Integer.MIN_VALUE + 1500);
        assertThat(new AgentLoggingAdvisor(
                mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class),
                new SimpleMeterRegistry()).getOrder())
                .as("AgentLoggingAdvisor").isEqualTo(0);
        assertThat(new HitlAdvisor(
                mock(com.operativus.agentmanager.core.registry.ApprovalOperations.class),
                mock(com.operativus.agentmanager.control.repository.AgentRepository.class),
                mock(com.operativus.agentmanager.control.service.HumanReviewService.class),
                new SimpleMeterRegistry(),
                Collections.emptyList(),
                false, java.util.Set.of()).getOrder())
                .as("HitlAdvisor").isEqualTo(0);
        assertThat(new CircuitBreakerAdvisor(50f, 10, 3, 30_000L, 2, 60_000L, 80f,
                new SimpleMeterRegistry()).getOrder())
                .as("CircuitBreakerAdvisor").isEqualTo(1);
        assertThat(new StructuredOutputRetryAdvisor(3, new SimpleMeterRegistry()).getOrder())
                .as("StructuredOutputRetryAdvisor").isEqualTo(1);
        assertThat(new OtlpSpanExportAdvisor(mock(Tracer.class), false, new SimpleMeterRegistry()).getOrder())
                .as("OtlpSpanExportAdvisor").isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(new GenAiMetricsAdvisor(
                new SimpleMeterRegistry(),
                mock(com.operativus.agentmanager.control.finops.service.LiveValuationEngine.class),
                mock(com.operativus.agentmanager.control.approval.HitlPauseHandler.class),
                mock(com.operativus.agentmanager.core.event.AgentRunEventBus.class),
                mock(com.operativus.agentmanager.control.finops.service.BurnRateMonitorService.class),
                null).getOrder())
                .as("GenAiMetricsAdvisor").isEqualTo(Integer.MAX_VALUE);
    }
}
