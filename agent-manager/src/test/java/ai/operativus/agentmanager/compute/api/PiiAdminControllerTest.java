package ai.operativus.agentmanager.compute.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.compute.security.PiiAuditLogEntity;
import ai.operativus.agentmanager.compute.security.PiiAuditLogRepository;
import ai.operativus.agentmanager.compute.security.PiiPolicyDTO;
import ai.operativus.agentmanager.compute.security.PiiPolicyService;
import ai.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc test — exercises wire shape (path → handler → JSON) without a real
 * Spring Security context. {@link ai.operativus.agentmanager.core.callback.AgentContextHolder#getOrgId()}
 * returns {@code null} here (no bound ScopedValue, no SecurityContext), and the service
 * mocks accept any orgId. Cross-tenant scoping correctness is covered by
 * {@code PiiAdminAuthzRuntimeTest} against a real Spring context with TenantContextFilter
 * + SecurityContext fallback.
 */
@ExtendWith(MockitoExtension.class)
public class PiiAdminControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PiiPolicyService policyService;

    @Mock
    private PiiAuditLogRepository auditLogRepository;

    @InjectMocks
    private PiiAdminController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listAllPolicies_Returns200() throws Exception {
        UUID policyId = UUID.randomUUID();
        PiiPolicyDTO mockDto = new PiiPolicyDTO(policyId, "SSN", "Desc", null, "\\d+", null, true, null, null);
        Mockito.when(policyService.findAllForOrg(any())).thenReturn(List.of(mockDto));

        mockMvc.perform(get("/api/v1/pii-policies")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(policyId.toString()))
                .andExpect(jsonPath("$[0].name").value("SSN"));
    }

    @Test
    void getAuditLog_noAgentFilter_usesOrgScopedQuery_andMapsDto() throws Exception {
        UUID id = UUID.randomUUID();
        PiiAuditLogEntity e = new PiiAuditLogEntity(id, "agent-1", "SSN", "REDACT", 3, "sess-1", "org-1");
        e.setCreatedAt(java.time.LocalDateTime.of(2026, 7, 4, 9, 0));
        // No agentId param -> the org-scoped list query (never findAll — that would cross tenants).
        Mockito.when(auditLogRepository.findByOrgIdOrderByCreatedAtDesc(any())).thenReturn(List.of(e));

        mockMvc.perform(get("/api/v1/pii-policies/audit-log")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].agentId").value("agent-1"))
                .andExpect(jsonPath("$[0].policyName").value("SSN"))
                .andExpect(jsonPath("$[0].occurrences").value(3))
                .andExpect(jsonPath("$[0].createdAt").value("2026-07-04T09:00"))
                // orgId is internal-only and must NOT leak into the wire shape.
                .andExpect(jsonPath("$[0].orgId").doesNotExist());
    }

    @Test
    void createPolicy_Returns201() throws Exception {
        UUID policyId = UUID.randomUUID();
        PiiPolicyDTO payload = new PiiPolicyDTO(null, "SSN", "Desc", null, "\\d+", null, true, null, null);
        PiiPolicyDTO created = new PiiPolicyDTO(policyId, "SSN", "Desc", null, "\\d+", null, true, null, null);

        Mockito.when(policyService.createPolicy(any(PiiPolicyDTO.class), any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/pii-policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(policyId.toString()));
    }

    @Test
    void deletePolicy_Returns204() throws Exception {
        UUID pid = UUID.randomUUID();
        Mockito.doNothing().when(policyService).deletePolicy(eq(pid), any());

        mockMvc.perform(delete("/api/v1/pii-policies/" + pid)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    @Test
    void getAgentBindings_ReturnsListOfUUIDs() throws Exception {
        UUID pid = UUID.randomUUID();
        Mockito.when(policyService.findBoundPolicyIds(eq("agent-1"), any())).thenReturn(List.of(pid));

        mockMvc.perform(get("/api/v1/pii-policies/agents/agent-1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(pid.toString()));
    }

    @Test
    void bindPolicy_Returns200() throws Exception {
        UUID pid = UUID.randomUUID();
        Mockito.doNothing().when(policyService).bindPolicyToAgent(eq("agent-1"), eq(pid), any());

        mockMvc.perform(post("/api/v1/pii-policies/agents/agent-1/bind/" + pid)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void unbindPolicy_Returns204() throws Exception {
        UUID pid = UUID.randomUUID();
        Mockito.doNothing().when(policyService).unbindPolicyFromAgent(eq("agent-1"), eq(pid), any());

        mockMvc.perform(delete("/api/v1/pii-policies/agents/agent-1/unbind/" + pid)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

}
