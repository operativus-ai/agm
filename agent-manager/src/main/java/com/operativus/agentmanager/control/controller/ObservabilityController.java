package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.core.model.TraceSpanDTO;
import com.operativus.agentmanager.core.entity.TraceSpanEntity;
import com.operativus.agentmanager.control.repository.TraceSpanRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityController {

    private final TraceSpanRepository traceSpanRepository;

    @Value("${agentmanager.otlp.enabled:false}")
    private boolean otlpEnabled;

    @Value("${agentmanager.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Value("${agentmanager.otlp.include-prompts:false}")
    private boolean otlpIncludePrompts;

    @Value("${agentmanager.otlp.batch-size:512}")
    private int otlpBatchSize;

    @Value("${agentmanager.otlp.export-interval-ms:5000}")
    private long otlpExportIntervalMs;

    public ObservabilityController(TraceSpanRepository traceSpanRepository) {
        this.traceSpanRepository = traceSpanRepository;
    }

    @GetMapping("/traces/{runId}")
    public ResponseEntity<List<TraceSpanDTO>> getRunTraces(@PathVariable String runId) {
        List<TraceSpanDTO> dtos = traceSpanRepository.findAll().stream()
                .filter(t -> runId.equals(t.getParentId()))
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * @summary Returns the current OTLP export configuration (read-only view of application properties).
     */
    @GetMapping("/otlp/config")
    public ResponseEntity<Map<String, Object>> getOtlpConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", otlpEnabled);
        config.put("endpoint", otlpEndpoint);
        config.put("includePrompts", otlpIncludePrompts);
        config.put("batchSize", otlpBatchSize);
        config.put("exportIntervalMs", otlpExportIntervalMs);
        return ResponseEntity.ok(config);
    }

    private TraceSpanDTO toDto(TraceSpanEntity entity) {
        return new TraceSpanDTO(
            entity.getId(),
            entity.getParentId(),
            entity.getName(),
            entity.getStartTime(),
            entity.getEndTime(),
            entity.getDurationMs() != null ? entity.getDurationMs() : 0L,
            Collections.emptyMap(), // Simplification for now
            Collections.emptyList()
        );
    }
}
