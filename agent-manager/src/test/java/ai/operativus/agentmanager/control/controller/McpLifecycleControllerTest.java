package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.compute.mcp.McpConnectionPool;
import ai.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class McpLifecycleControllerTest {

    private MockMvc mockMvc;

    @Mock
    private McpConnectionPool connectionPool;

    @Mock
    private ExtensionRegistrationRepository extensionRepository;

    @InjectMocks
    private McpLifecycleController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getStatus_aggregatesPoolAndRepository() throws Exception {
        ExtensionRegistrationEntity active = mcpEntity("ext-1", "active-mcp", "http://a", true);
        ExtensionRegistrationEntity inactive = mcpEntity("ext-2", "inactive-mcp", "http://b", false);
        ExtensionRegistrationEntity webhook = new ExtensionRegistrationEntity();
        webhook.setId("ext-3");
        webhook.setType("WEBHOOK");
        webhook.setActive(true);

        when(extensionRepository.findByOrgId(anyString())).thenReturn(List.of(active, inactive, webhook));
        when(connectionPool.getConnectedExtensionIds()).thenReturn(Set.of("ext-1"));
        when(connectionPool.getToolCallbacksForOrg(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/mcp/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(2))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.connected").value(1))
                .andExpect(jsonPath("$.totalTools").value(0));
    }

    @Test
    void listServers_filtersToMcpAndIncludesConnectionState() throws Exception {
        ExtensionRegistrationEntity connected = mcpEntity("ext-1", "alpha", "http://a", true);
        ExtensionRegistrationEntity disconnected = mcpEntity("ext-2", "beta", "http://b", true);
        ExtensionRegistrationEntity webhook = new ExtensionRegistrationEntity();
        webhook.setId("hook");
        webhook.setType("WEBHOOK");
        webhook.setActive(true);

        when(extensionRepository.findByOrgId(anyString())).thenReturn(List.of(connected, disconnected, webhook));
        when(connectionPool.getConnectedExtensionIds()).thenReturn(Set.of("ext-1"));
        when(connectionPool.getToolCallbacks("ext-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("ext-1"))
                .andExpect(jsonPath("$[0].connectionStatus").value("CONNECTED"))
                .andExpect(jsonPath("$[1].id").value("ext-2"))
                .andExpect(jsonPath("$[1].connectionStatus").value("DISCONNECTED"));
    }

    @Test
    void reconnect_returns404WhenExtensionMissing() throws Exception {
        when(extensionRepository.findByIdAndOrgId(eq("missing"), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/mcp/servers/missing/reconnect"))
                .andExpect(status().isNotFound());

        verify(connectionPool, never()).disconnect(anyString());
        verify(connectionPool, never()).connect(anyString());
    }

    @Test
    void reconnect_returns404WhenExtensionIsNotMcp() throws Exception {
        ExtensionRegistrationEntity webhook = new ExtensionRegistrationEntity();
        webhook.setId("hook-1");
        webhook.setType("WEBHOOK");
        webhook.setUrl("http://a");
        webhook.setActive(true);
        when(extensionRepository.findByIdAndOrgId(eq("hook-1"), anyString())).thenReturn(Optional.of(webhook));

        mockMvc.perform(post("/api/mcp/servers/hook-1/reconnect"))
                .andExpect(status().isNotFound());

        verify(connectionPool, never()).connect(anyString());
    }

    @Test
    void reconnect_returns400WhenUrlMissing() throws Exception {
        ExtensionRegistrationEntity entity = mcpEntity("ext-1", "alpha", null, true);
        when(extensionRepository.findByIdAndOrgId(eq("ext-1"), anyString())).thenReturn(Optional.of(entity));

        mockMvc.perform(post("/api/mcp/servers/ext-1/reconnect"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_URL"));

        verify(connectionPool, never()).disconnect(anyString());
        verify(connectionPool, never()).connect(anyString());
    }

    @Test
    void reconnect_successfulReportsConnectedAndTriggersPool() throws Exception {
        ExtensionRegistrationEntity entity = mcpEntity("ext-1", "alpha", "http://a", true);
        when(extensionRepository.findByIdAndOrgId(eq("ext-1"), anyString())).thenReturn(Optional.of(entity));
        when(connectionPool.getConnectedExtensionIds()).thenReturn(Set.of("ext-1"));
        when(connectionPool.getToolCallbacks("ext-1")).thenReturn(List.of());

        mockMvc.perform(post("/api/mcp/servers/ext-1/reconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ext-1"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.status").value("RECONNECTED"));

        verify(connectionPool, times(1)).disconnect("ext-1");
        verify(connectionPool, times(1)).connect(eq("ext-1"));
    }

    @Test
    void reconnect_failedReportsFailedStatus() throws Exception {
        ExtensionRegistrationEntity entity = mcpEntity("ext-1", "alpha", "http://a", true);
        when(extensionRepository.findByIdAndOrgId(eq("ext-1"), anyString())).thenReturn(Optional.of(entity));
        // Pool reports the extension as not connected after the reconnect attempt — common
        // failure mode (e.g. network unreachable) where McpConnectionPool.connect logs and
        // returns without throwing, leaving the pool entry empty.
        when(connectionPool.getConnectedExtensionIds()).thenReturn(Set.of());

        mockMvc.perform(post("/api/mcp/servers/ext-1/reconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private static ExtensionRegistrationEntity mcpEntity(String id, String name, String url, boolean active) {
        ExtensionRegistrationEntity e = new ExtensionRegistrationEntity();
        e.setId(id);
        e.setName(name);
        e.setType("MCP");
        e.setUrl(url);
        e.setActive(active);
        return e;
    }
}
