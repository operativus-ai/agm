package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.service.DataRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Domain Responsibility: REST API boundary for inspecting data retention policies and triggering on-demand purge.
 *   The purge mutates persistent state across all tenants (sessions, runs, audits, alerts, run events,
 *   orchestration decisions, sse_tokens). Admin-only at the controller layer.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/admin/retention")
@PreAuthorize("hasRole('ADMIN')")
public class DataRetentionController {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionController.class);

    private final DataRetentionService dataRetentionService;

    public DataRetentionController(DataRetentionService dataRetentionService) {
        this.dataRetentionService = dataRetentionService;
    }

    @GetMapping("/policies")
    public ResponseEntity<Map<String, Integer>> getPolicies() {
        return ResponseEntity.ok(dataRetentionService.getRetentionPolicies());
    }

    @PostMapping("/purge")
    public ResponseEntity<Map<String, Integer>> triggerPurge() {
        log.info("Manual data retention purge triggered via admin API");
        Map<String, Integer> result = dataRetentionService.enforceRetentionPolicies();
        return ResponseEntity.ok(result);
    }
}
