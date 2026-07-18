package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.BaselineRequest;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.HistoricalTrendPoint;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.CostAllocationEntry;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ModelCostSlice;
import com.operativus.agentmanager.control.finops.model.FinOpsApiRecords.ValuationRateRequest;
import com.operativus.agentmanager.control.finops.model.FinOpsRecords.ModelValuationRate;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService.WindowAccumulator;
import com.operativus.agentmanager.control.finops.service.FinOpsAnalyticsService;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for the {@link FinOpsAdminController}.
 * Uses standalone MockMvc setup with Mockito, following the established
 * {@code AgentAdminControllerTest} pattern.
 *
 * Coverage: All four REST endpoints including happy path, edge cases, and validation failures.
 */
@ExtendWith(MockitoExtension.class)
public class FinOpsAdminControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LiveValuationEngine valuationEngine;

    @Mock
    private BurnRateMonitorService burnRateMonitor;

    @Mock
    private FinOpsAnalyticsService analyticsService;

    @Mock
    private com.operativus.agentmanager.control.repository.AgentRepository agentRepository;

    @Mock
    private MeterRegistry meterRegistry;

    private FinOpsAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new FinOpsAdminController(valuationEngine, burnRateMonitor, analyticsService,
                agentRepository, meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validatorFactory())
                .build();
    }

    private static LocalValidatorFactoryBean validatorFactory() {
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        return factory;
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/finops/valuation-rates
    // -------------------------------------------------------------------------

    @Test
    void getValuationRates_ReturnsAllRates() throws Exception {
        Map<String, ModelValuationRate> snapshot = Map.of(
            "gpt-4o", new ModelValuationRate("gpt-4o", 2.50, 10.00),
            "claude-3-5-sonnet", new ModelValuationRate("claude-3-5-sonnet", 3.00, 15.00)
        );
        Mockito.when(valuationEngine.getRateSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/finops/valuation-rates")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getValuationRates_EmptyCache_ReturnsEmptyList() throws Exception {
        Mockito.when(valuationEngine.getRateSnapshot()).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/finops/valuation-rates")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/finops/valuation-rates
    // -------------------------------------------------------------------------

    @Test
    void updateValuationRate_ValidRequest_Returns200() throws Exception {
        ValuationRateRequest request = new ValuationRateRequest("gpt-4o", 2.50, 10.00, 0.0, 0.0);

        mockMvc.perform(put("/api/v1/finops/valuation-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value("gpt-4o"))
                .andExpect(jsonPath("$.inputRatePerKTokens").value(2.50))
                .andExpect(jsonPath("$.outputRatePerKTokens").value(10.00));

        verify(valuationEngine).register(any(ModelValuationRate.class));
    }

    @Test
    void updateValuationRate_BlankModelId_Returns400() throws Exception {
        String invalidPayload = """
            {
                "modelId": "",
                "inputRatePerKTokens": 2.50,
                "outputRatePerKTokens": 10.00,
                "cachedInputRatePerKTokens": 0.0,
                "reasoningRatePerKTokens": 0.0
            }
            """;

        mockMvc.perform(put("/api/v1/finops/valuation-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateValuationRate_NegativeRate_Returns400() throws Exception {
        String invalidPayload = """
            {
                "modelId": "gpt-4o",
                "inputRatePerKTokens": -1.00,
                "outputRatePerKTokens": 10.00,
                "cachedInputRatePerKTokens": 0.0,
                "reasoningRatePerKTokens": 0.0
            }
            """;

        mockMvc.perform(put("/api/v1/finops/valuation-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/finops/burn-rates/active
    // -------------------------------------------------------------------------

    @Test
    void getActiveBurnRates_ReturnsActiveWindows() throws Exception {
        WindowAccumulator accumulator = Mockito.mock(WindowAccumulator.class);
        Mockito.when(accumulator.getCumulativeUsd()).thenReturn(5.25);

        Map<String, WindowAccumulator> windows = Map.of("session-abc", accumulator);
        Mockito.when(burnRateMonitor.getActiveWindows()).thenReturn(windows);

        mockMvc.perform(get("/api/v1/finops/burn-rates/active")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value("session-abc"))
                .andExpect(jsonPath("$[0].cumulativeUsd").value(5.25));
    }

    @Test
    void getActiveBurnRates_NoActiveSessions_ReturnsEmptyList() throws Exception {
        Mockito.when(burnRateMonitor.getActiveWindows()).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/finops/burn-rates/active")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/finops/baselines/{agentId}
    // -------------------------------------------------------------------------

    @Test
    void updateBaseline_ValidRequest_Returns200() throws Exception {
        BaselineRequest request = new BaselineRequest(1.50);

        // Tenant guard added: controller pre-checks
        // agentRepository.existsByIdAndOrgId(agentId, callerOrgId). Wire both for the happy path.
        bindSecurityContextWithOrg("test-org");
        Mockito.when(agentRepository.existsByIdAndOrgId(eq("agent-alpha"), eq("test-org")))
                .thenReturn(true);
        try {
            mockMvc.perform(put("/api/v1/finops/baselines/agent-alpha")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agentId").value("agent-alpha"))
                    .andExpect(jsonPath("$.baselineUsdPerHour").value(1.50));

            verify(burnRateMonitor).registerBaseline(eq("agent-alpha"), eq(1.50));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static void bindSecurityContextWithOrg(String orgId) {
        com.operativus.agentmanager.control.security.UserDetailsImpl principal =
                new com.operativus.agentmanager.control.security.UserDetailsImpl(
                        java.util.UUID.randomUUID(), "test-admin", "test@local",
                        orgId, false, "{noop}pass", java.util.List.of());
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        var ctx = new org.springframework.security.core.context.SecurityContextImpl(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
    }

    @Test
    void updateBaseline_NegativeBaseline_Returns400() throws Exception {
        String invalidPayload = """
            {
                "baselineUsdPerHour": -5.00
            }
            """;

        mockMvc.perform(put("/api/v1/finops/baselines/agent-alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateBaseline_ZeroBaseline_Returns400() throws Exception {
        String invalidPayload = """
            {
                "baselineUsdPerHour": 0.0
            }
            """;

        mockMvc.perform(put("/api/v1/finops/baselines/agent-alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateBaseline_MissingBody_ReturnsError() throws Exception {
        mockMvc.perform(put("/api/v1/finops/baselines/agent-alpha")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 400 || status == 500 :
                        "Expected 400 or 500 for missing body, got " + status;
                });
    }

    // -------------------------------------------------------------------------
    // Analytics endpoints — org-scoping (IDOR fix)
    // -------------------------------------------------------------------------

    @Test
    void getHistoricalTrends_WithBoundOrgId_PassesOrgIdToService() throws Exception {
        Mockito.when(analyticsService.getHistoricalTrends(7, "org-a"))
               .thenReturn(List.of(new HistoricalTrendPoint("2026-05-07", 3L, 0.24)));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/finops/trends"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].date").value("2026-05-07"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        verify(analyticsService).getHistoricalTrends(7, "org-a");
    }

    @Test
    void getHistoricalTrends_WithoutOrgHeader_PassesNullOrgIdToService() throws Exception {
        Mockito.when(analyticsService.getHistoricalTrends(eq(7), isNull()))
               .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/finops/trends"))
                .andExpect(status().isOk());

        verify(analyticsService).getHistoricalTrends(eq(7), isNull());
    }

    @Test
    void getCostAllocations_WithBoundOrgId_PassesOrgIdToService() throws Exception {
        Mockito.when(analyticsService.getCostAllocations(7, "org-a"))
               .thenReturn(List.of(new CostAllocationEntry("agent", "agent-1", 5L, 100.0)));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/finops/allocations"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1));
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        verify(analyticsService).getCostAllocations(7, "org-a");
    }

    @Test
    void getCostAllocationsByModel_WithBoundOrgId_PassesOrgIdToService() throws Exception {
        Mockito.when(analyticsService.getCostAllocationsByModel(7, "org-a"))
               .thenReturn(List.of(new ModelCostSlice("gpt-4o", 10L, 100.0, 0.80)));

        ScopedValue.where(AgentContextHolder.orgId, "org-a").run(() -> {
            try {
                mockMvc.perform(get("/api/v1/finops/allocations/by-model"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].modelId").value("gpt-4o"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        verify(analyticsService).getCostAllocationsByModel(7, "org-a");
    }
}
