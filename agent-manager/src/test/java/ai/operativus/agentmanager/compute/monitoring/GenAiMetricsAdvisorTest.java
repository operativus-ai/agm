package ai.operativus.agentmanager.compute.monitoring;

import ai.operativus.agentmanager.control.approval.HitlPauseHandler;
import ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException;
import ai.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import ai.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GenAiMetricsAdvisor}. Verifies:
 * - Token usage metrics are published with correct OTel semantic tags
 * - Input and output tokens are recorded as separate DistributionSummary entries
 * - Provider name derivation from model ID strings
 * - Null/missing token usage gracefully handled without exceptions
 * - Advisor does not mutate the request or response (pure observation)
 * - Chargeback dimension tags (org_unit, project_code, team_id, session_id) are appended to telemetry
 * - FinOps budget exhaustion triggers FinOpsBudgetExhaustedException and invokes HitlPauseHandler
 * - Budget check is skipped when no AgentContext is bound (no-op for unbound contexts)
 */
class GenAiMetricsAdvisorTest {

    private SimpleMeterRegistry meterRegistry;
    private LiveValuationEngine valuationEngine;
    private HitlPauseHandler hitlPauseHandler;
    private AgentRunEventBus eventBus;
    private BurnRateMonitorService burnRateMonitor;
    private GenAiMetricsAdvisor advisor;
    private CallAdvisorChain chain;
    private ChatClientRequest request;

    @BeforeEach
    void setUp() {
        meterRegistry    = new SimpleMeterRegistry();
        ai.operativus.agentmanager.control.repository.FinOpsValuationRateRepository mockRepo = mock(ai.operativus.agentmanager.control.repository.FinOpsValuationRateRepository.class);
        valuationEngine  = new LiveValuationEngine(mockRepo, 300000L);
        hitlPauseHandler = mock(HitlPauseHandler.class);
        eventBus         = mock(AgentRunEventBus.class);
        burnRateMonitor  = mock(BurnRateMonitorService.class);
        advisor          = new GenAiMetricsAdvisor(meterRegistry, valuationEngine, hitlPauseHandler, eventBus, burnRateMonitor, null);
        chain            = mock(CallAdvisorChain.class);
        request          = mock(ChatClientRequest.class);

        Prompt prompt = mock(Prompt.class);
        ChatOptions options = mock(ChatOptions.class);
        when(options.getModel()).thenReturn("gemini-2.5-pro");
        when(prompt.getOptions()).thenReturn(options);
        when(request.prompt()).thenReturn(prompt);
    }

    // -------------------------------------------------------------------------
    // Core metric recording
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getName returns GenAiMetricsAdvisor")
    void testGetName() {
        assertThat(advisor.getName()).isEqualTo("GenAiMetricsAdvisor");
    }

    @Test
    @DisplayName("getOrder returns MAX_VALUE (runs last)")
    void testGetOrder() {
        assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Records input and output token metrics with OTel semantic tags")
    void testRecordsTokenMetrics() {
        ChatClientResponse response = mockResponseWithUsage(150, 75, "gemini-2.5-pro");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);

        var inputMetric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", "input")
                .summary();
        assertThat(inputMetric).isNotNull();
        assertThat(inputMetric.count()).isEqualTo(1);
        assertThat(inputMetric.totalAmount()).isEqualTo(150);

        var outputMetric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", "output")
                .summary();
        assertThat(outputMetric).isNotNull();
        assertThat(outputMetric.count()).isEqualTo(1);
        assertThat(outputMetric.totalAmount()).isEqualTo(75);
    }

