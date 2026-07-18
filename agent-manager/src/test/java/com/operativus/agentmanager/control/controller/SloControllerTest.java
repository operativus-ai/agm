package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.service.SloTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SloControllerTest {

    private MockMvc mockMvc;

    @Mock private SloTrackingService sloTrackingService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SloController(sloTrackingService)).build();
    }

    @Test
    void getSloStatus_MapsServiceRowsToTypedResponse() throws Exception {
        when(sloTrackingService.getSloStatus()).thenReturn(Map.of(
                "evaluated_at", "2026-04-23T12:00:00Z",
                "slos", List.of(
                        sloRow("Agent Response Latency P99", 30000.0, 2500.0, true, "ms"),
                        sloRow("Agent Success Rate", 0.95, 0.92, false, "ratio"))));

        mockMvc.perform(get("/api/v1/observability/slo-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sloName").value("Agent Response Latency P99"))
                .andExpect(jsonPath("$[0].target").value(30000.0))
                .andExpect(jsonPath("$[0].current").value(2500.0))
                .andExpect(jsonPath("$[0].compliant").value(true))
                .andExpect(jsonPath("$[0].unit").value("ms"))
                .andExpect(jsonPath("$[1].sloName").value("Agent Success Rate"))
                .andExpect(jsonPath("$[1].compliant").value(false));
    }

    @Test
    void getSloStatus_ServiceReturnsNoSlosKey_ReturnsEmptyList() throws Exception {
        when(sloTrackingService.getSloStatus()).thenReturn(Map.of("evaluated_at", "x"));

        mockMvc.perform(get("/api/v1/observability/slo-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getSloStatus_IntegerTargetCoercesToDouble() throws Exception {
        // The service produces `target` as a double literal today, but @Value parsing in a
        // future tweak could emit an Integer. Pin the Number-widening path.
        when(sloTrackingService.getSloStatus()).thenReturn(Map.of(
                "slos", List.of(sloRow("X", (Object) Integer.valueOf(30000), 1.0, true, "ms"))));

        mockMvc.perform(get("/api/v1/observability/slo-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].target").value(30000.0));
    }

    @Test
    void getSloStatus_MissingCompliantKey_DefaultsFalse() throws Exception {
        Map<String, Object> partialRow = new LinkedHashMap<>();
        partialRow.put("name", "Partial SLO");
        partialRow.put("target", 1.0);
        partialRow.put("current_value", 0.5);
        partialRow.put("unit", "ratio");
        // no "compliant" key
        when(sloTrackingService.getSloStatus()).thenReturn(Map.of("slos", List.of(partialRow)));

        mockMvc.perform(get("/api/v1/observability/slo-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].compliant").value(false));
    }

    private static Map<String, Object> sloRow(String name, Object target, double current, boolean compliant, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("target", target);
        m.put("current_value", current);
        m.put("compliant", compliant);
        m.put("unit", unit);
        return m;
    }
}
