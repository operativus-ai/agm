package ai.operativus.agentmanager.core.registry;

import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.entity.AgentAuditEntity;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.model.TopologyDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

/**
 * Domain Responsibility: Registry contract to access Agent Administration operations from the Control plane.
 */
public interface AgentAdminOperations {
    Page<AgentDefinition> getAllAgents(Pageable pageable, boolean includeInactive);
    AgentDefinition getAgent(String id);
    AgentDefinition createAgent(AgentDefinition dto);
    AgentDefinition updateAgent(String id, AgentDefinition dto);
    void deleteAgent(String id);
    void restoreAgent(String id);
    Page<AgentRun> getAgentHistory(String id, Pageable pageable);
    List<String> getAgentLogs(String id);
    Page<AgentAuditEntity> getAgentAuditHistory(String id, Pageable pageable);
    AgentDefinition exportAgent(String id);
    AgentDefinition importAgent(AgentDefinition definition);
    TopologyDTO getAgentTopology(String id);
    AgentDefinition rollbackAgent(String agentId, String auditId);
    List<AgentDefinition> getAgentVersions(String agentId);
    void cancelRun(String runId);
}
