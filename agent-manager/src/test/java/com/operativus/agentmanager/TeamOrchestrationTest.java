package com.operativus.agentmanager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

// @SpringBootTest without extending BaseIntegrationTest — no Testcontainers,
// so it tries localhost:5432 and fails on CI runners with no host Postgres.
// Re-enable locally by removing @Disabled, or migrate to extend BaseIntegrationTest.
@Disabled("Requires host Postgres; not CI-compatible. Migrate to BaseIntegrationTest to re-enable under CI.")
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.ai.openai.api-key=sk-dummy-key-for-test"
})
@Tag("integration")
public class TeamOrchestrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.operativus.agentmanager.compute.service.AgentService agentService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry;

    @MockitoBean
    private com.operativus.agentmanager.control.service.SessionService sessionService;

    @MockitoBean
    private com.operativus.agentmanager.control.service.SettingsService settingsService;

    @MockitoBean
    private com.operativus.agentmanager.control.repository.RunRepository runRepository;
    
    @MockitoBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;
    
    @MockitoBean
    private com.operativus.agentmanager.control.service.PersistentChatMemory persistentChatMemory;
    
    // We mock DelegationTool to avoid circular deps if any, or just let it be loaded if using availableTools
    // @MockBean
    // private com.operativus.agentmanager.compute.tools.DelegationTool delegationTool;
    
    @Test
    public void testContextLoads() {
        assertNotNull(agentService);
        assertNotNull(agentRegistry);
    }
    
    @Test
    public void testRegistryHasTeams() {
        assertNotNull(agentRegistry.findById(eq("investment_team"), any()));
        assertTrue(agentRegistry.findById(eq("investment_team"), any()).isTeam());
        assertEquals("ROUTER", agentRegistry.findById(eq("investment_team"), any()).teamMode());
    }
    
    // Enabling this would require a real API key or sophisticated mocking
    // @Test 
    public void testCoordinatorExecution() {
        // RunResponse response = agentService.run("investment_team", "Analyze AAPL", "test-session");
        // assertNotNull(response);
    }
}
