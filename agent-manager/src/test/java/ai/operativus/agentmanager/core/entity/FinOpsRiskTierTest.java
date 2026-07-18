package ai.operativus.agentmanager.core.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FinOpsRiskTierTest {

    @Test
    void fromString_ValidNames() {
        assertEquals(FinOpsRiskTier.UNRESTRICTED, FinOpsRiskTier.fromString("UNRESTRICTED"));
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString("LOW_RISK"));
        assertEquals(FinOpsRiskTier.MODERATE_RISK, FinOpsRiskTier.fromString("MODERATE_RISK"));
        assertEquals(FinOpsRiskTier.STRICT, FinOpsRiskTier.fromString("STRICT"));
        assertEquals(FinOpsRiskTier.CRITICAL, FinOpsRiskTier.fromString("CRITICAL"));
    }

    @Test
    void fromString_CaseInsensitive() {
        assertEquals(FinOpsRiskTier.STRICT, FinOpsRiskTier.fromString("strict"));
        assertEquals(FinOpsRiskTier.CRITICAL, FinOpsRiskTier.fromString("Critical"));
    }

    @Test
    void fromString_NumericLevel() {
        assertEquals(FinOpsRiskTier.UNRESTRICTED, FinOpsRiskTier.fromString("0"));
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString("1"));
        assertEquals(FinOpsRiskTier.MODERATE_RISK, FinOpsRiskTier.fromString("2"));
        assertEquals(FinOpsRiskTier.STRICT, FinOpsRiskTier.fromString("3"));
        assertEquals(FinOpsRiskTier.CRITICAL, FinOpsRiskTier.fromString("4"));
    }

    @Test
    void fromString_NullOrBlank_DefaultsToLowRisk() {
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString(null));
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString(""));
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString("   "));
    }

    @Test
    void fromString_InvalidValue_DefaultsToLowRisk() {
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString("INVALID"));
        assertEquals(FinOpsRiskTier.LOW_RISK, FinOpsRiskTier.fromString("99"));
    }

    @Test
    void defaultTokenBudgets_AreCorrect() {
        assertNull(FinOpsRiskTier.UNRESTRICTED.getDefaultTokenBudget());
        assertEquals(5_000_000L, FinOpsRiskTier.LOW_RISK.getDefaultTokenBudget());
        assertEquals(1_000_000L, FinOpsRiskTier.MODERATE_RISK.getDefaultTokenBudget());
        assertEquals(500_000L, FinOpsRiskTier.STRICT.getDefaultTokenBudget());
        assertEquals(100_000L, FinOpsRiskTier.CRITICAL.getDefaultTokenBudget());
    }

    @Test
    void modelDowngradeEligibility() {
        assertFalse(FinOpsRiskTier.UNRESTRICTED.isModelDowngradeEligible());
        assertFalse(FinOpsRiskTier.LOW_RISK.isModelDowngradeEligible());
        assertFalse(FinOpsRiskTier.MODERATE_RISK.isModelDowngradeEligible());
        assertTrue(FinOpsRiskTier.STRICT.isModelDowngradeEligible());
        assertTrue(FinOpsRiskTier.CRITICAL.isModelDowngradeEligible());
    }

    @Test
    void burnRateMultiplierThresholds() {
        assertNull(FinOpsRiskTier.UNRESTRICTED.getBurnRateMultiplierThreshold());
        assertEquals(10.0, FinOpsRiskTier.LOW_RISK.getBurnRateMultiplierThreshold());
        assertEquals(5.0, FinOpsRiskTier.MODERATE_RISK.getBurnRateMultiplierThreshold());
        assertEquals(3.0, FinOpsRiskTier.STRICT.getBurnRateMultiplierThreshold());
        assertEquals(2.0, FinOpsRiskTier.CRITICAL.getBurnRateMultiplierThreshold());
    }

    @Test
    void levelsAreOrdered() {
        assertTrue(FinOpsRiskTier.UNRESTRICTED.getLevel() < FinOpsRiskTier.LOW_RISK.getLevel());
        assertTrue(FinOpsRiskTier.LOW_RISK.getLevel() < FinOpsRiskTier.MODERATE_RISK.getLevel());
        assertTrue(FinOpsRiskTier.MODERATE_RISK.getLevel() < FinOpsRiskTier.STRICT.getLevel());
        assertTrue(FinOpsRiskTier.STRICT.getLevel() < FinOpsRiskTier.CRITICAL.getLevel());
    }
}
