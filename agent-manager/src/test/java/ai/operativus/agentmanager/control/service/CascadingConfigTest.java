package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.GlobalSettingRepository;
import ai.operativus.agentmanager.core.entity.FinOpsRiskTier;
import ai.operativus.agentmanager.core.entity.GlobalSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CascadingConfigTest {

    @Mock
    private GlobalSettingRepository globalSettingRepository;

    @InjectMocks
    private SettingsService settingsService;

    // --- Temperature Cascading ---

    @Test
    void resolveTemperature_AgentValueTakesPrecedence() {
        assertEquals(0.3, settingsService.resolveTemperature(0.3));
    }

    @Test
    void resolveTemperature_FallsToGlobalSetting() {
        when(globalSettingRepository.findById("DEFAULT_TEMPERATURE"))
                .thenReturn(Optional.of(new GlobalSetting("DEFAULT_TEMPERATURE", "0.5", "")));

        assertEquals(0.5, settingsService.resolveTemperature(null));
    }

    @Test
    void resolveTemperature_FallsToHardcodedDefault() {
        when(globalSettingRepository.findById("DEFAULT_TEMPERATURE"))
                .thenReturn(Optional.empty());

        assertEquals(0.7, settingsService.resolveTemperature(null));
    }

    // --- TopP Cascading ---

    @Test
    void resolveTopP_AgentValueTakesPrecedence() {
        assertEquals(0.8, settingsService.resolveTopP(0.8));
    }

    @Test
    void resolveTopP_FallsToHardcodedDefault() {
        when(globalSettingRepository.findById("DEFAULT_TOP_P"))
                .thenReturn(Optional.empty());

        assertEquals(0.9, settingsService.resolveTopP(null));
    }

    // --- SecurityTier Cascading ---

    @Test
    void resolveSecurityTier_AgentValueTakesPrecedence() {
        assertEquals(3, settingsService.resolveSecurityTier(3));
    }

    @Test
    void resolveSecurityTier_FallsToGlobalSetting() {
        when(globalSettingRepository.findById("DEFAULT_SECURITY_TIER"))
                .thenReturn(Optional.of(new GlobalSetting("DEFAULT_SECURITY_TIER", "2", "")));

        assertEquals(2, settingsService.resolveSecurityTier(null));
    }

    @Test
    void resolveSecurityTier_FallsToHardcodedDefault() {
        when(globalSettingRepository.findById("DEFAULT_SECURITY_TIER"))
                .thenReturn(Optional.empty());

        assertEquals(1, settingsService.resolveSecurityTier(null));
    }

    // --- FinOpsRiskTier Cascading ---

    @Test
    void resolveFinOpsRiskTier_AgentValueTakesPrecedence() {
        assertEquals(FinOpsRiskTier.CRITICAL, settingsService.resolveFinOpsRiskTier(FinOpsRiskTier.CRITICAL));
    }

    @Test
    void resolveFinOpsRiskTier_FallsToGlobalSetting() {
        when(globalSettingRepository.findById("DEFAULT_FINOPS_RISK_TIER"))
                .thenReturn(Optional.of(new GlobalSetting("DEFAULT_FINOPS_RISK_TIER", "STRICT", "")));

        assertEquals(FinOpsRiskTier.STRICT, settingsService.resolveFinOpsRiskTier(null));
    }

    @Test
    void resolveFinOpsRiskTier_FallsToHardcodedDefault() {
        when(globalSettingRepository.findById("DEFAULT_FINOPS_RISK_TIER"))
                .thenReturn(Optional.empty());

        assertEquals(FinOpsRiskTier.LOW_RISK, settingsService.resolveFinOpsRiskTier(null));
    }

    // --- FinOpsTokenBudget Cascading ---

    @Test
    void resolveFinOpsTokenBudget_ExplicitBudgetTakesPrecedence() {
        assertEquals(999L, settingsService.resolveFinOpsTokenBudget(999L, FinOpsRiskTier.CRITICAL));
    }

    @Test
    void resolveFinOpsTokenBudget_FallsToRiskTierDefault() {
        Long budget = settingsService.resolveFinOpsTokenBudget(null, FinOpsRiskTier.STRICT);
        assertEquals(500_000L, budget);
    }

    @Test
    void resolveFinOpsTokenBudget_UnrestrictedReturnsNull() {
        Long budget = settingsService.resolveFinOpsTokenBudget(null, FinOpsRiskTier.UNRESTRICTED);
        assertNull(budget);
    }

    // --- MaxConcurrent Cascading ---

    @Test
    void resolveMaxConcurrent_AgentValueTakesPrecedence() {
        assertEquals(10, settingsService.resolveMaxConcurrentExecutions(10));
    }

    @Test
    void resolveMaxConcurrent_FallsToHardcodedDefault() {
        when(globalSettingRepository.findById("DEFAULT_MAX_CONCURRENT_EXECUTIONS"))
                .thenReturn(Optional.empty());

        assertEquals(5, settingsService.resolveMaxConcurrentExecutions(null));
    }
}
