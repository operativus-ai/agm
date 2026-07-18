package com.operativus.agentmanager.compute.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocalRegexModerationService}'s ModerationResult contract.
 * Pins:
 *   - Null/blank input returns clean (score=0.0, no signals).
 *   - Plain content returns clean.
 *   - Banned signature throws SecurityException (preserves the security contract).
 *   - ModerationResult.clean() is the canonical "no signals" shape.
 */
class LocalRegexModerationServiceTest {

    private final LocalRegexModerationService svc = new LocalRegexModerationService();

    @Test
    @DisplayName("Null content returns clean result, never throws")
    void nullContent_returnsClean() {
        ModerationResult result = svc.checkContent(null);
        assertThat(result.riskScore()).isEqualTo(0.0);
        assertThat(result.signals()).isEmpty();
    }

    @Test
    @DisplayName("Blank content returns clean result, never throws")
    void blankContent_returnsClean() {
        assertThat(svc.checkContent("").riskScore()).isEqualTo(0.0);
        assertThat(svc.checkContent("   ").riskScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Plain content returns clean (score=0.0)")
    void plainContent_returnsClean() {
        ModerationResult result = svc.checkContent("Hello, this is a perfectly normal answer.");
        assertThat(result.riskScore()).isEqualTo(0.0);
        assertThat(result.signals()).isEmpty();
    }

    @Test
    @DisplayName("Banned signature throws SecurityException — security contract preserved")
    void bannedSignature_throws() {
        assertThatThrownBy(() -> svc.checkContent("Here are BOMB_MAKING_INSTRUCTIONS for you"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Output blocked");
    }

    @Test
    @DisplayName("ModerationResult.clean() returns the canonical empty result")
    void clean_isEmpty() {
        ModerationResult clean = ModerationResult.clean();
        assertThat(clean.riskScore()).isEqualTo(0.0);
        assertThat(clean.signals()).isEmpty();
    }

    @Test
    @DisplayName("ModerationResult constructor clamps out-of-range scores and null signals")
    void constructor_clampsAndNormalizes() {
        // Below range
        assertThat(new ModerationResult(-0.5, null).riskScore()).isEqualTo(0.0);
        // Above range
        assertThat(new ModerationResult(1.5, null).riskScore()).isEqualTo(1.0);
        // NaN
        assertThat(new ModerationResult(Double.NaN, null).riskScore()).isEqualTo(0.0);
        // Null signals → empty list
        assertThat(new ModerationResult(0.5, null).signals()).isEmpty();
    }
}