    @Test
    @DisplayName("Tags include correct provider name for Google models")
    void testGoogleProviderTag() {
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gemini-2.5-flash");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "google")
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("Tags include correct provider name for OpenAI models")
    void testOpenAiProviderTag() {
        ChatClientResponse response = mockResponseWithUsage(200, 100, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "openai")
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("Tags include correct provider name for Anthropic models")
    void testAnthropicProviderTag() {
        ChatClientResponse response = mockResponseWithUsage(300, 150, "claude-3-5-sonnet-20240620");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.provider.name", "anthropic")
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("Null chatResponse does not throw exception")
    void testNullChatResponse() {
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(null);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
        assertThat(meterRegistry.find("gen_ai.client.token.usage").summary()).isNull();
    }

    @Test
    @DisplayName("Missing token usage metadata does not throw exception")
    void testMissingUsageMetadata() {
        AssistantMessage msg = new AssistantMessage("Hello");
        Generation gen = new Generation(msg);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().build();
        ChatResponse chatResponse = new ChatResponse(List.of(gen), metadata);

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("Response passthrough: advisor does not modify request or response")
    void testPassthroughBehavior() {
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gemini-2.5-pro");
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
    }

    // -------------------------------------------------------------------------
    // Chargeback tag dimension tests (Gap 4.1)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Chargeback tags are appended to token usage metrics — org_unit dimension present")
    void testChargebackTagOrgUnitPresentInMetrics() {
        ChatClientResponse response = mockResponseWithUsage(500, 250, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        // The metric must be registered with finops.org_unit tag
        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("finops.org_unit", "unknown-org") // default fallback when no context bound
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("Chargeback tags are appended to token usage metrics — project_code dimension present")
    void testChargebackTagProjectCodePresentInMetrics() {
        ChatClientResponse response = mockResponseWithUsage(200, 100, "claude-3-5-sonnet");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("finops.project_code", "unattributed") // default fallback
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("Chargeback tags are appended to token usage metrics — session_id dimension present")
    void testChargebackTagSessionIdPresentInMetrics() {
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gpt-4o-mini");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        // Session ID defaults to "unknown-session" when AgentContextHolder is unbound
        var metric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("finops.session_id", "unknown-session")
                .summary();
        assertThat(metric).isNotNull();
    }

    @Test
    @DisplayName("USD cost metric is published with chargeback tags when valuation engine resolves model")
    void testUsdCostMetricPublished() {
        ChatClientResponse response = mockResponseWithUsage(1000, 500, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        // gen_ai.client.token.cost.usd metric must be published
        var costMetric = meterRegistry.find("gen_ai.client.token.cost.usd").summary();
        assertThat(costMetric).isNotNull();
        // 1000 input tokens at $2.50/1k + 500 output tokens at $10.00/1k = $2.50 + $5.00 = $7.50
        assertThat(costMetric.totalAmount()).isGreaterThan(0.0);
    }

    // -------------------------------------------------------------------------
    // Mid-flight FinOps enforcement / stream termination tests (Gap 1)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No budget exception when AgentContext is unbound (no-op path)")
    void testNoBudgetExceptionWhenContextUnbound() {
        // AgentContextHolder.CONTEXT is not bound in test scope — budget check is skipped
        ChatClientResponse response = mockResponseWithUsage(10_000, 5_000, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        // Should complete without exception — no budget ceiling is bound
        ChatClientResponse result = advisor.adviseCall(request, chain);

        assertThat(result).isSameAs(response);
        verify(hitlPauseHandler, never()).pauseForBudgetExhaustion(any());
    }

    @Test
    @DisplayName("HitlPauseHandler is never invoked when no FinOps exception is thrown")
    void testHitlPauseHandlerNotCalledOnNormalCall() {
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gemini-2.5-pro");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        verify(hitlPauseHandler, never()).pauseForBudgetExhaustion(any());
    }

    @Test
    @DisplayName("FinOpsBudgetExhaustedException carries accurate session and cost context")
    void testBudgetExhaustedExceptionFields() {
        FinOpsBudgetExhaustedException ex = new FinOpsBudgetExhaustedException(
            "session-abc", "agent-xyz", "run-001", "gpt-4o", 12.75, 10.00);

        assertThat(ex.getSessionId()).isEqualTo("session-abc");
        assertThat(ex.getAgentId()).isEqualTo("agent-xyz");
        assertThat(ex.getRunId()).isEqualTo("run-001");
        assertThat(ex.getModelId()).isEqualTo("gpt-4o");
        assertThat(ex.getCumulativeUsd()).isEqualTo(12.75);
        assertThat(ex.getBudgetCeilingUsd()).isEqualTo(10.00);
        assertThat(ex.getMessage()).contains("agent-xyz");
        assertThat(ex.getMessage()).contains("session-abc");
        assertThat(ex.getMessage()).contains("12.7500");
        assertThat(ex.getMessage()).contains("10.0000");
    }

    @Test
    @DisplayName("FinOpsBudgetExhaustedException message includes model ID")
    void testBudgetExhaustedExceptionMessageIncludesModel() {
        FinOpsBudgetExhaustedException ex = new FinOpsBudgetExhaustedException(
            "s1", "a1", "r1", "claude-3-opus", 100.0, 50.0);

        assertThat(ex.getMessage()).contains("claude-3-opus");
    }

    @Test
    @DisplayName("Advisor chain is still called before FinOps checks — response is obtained first")
    void testChainIsCalledBeforeFinOpsEnforcement() {
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gemini-2.5-pro");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        // chain.nextCall must be invoked exactly once regardless of budget state
        verify(chain, times(1)).nextCall(request);
    }

    @Test
    @DisplayName("BurnRateMonitorService.recordSpend is invoked on every successful inference call")
    void testBurnRateMonitorWiredOnInferencePath() {
        // Pins SDD G1 (agm-finops-cost-enforcement-36): the wiring is one-way and unconditional
        // — the monitor receives every spend event regardless of whether a budget ceiling is bound.
        // A future refactor that drops this call site would re-introduce the dead-code regression
        // (BurnRateMonitorService.recordSpend had ZERO callers before this PR).
        ChatClientResponse response = mockResponseWithUsage(1_000, 500, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        advisor.adviseCall(request, chain);

        verify(burnRateMonitor, times(1))
                .recordSpend(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("BurnRateMonitorService.recordSpend is invoked on the embedding cost path")
    void testBurnRateMonitorWiredOnEmbeddingPath() {
        // Same wiring pin as the inference path, but for accumulateEmbeddingCost. Embeddings
        // are accumulated separately from inference and must reach the monitor on the same hook
        // so the rolling-window alerting captures total per-session cost, not just LLM cost.
        advisor.accumulateEmbeddingCost("sess-emb", "text-embedding-3-small", 100_000L);

        verify(burnRateMonitor, atLeastOnce())
                .recordSpend(eq("sess-emb"), anyString(), anyDouble());
    }

    @Test
    @DisplayName("recordSpend agentId arg is the bound agentId, not the bound runId (Bug #44 regression guard)")
    void testRecordSpend_UsesAgentIdNotRunId_WhenManifestUnbound() {
        // Bug #44: resolveAgentId() previously fell back to getCurrentRunId() when the
        // HTTP-side manifest was unbound. Background runs + scheduler-spawned runs hit
        // that path, so anomaly entries reported a runId in the agentId column.
        // The fix is to fall back to AgentContextHolder.getAgentId() instead.
        ChatClientResponse response = mockResponseWithUsage(100, 50, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        // Bind currentRunId and agentId to distinguishable values. Do NOT bind
        // the HTTP-side AgentContext (so the manifest branch returns null and the
        // fallback path is exercised).
        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.currentRunId, "run-bug44")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.agentId, "agent-bug44")
                .run(() -> advisor.adviseCall(request, chain));

        ArgumentCaptor<String> agentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(burnRateMonitor, atLeastOnce())
                .recordSpend(anyString(), agentIdCaptor.capture(), anyDouble());
        assertThat(agentIdCaptor.getValue())
                .as("recordSpend must receive the bound agentId, not the runId")
                .isEqualTo("agent-bug44")
                .isNotEqualTo("run-bug44");
    }

    // -------------------------------------------------------------------------
    // BUDGET_EXCEEDED event emission (plan §5.12 emit points)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Budget halt on embedding cost publishes BUDGET_EXCEEDED before throwing")
    void testAccumulateEmbeddingCost_BudgetHalt_EmitsBudgetExceeded() {
        ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext ctx =
                new ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext(
                        "team-1", "human-1", 0.0001, null);

        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.currentRunId, "run-budget-1")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.sessionId, "sess-1")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.agentId, "agent-1")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, "org-1")
                .where(ai.operativus.agentmanager.control.security.AgentContextHolder.CONTEXT, ctx)
                .run(() -> assertThatThrownBy(
                        () -> advisor.accumulateEmbeddingCost("sess-1", "gpt-4o", 50_000L))
                        .isInstanceOf(FinOpsBudgetExhaustedException.class));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        AgentRunEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(AgentRunEventType.BUDGET_EXCEEDED);
        assertThat(event.runId()).isEqualTo("run-budget-1");
        assertThat(event.payload().get("modelId")).isEqualTo("gpt-4o");
        assertThat(event.payload().get("costKind")).isEqualTo("embedding");
        assertThat((Double) event.payload().get("budgetCeilingUsd")).isEqualTo(0.0001);
        assertThat((Double) event.payload().get("cumulativeUsd")).isGreaterThan(0.0001);
    }

    @Test
    @DisplayName("Budget halt without bound runId does not publish BUDGET_EXCEEDED")
    void testAccumulateEmbeddingCost_BudgetHalt_UnboundRunId_SkipsEmission() {
        ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext ctx =
                new ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext(
                        "team-1", "human-1", 0.0001, null);

        // No core.callback.AgentContextHolder.currentRunId bound — emission must skip
        ScopedValue.where(ai.operativus.agentmanager.control.security.AgentContextHolder.CONTEXT, ctx)
                .run(() -> assertThatThrownBy(
                        () -> advisor.accumulateEmbeddingCost("sess-1", "gpt-4o", 50_000L))
                        .isInstanceOf(FinOpsBudgetExhaustedException.class));

        verify(eventBus, never()).publish(any());
    }

    // -------------------------------------------------------------------------
    // Run-scoped cost accumulation (feeds AgentRun.totalCostUsd + usage summary)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("adviseCall: per-call USD cost is written to the run-scoped telemetry accumulator (addCostUsd had no callers before)")
    void adviseCall_AccumulatesCostIntoRunTelemetry() {
        ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator telemetry =
                new ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator();
        ChatClientResponse response = mockResponseWithUsage(1_000, 500, "gpt-4o");
        when(chain.nextCall(any())).thenReturn(response);

        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.telemetry, telemetry)
                .run(() -> advisor.adviseCall(request, chain));

        assertThat(telemetry.getTotalCostUsd())
                .as("GenAiMetricsAdvisor must fold per-call USD into the run accumulator so the run row is non-null")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("adviseStream: per-call USD cost is written to the run-scoped telemetry accumulator on the streaming path too")
    void adviseStream_AccumulatesCostIntoRunTelemetry() {
        ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator telemetry =
                new ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator();
        ChatClientResponse usageChunk = mockResponseWithUsage(1_000, 500, "gpt-4o");
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(usageChunk));

        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.telemetry, telemetry)
                .run(() -> StepVerifier.create(advisor.adviseStream(request, streamChain))
                        .expectNext(usageChunk)
                        .verifyComplete());

        assertThat(telemetry.getTotalCostUsd()).isGreaterThan(0.0);
    }

    // -------------------------------------------------------------------------
    // Streaming path — token/cost metrics + FinOps enforcement (previously bypassed)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("adviseStream: records token + cost metrics and burn-rate spend from the terminal usage chunk")
    void adviseStream_RecordsMetricsFromTerminalChunk() {
        ChatClientResponse contentChunk = mockResponseWithoutUsage();
        ChatClientResponse usageChunk = mockResponseWithUsage(1_000, 500, "gpt-4o");
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(contentChunk, usageChunk));

        StepVerifier.create(advisor.adviseStream(request, streamChain))
                .expectNext(contentChunk, usageChunk)
                .verifyComplete();

        var inputMetric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", "input").summary();
        assertThat(inputMetric).isNotNull();
        assertThat(inputMetric.totalAmount()).isEqualTo(1_000);

        var outputMetric = meterRegistry.find("gen_ai.client.token.usage")
                .tag("gen_ai.token.type", "output").summary();
        assertThat(outputMetric).isNotNull();
        assertThat(outputMetric.totalAmount()).isEqualTo(500);

        assertThat(meterRegistry.find("gen_ai.client.token.cost.usd").summary()).isNotNull();
        verify(burnRateMonitor, times(1)).recordSpend(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("adviseStream: a content-only stream (no usage chunk) records nothing and completes normally")
    void adviseStream_NoUsage_RecordsNothing() {
        ChatClientResponse contentChunk = mockResponseWithoutUsage();
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(contentChunk));

        StepVerifier.create(advisor.adviseStream(request, streamChain))
                .expectNext(contentChunk)
                .verifyComplete();

        assertThat(meterRegistry.find("gen_ai.client.token.usage").summary()).isNull();
        verify(hitlPauseHandler, never()).pauseForBudgetExhaustion(any());
    }

    @Test
    @DisplayName("adviseStream: a ceiling breach pauses via HITL, publishes BUDGET_EXCEEDED, and terminates the stream with the FinOps error after content")
    void adviseStream_BudgetBreach_PausesAndTerminatesWithError() {
        ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext ctx =
                new ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext(
                        "team-1", "human-1", 0.0001, null); // remainingBudget ceiling = $0.0001

        ChatClientResponse contentChunk = mockResponseWithoutUsage();
        ChatClientResponse usageChunk = mockResponseWithUsage(50_000, 10_000, "gpt-4o");
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(any())).thenReturn(Flux.just(contentChunk, usageChunk));

        ScopedValue.where(ai.operativus.agentmanager.core.callback.AgentContextHolder.currentRunId, "run-stream-budget")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.sessionId, "sess-stream")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.agentId, "agent-stream")
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, "org-stream")
                .where(ai.operativus.agentmanager.control.security.AgentContextHolder.CONTEXT, ctx)
                .run(() -> StepVerifier.create(advisor.adviseStream(request, streamChain))
                        // All content chunks reach the client; the breach errors the stream AFTER them.
                        .expectNext(contentChunk, usageChunk)
                        .expectError(FinOpsBudgetExhaustedException.class)
                        .verify());

        verify(hitlPauseHandler, times(1)).pauseForBudgetExhaustion(any(FinOpsBudgetExhaustedException.class));

        ArgumentCaptor<AgentRunEvent> captor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(AgentRunEventType.BUDGET_EXCEEDED);
        assertThat(captor.getValue().payload().get("costKind")).isEqualTo("inference");
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private ChatClientResponse mockResponseWithoutUsage() {
        AssistantMessage assistantMessage = new AssistantMessage("partial ");
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation), ChatResponseMetadata.builder().build());

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    private ChatClientResponse mockResponseWithUsage(int inputTokens, int outputTokens, String modelName) {
        AssistantMessage assistantMessage = new AssistantMessage("Test response");
        Generation generation = new Generation(assistantMessage);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(inputTokens, outputTokens))
                .model(modelName)
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }
}
