package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.core.model.RegistryItemDTO;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.TeamRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Exposes REST APIs for listing registered Agents and Teams from persistence.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/registry")
public class RegistryController {

    private final AgentRepository agentRepository;
    private final TeamRepository teamRepository;

    public RegistryController(AgentRepository agentRepository, TeamRepository teamRepository) {
        this.agentRepository = agentRepository;
        this.teamRepository = teamRepository;
    }

    @GetMapping("/agents/code")
    public ResponseEntity<List<RegistryItemDTO>> listCodeAgents() {
        List<RegistryItemDTO> dtos = agentRepository.findAll().stream()
                .filter(agent -> Boolean.TRUE.equals(agent.isActive()) && Boolean.FALSE.equals(agent.isTeam()))
                .map(agent -> new RegistryItemDTO(
                        agent.getId(),
                        agent.getName(),
                        agent.getDescription(),
                        "AGENT"
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/teams/code")
    public ResponseEntity<List<RegistryItemDTO>> listCodeTeams() {
        List<RegistryItemDTO> dtos = teamRepository.findAll().stream()
                .map(team -> new RegistryItemDTO(
                        team.getId(),
                        team.getName(),
                        team.getDescription(),
                        "TEAM"
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
}
