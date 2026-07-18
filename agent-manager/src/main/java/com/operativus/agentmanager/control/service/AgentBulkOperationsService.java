package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.AgentAdminOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Executes bulk state mutations and exports across multiple Agent entities.
 * State: Stateless
 */
@Service
public class AgentBulkOperationsService {

    private final AgentRepository agentRepository;
    private final AgentAdminOperations agentAdminOperations;

    public AgentBulkOperationsService(AgentRepository agentRepository, AgentAdminOperations agentAdminOperations) {
        this.agentRepository = agentRepository;
        this.agentAdminOperations = agentAdminOperations;
    }

    @Transactional
    public Map<String, Integer> enableAll(List<String> ids) {
        List<AgentEntity> agents = agentRepository.findAllById(ids);
        agents.forEach(a -> a.setActive(true));
        agentRepository.saveAll(agents);
        return Map.of("affected", agents.size());
    }

    @Transactional
    public Map<String, Integer> disableAll(List<String> ids) {
        List<AgentEntity> agents = agentRepository.findAllById(ids);
        agents.forEach(a -> a.setActive(false));
        agentRepository.saveAll(agents);
        return Map.of("affected", agents.size());
    }

    @Transactional
    public Map<String, Integer> deleteAll(List<String> ids) {
        int count = 0;
        for (String id : ids) {
            agentAdminOperations.deleteAgent(id);
            count++;
        }
        return Map.of("affected", count);
    }

    @Transactional(readOnly = true)
    public List<AgentDefinition> exportAll(List<String> ids) {
        return ids.stream().map(agentAdminOperations::exportAgent).toList();
    }
}
