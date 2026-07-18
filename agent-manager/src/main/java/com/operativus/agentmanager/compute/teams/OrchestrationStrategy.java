package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.springframework.ai.content.Media;

import java.util.List;

/**
 * Domain Responsibility: Interface defining multiple patterns for orchestrating teams of LLM-backed agents (Sequential, Planner, Router, Swarm).
 * State: Stateless (Strategy Interface)
 */

public sealed interface OrchestrationStrategy
        permits PlannerOrchestrator, RouterOrchestrator,
                SequentialOrchestrator, SwarmOrchestrator,
                TasksOrchestrator {
    String getStrategyName();

    boolean supports(String teamMode);

    /**
     * @summary Executes the specific multi-agent orchestration pattern synchronously.
     * @logic Implements the specific orchestration logic defined by the concrete class (e.g., SWARM, SEQUENTIAL).
     */
    String execute(
            AgentDefinition rootAgent,
            String initialInput,
            List<Media> media,
            String sessionId,
            String userId,
            String orgId,
            Boolean generateFollowups,
            AgentOperations agentService
    );

}

