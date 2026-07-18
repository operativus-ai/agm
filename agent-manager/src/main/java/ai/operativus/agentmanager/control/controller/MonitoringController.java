package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.model.ThreatEventDTO;
import ai.operativus.agentmanager.core.model.SandboxCapabilityDTO;
import ai.operativus.agentmanager.core.entity.ThreatEventEntity;
import ai.operativus.agentmanager.core.entity.SandboxCapabilityEntity;
import ai.operativus.agentmanager.control.repository.ThreatEventRepository;
import ai.operativus.agentmanager.control.repository.SandboxCapabilityRepository;
import ai.operativus.agentmanager.control.service.MonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/monitoring")
@PreAuthorize("hasRole('ADMIN')")
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final ThreatEventRepository threatEventRepository;
    private final SandboxCapabilityRepository sandboxCapabilityRepository;

    public MonitoringController(MonitoringService monitoringService, 
                              ThreatEventRepository threatEventRepository,
                              SandboxCapabilityRepository sandboxCapabilityRepository) {
        this.monitoringService = monitoringService;
        this.threatEventRepository = threatEventRepository;
        this.sandboxCapabilityRepository = sandboxCapabilityRepository;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getGlobalStats() {
        return ResponseEntity.ok(monitoringService.getGlobalStats());
    }

    @GetMapping("/security/events")
    public ResponseEntity<List<ThreatEventDTO>> getThreatEvents() {
        List<ThreatEventDTO> dtos = threatEventRepository.findAll().stream()
                .map(this::toThreatDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/security/sandbox")
    public ResponseEntity<List<SandboxCapabilityDTO>> getSandboxCapabilities() {
        List<SandboxCapabilityDTO> dtos = sandboxCapabilityRepository.findAll().stream()
                .map(this::toSandboxDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
    
    private ThreatEventDTO toThreatDto(ThreatEventEntity entity) {
        return new ThreatEventDTO(
            entity.getId(),
            entity.getTimestamp(),
            entity.getAgentId(),
            entity.getThreatLevel(),
            entity.getType(),
            entity.getTarget(),
            entity.getStatus()
        );
    }
    
    private SandboxCapabilityDTO toSandboxDto(SandboxCapabilityEntity entity) {
        List<String> activeCaps = entity.getActiveCapabilities() != null 
                ? Arrays.asList(entity.getActiveCapabilities().split(",")) 
                : Collections.emptyList();
        List<String> restPaths = entity.getRestrictedPaths() != null 
                ? Arrays.asList(entity.getRestrictedPaths().split(",")) 
                : Collections.emptyList();
                
        return new SandboxCapabilityDTO(
            entity.getAgentId(),
            entity.getThreadId(),
            activeCaps,
            restPaths,
            entity.getMemoryIsolation()
        );
    }
}
