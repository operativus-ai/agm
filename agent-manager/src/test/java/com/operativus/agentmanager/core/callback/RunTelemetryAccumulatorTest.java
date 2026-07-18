package com.operativus.agentmanager.core.callback;

import com.operativus.agentmanager.core.entity.AgentRun;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class RunTelemetryAccumulatorTest {

    @Test
    void applyTo_copiesAccumulatedFieldsOntoEntity() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.addInputTokens(1000);
        acc.addOutputTokens(500);
        acc.addReasoningTokens(200);
        acc.addCostUsd(0.042);
        acc.incrementLlmCalls();
        acc.incrementLlmCalls();
        acc.setModelIfAbsent("gpt-4");
        acc.setOrchestrationStrategy("ROUTER");
        acc.updateSafetyRiskScore(0.12);

        AgentRun run = new AgentRun();
        acc.applyTo(run);

        assertEquals(1000L, run.getInputTokens());
        assertEquals(500L, run.getOutputTokens());
        assertEquals(200L, run.getReasoningTokens());
        assertEquals(0, new BigDecimal("0.042000").compareTo(run.getTotalCostUsd()));
        assertEquals("gpt-4", run.getModel());
        assertEquals("ROUTER", run.getOrchestrationStrategy());
        assertEquals(new BigDecimal("0.120").setScale(3, RoundingMode.HALF_UP),
                run.getSafetyRiskScore());
        assertNotNull(run.getDurationMs());
        assertTrue(run.getDurationMs() >= 0);
    }

    @Test
    void applyTo_zeroCountersAreLeftNull() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        AgentRun run = new AgentRun();

        acc.applyTo(run);

        assertNull(run.getInputTokens());
        assertNull(run.getOutputTokens());
        assertNull(run.getReasoningTokens());
        assertNull(run.getTotalCostUsd());
        assertNull(run.getModel());
        assertNull(run.getErrorType());
        assertNull(run.getErrorMessage());
        assertNull(run.getSafetyRiskScore());
        assertNotNull(run.getDurationMs());
    }

    @Test
    void recordError_setsTypeMessageAndIncrementsCount() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.recordError("TimeoutException", "waited too long");

        AgentRun run = new AgentRun();
        acc.applyTo(run);

        assertEquals("TimeoutException", run.getErrorType());
        assertEquals("waited too long", run.getErrorMessage());
        assertEquals(1, acc.getErrorCount());
    }

    @Test
    void setModelIfAbsent_isFirstWriterWins() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.setModelIfAbsent("gpt-4");
        acc.setModelIfAbsent("claude-sonnet");

        assertEquals("gpt-4", acc.getModel());
    }

    @Test
    void addCostUsd_isRoundedToSixDecimals() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.addCostUsd(0.000001);
        acc.addCostUsd(0.000002);

        AgentRun run = new AgentRun();
        acc.applyTo(run);

        assertEquals(0, new BigDecimal("0.000003").compareTo(run.getTotalCostUsd()));
    }

    @Test
    void negativeTokenAdditionsAreIgnored() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        acc.addInputTokens(-5);
        acc.addOutputTokens(0);
        acc.addInputTokens(10);

        assertEquals(10L, acc.getTotalInputTokens());
        assertEquals(0L, acc.getTotalOutputTokens());
    }

    @Test
    void errorMessageIsTruncatedTo4000Chars() {
        RunTelemetryAccumulator acc = new RunTelemetryAccumulator();
        String huge = "x".repeat(5000);
        acc.recordError("RT", huge);

        AgentRun run = new AgentRun();
        acc.applyTo(run);

        assertEquals(4000, run.getErrorMessage().length());
    }
}
