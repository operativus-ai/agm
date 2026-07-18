package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.model.AgentTemplate;
import ai.operativus.agentmanager.core.model.TeamTemplate;
import ai.operativus.agentmanager.core.model.WorkflowTemplate;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requirement 3.5.1: System Configuration API
 * Domain Responsibility: Exposes global configuration and available resources (Agents, Teams, Knowledge) for the frontend Control Plane.
 * State: Stateless
 * Dependencies: AgentRegistry, KnowledgeService
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final AgentRegistry agentRegistry;
    private final ai.operativus.agentmanager.control.service.KnowledgeService knowledgeService;
    private final ai.operativus.agentmanager.core.registry.TeamOperations teamOperations;
    private final ai.operativus.agentmanager.control.service.WorkflowService workflowService;
    private final String osId = UUID.randomUUID().toString();
    private final String appVersion;

    public ConfigController(AgentRegistry agentRegistry,
                           ai.operativus.agentmanager.control.service.KnowledgeService knowledgeService,
                           ai.operativus.agentmanager.core.registry.TeamOperations teamOperations,
                           ai.operativus.agentmanager.control.service.WorkflowService workflowService,
                           @org.springframework.beans.factory.annotation.Value("${agentmanager.version:0.0.0}") String appVersion) {
        this.agentRegistry = agentRegistry;
        this.knowledgeService = knowledgeService;
        this.teamOperations = teamOperations;
        this.workflowService = workflowService;
        this.appVersion = appVersion;
    }

    /**
     * @summary Returns built-in agent profile templates for the "Create Agent" wizard.
     */
    @GetMapping("/templates")
    public List<AgentTemplate> getAgentTemplates() {
        return AgentTemplate.builtInTemplates();
    }

    /**
     * @summary Returns built-in team orchestration templates for the "Create Team" wizard.
     */
    @GetMapping("/team-templates")
    public List<TeamTemplate> getTeamTemplates() {
        return TeamTemplate.builtInTemplates();
    }

    /**
     * @summary Returns built-in workflow pipeline templates for the "Create Workflow" wizard.
     */
    @GetMapping("/workflow-templates")
    public List<WorkflowTemplate> getWorkflowTemplates() {
        return WorkflowTemplate.builtInTemplates();
    }

    /**
     * @summary Returns an aggregated configuration payload for initializing the UI.
     */
    @GetMapping
    public Map<String, Object> getConfig() {
        log.debug("API request to /config");
        return Map.of(
            "os_id", osId,
            "name", "Java AgentManager",
            "version", appVersion,
            "agents", agentRegistry.findAll(false,
                    ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId()),
            "teams", teamOperations.getAllTeams(),
            "workflows", workflowService.getAllWorkflows(),
            // Dynamic Knowledge Base List
            "knowledge_bases", knowledgeService.listFiles()
        );
    }
}
