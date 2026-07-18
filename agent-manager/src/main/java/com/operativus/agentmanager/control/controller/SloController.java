package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.service.SloTrackingService;
import com.operativus.agentmanager.core.model.SloStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: HTTP surface for current SLO compliance state. Read side of
 * {@link SloTrackingService}, which already aggregates Micrometer percentiles and
 * rolling success-rate counters on a 5-minute cadence. This controller translates the
 * service's untyped {@code Map} output into typed {@link SloStatusResponse} rows so the
 * UI can render without field-name guessing.
 * State: Stateless controller.
 */
@RestController
@RequestMapping("/api/v1/observability")
public class SloController {

    private final SloTrackingService sloTrackingService;

    public SloController(SloTrackingService sloTrackingService) {
        this.sloTrackingService = sloTrackingService;
    }

    /**
     * @summary Returns one {@link SloStatusResponse} per tracked SLO — latency p99 and
     *     success rate today; additional SLOs as the service grows.
     * @logic
     * - Delegates to {@link SloTrackingService#getSloStatus()} for evaluation.
     * - Maps the service's {@code Map<String,Object>} rows to typed records; fields the
     *   service may omit (e.g. if a meter isn't registered yet) default to the record's
     *   primitive zero values via {@link #asDouble(Object)}.
     * - {@code @PreAuthorize("hasRole('ADMIN')")} — widen if OPERATOR/OBSERVER roles are
     *   introduced later (observability plan §Phase 1 T001 note).
     */
    @GetMapping("/slo-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SloStatusResponse>> getSloStatus() {
        Map<String, Object> status = sloTrackingService.getSloStatus();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slos = (List<Map<String, Object>>) status.get("slos");
        if (slos == null) {
            return ResponseEntity.ok(List.of());
        }

        List<SloStatusResponse> response = new ArrayList<>(slos.size());
        for (Map<String, Object> slo : slos) {
            response.add(new SloStatusResponse(
                    (String) slo.get("name"),
                    asDouble(slo.get("target")),
                    asDouble(slo.get("current_value")),
                    Boolean.TRUE.equals(slo.get("compliant")),
                    (String) slo.get("unit")));
        }
        return ResponseEntity.ok(response);
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
