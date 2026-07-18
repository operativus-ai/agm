package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.AgentStatsDTO;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Aggregates and calculates execution statistics for agents across the platform.
 * State: Stateless
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final RunRepository runRepository;
    private final AgentRegistry agentRegistry;

    public MonitoringService(RunRepository runRepository, AgentRegistry agentRegistry) {
        this.runRepository = runRepository;
        this.agentRegistry = agentRegistry;
    }

    /**
     * @summary Aggregates global execution metrics encompassing all configured agents.
     * @logic Fetches all registered agent definitions, queries RunRepository for global active and completed counts, iteratively computes AgentStatsDTO for each agent, and bundles all metrics into a unified map.
     */
    public Map<String, Object> getGlobalStats() {
        log.debug("Fetching global stats calculation");
        List<AgentDefinition> agents = agentRegistry.findAll(false,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
        long totalAgents = agents.size();
        
        long totalActiveRuns = runRepository.countByStatus(RunStatus.RUNNING);
        long totalCompletedRuns = runRepository.countByStatus(RunStatus.COMPLETED);
        
        List<AgentStatsDTO> agentStats = new ArrayList<>();
        
        for (AgentDefinition agent : agents) {
            agentStats.add(getAgentStatsDTO(agent));
        }
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAgents", totalAgents);
        stats.put("totalActiveRuns", totalActiveRuns);
        stats.put("totalCompletedRuns", totalCompletedRuns);
        stats.put("agentStats", agentStats);
        
        return stats;
    }

    /**
     * @summary Translates an AgentDefinition into a populated AgentStatsDTO by querying execution state.
     * @logic Queries the total, active, and failed run counts for the specific agent ID, fetches the most recent Run record to determine the last execution timestamp, and constructs and returns the resulting immutable DTO.
     */
    private AgentStatsDTO getAgentStatsDTO(AgentDefinition agent) {
        String agentId = agent.id();
        
        long totalRuns = runRepository.countByAgentId(agentId);
        long activeRuns = runRepository.countByAgentIdAndStatus(agentId, RunStatus.RUNNING);
        long errorRuns = runRepository.countByAgentIdAndStatus(agentId, RunStatus.FAILED);
        
        AgentRun lastRun = runRepository.findFirstByAgentIdOrderByCreatedAtDesc(agentId);
        LocalDateTime lastRunAt = lastRun != null ? lastRun.getCreatedAt() : null;
        
        return new AgentStatsDTO(
            agentId,
            agent.name(),
            totalRuns,
            activeRuns,
            errorRuns,
            lastRunAt
        );
    }
}
