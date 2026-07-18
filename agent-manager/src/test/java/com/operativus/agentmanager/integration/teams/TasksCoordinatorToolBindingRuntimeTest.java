package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.service.AgentClientFactory;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.SystemTool;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins REQ-TT-7a — when a team agent's {@code teamMode='TASKS'},
 *   {@link AgentClientFactory#resolveTools(AgentDefinition)} auto-injects all four
 *   {@code TaskManagementTool} methods so the coordinator LLM can call them via
 *   Spring AI's @Tool framework. Without this binding, the worker loop never
 *   has anything to drain.
 *
 *   <p>Cases:
 *   <ol>
 *     <li>TASKS-mode coordinator with empty {@code tools()} list gets all 4 task tools.</li>
 *     <li>Non-TASKS team (e.g. SEQUENTIAL) does NOT get task tools injected.</li>
 *   </ol>
 *
 * State: Stateless test fixture (per-test isolation via BaseIntegrationTest).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class TasksCoordinatorToolBindingRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private AgentClientFactory agentClientFactory;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentRegistry agentRegistry;

    @Test
    void tasksMode_resolveTools_injectsAllFourTaskTools() {
        String id = persistTeam("tasks-coord-" + UUID.randomUUID(), "TASKS");
        AgentDefinition def = agentRegistry.findById(id, ORG);

        List<String> resolvedNames = agentClientFactory.resolveTools(def).stream()
                .map(t -> t.getToolDefinition().name())
                .toList();

        assertThat(resolvedNames).contains(
                SystemTool.TASK_CREATE.getToolName(),
                SystemTool.TASK_UPDATE_STATUS.getToolName(),
                SystemTool.TASK_QUERY.getToolName(),
                SystemTool.TASK_GET.getToolName());
    }

    @Test
    void sequentialMode_resolveTools_doesNotInjectTaskTools() {
        String id = persistTeam("seq-coord-" + UUID.randomUUID(), "SEQUENTIAL");
        AgentDefinition def = agentRegistry.findById(id, ORG);

        List<String> resolvedNames = agentClientFactory.resolveTools(def).stream()
                .map(t -> t.getToolDefinition().name())
                .toList();

        assertThat(resolvedNames).doesNotContain(
                SystemTool.TASK_CREATE.getToolName(),
                SystemTool.TASK_QUERY.getToolName());
    }

    private String persistTeam(String id, String teamMode) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(ORG);
        a.setName("TT7a fixture " + teamMode);
        a.setDescription("TT7a fixture");
        a.setInstructions("noop");
        a.setModelId(null);
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(true);
        a.setTeamMode(teamMode);
        a.setMembers(List.of());
        a.setCapabilities(new String[0]);
        return agentRepository.save(a).getId();
    }
}
