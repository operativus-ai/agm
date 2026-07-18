package com.operativus.agentmanager.compute.advisor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContentSafetyAdvisor}'s meter wiring.
 * Pins:
 *   - On clean scan: {@code agm.security.content_safety.scanned{outcome=ok}} increments,
 *     {@code agm.security.content_safety.risk_score} records the returned score.
 *   - On SecurityException: {@code agm.security.content_safety.scanned{outcome=blocked}}
 *     increments, exception propagates unchanged.
 *   - {@code advisor.duration_ms{advisor=content_safety}} timer always records (separate from outcome).
 */
class ContentSafetyAdvisorMeterTest {

    private SimpleMeterRegistry meterRegistry;
    private ModerationService moderationService;
    private ContentSafetyAdvisor advisor;
    private CallAdvisorChain chain;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        moderationService = mock(ModerationService.class);
        advisor = new ContentSafetyAdvisor(moderationService, meterRegistry);
        chain = mock(CallAdvisorChain.class);
    }

    @Test
    @DisplayName("Clean scan increments outcome=ok and records riskScore")
    void cleanScan_incrementsOkAndRecordsScore() {
        ChatClientResponse response = mockResponse("safe response");
        when(chain.nextCall(any())).thenReturn(response);
        when(moderationService.checkContent(any())).thenReturn(new ModerationResult(0.3, List.of("suspicious")));

        ChatClientRequest request = mock(ChatClientRequest.class);
        advisor.adviseCall(request, chain);

        double okCount = meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "ok").count();
        double blockedCount = meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "blocked").count();
        assertThat(okCount).isEqualTo(1.0);
        assertThat(blockedCount).isEqualTo(0.0);

        var summary = meterRegistry.summary("agm.security.content_safety.risk_score");
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("SecurityException increments outcome=blocked and propagates the exception")
    void blockedScan_incrementsBlockedAndRethrows() {
        ChatClientResponse response = mockResponse("BOMB_MAKING_INSTRUCTIONS");
        when(chain.nextCall(any())).thenReturn(response);
        when(moderationService.checkContent(any()))
                .thenThrow(new SecurityException("Output blocked due to safety violations"));

        ChatClientRequest request = mock(ChatClientRequest.class);
        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Output blocked");

        double okCount = meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "ok").count();
        double blockedCount = meterRegistry.counter("agm.security.content_safety.scanned", "outcome", "blocked").count();
        assertThat(okCount).isEqualTo(0.0);
        assertThat(blockedCount).isEqualTo(1.0);

        // Risk-score distribution is intentionally NOT recorded on blocks (the throw path skips it).
        var summary = meterRegistry.summary("agm.security.content_safety.risk_score");
        assertThat(summary.count()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Duration timer records on both ok and blocked paths")
    void durationTimer_recordsRegardlessOfOutcome() {
        ChatClientResponse response = mockResponse("hello");
        when(chain.nextCall(any())).thenReturn(response);

        // Clean path
        when(moderationService.checkContent(any())).thenReturn(ModerationResult.clean());
        ChatClientRequest request = mock(ChatClientRequest.class);
        advisor.adviseCall(request, chain);

        // Blocked path
        when(moderationService.checkContent(any())).thenThrow(new SecurityException("blocked"));
        try { advisor.adviseCall(request, chain); } catch (SecurityException ignored) {}

        var timer = meterRegistry.timer("advisor.duration_ms", "advisor", "content_safety");
        assertThat(timer.count()).isEqualTo(2L);
    }

    private ChatClientResponse mockResponse(String content) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation gen = new Generation(msg);
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }
}
