package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.compute.security.AgentIdentityService;
import com.operativus.agentmanager.core.entity.AgentCredential;
import com.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AgentCredentialControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgentIdentityService agentIdentityService;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private AgentDefinition mockAgentDef;

    @InjectMocks
    private AgentCredentialController controller;

    private AgentCredential sampleCredential;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        sampleCredential = new AgentCredential();
        sampleCredential.setId("cred-1");
        sampleCredential.setAgentId("agent-1");
        sampleCredential.setCredentialType("API_KEY");
        sampleCredential.setProviderName("stripe");
        sampleCredential.setEncryptedSecret("sk-test");
        sampleCredential.setEnabled(true);

        // AgentContextHolder.getOrgId() returns null in a standalone MockMvc context (no filter chain).
        // Stub agentRegistry to pass the org guard for the default test agent. lenient() because
        // the cross-cutting org-guard stub is invoked by most tests but not by the unknown-agent
        // 404 test that overrides it with a different agentId.
        lenient().when(agentRegistry.findById(eq("agent-1"), isNull())).thenReturn(mockAgentDef);
    }

    @Test
    void getCredentials_Returns200WithList() throws Exception {
        when(agentIdentityService.getCredentials("agent-1")).thenReturn(List.of(sampleCredential));

        mockMvc.perform(get("/api/v1/agents/agent-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cred-1"))
                .andExpect(jsonPath("$[0].providerName").value("stripe"));
    }

    @Test
    void getCredential_Exists_Returns200() throws Exception {
        when(agentIdentityService.getCredential("cred-1", "agent-1")).thenReturn(sampleCredential);

        mockMvc.perform(get("/api/v1/agents/agent-1/credentials/cred-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cred-1"))
                .andExpect(jsonPath("$.credentialType").value("API_KEY"));
    }

    @Test
    void getCredential_NotFound_Returns404() throws Exception {
        when(agentIdentityService.getCredential("missing", "agent-1"))
                .thenThrow(new ResourceNotFoundException("AgentCredential", "missing"));

        mockMvc.perform(get("/api/v1/agents/agent-1/credentials/missing")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void createCredential_Returns201() throws Exception {
        when(agentIdentityService.createCredential(any())).thenReturn(sampleCredential);

        String body = objectMapper.writeValueAsString(sampleCredential);

        mockMvc.perform(post("/api/v1/agents/agent-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cred-1"))
                .andExpect(jsonPath("$.providerName").value("stripe"));

        verify(agentIdentityService).createCredential(any());
    }

    @Test
    void updateCredential_Returns200() throws Exception {
        when(agentIdentityService.updateCredential(eq("cred-1"), eq("agent-1"), any())).thenReturn(sampleCredential);

        String body = objectMapper.writeValueAsString(sampleCredential);

        mockMvc.perform(put("/api/v1/agents/agent-1/credentials/cred-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cred-1"));

        verify(agentIdentityService).updateCredential(eq("cred-1"), eq("agent-1"), any());
    }

    @Test
    void deleteCredential_Returns204() throws Exception {
        doNothing().when(agentIdentityService).deleteCredential("cred-1", "agent-1");

        mockMvc.perform(delete("/api/v1/agents/agent-1/credentials/cred-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(agentIdentityService).deleteCredential("cred-1", "agent-1");
    }

    @Test
    void deleteCredential_NotFound_Returns404() throws Exception {
        doThrow(new ResourceNotFoundException("AgentCredential", "missing"))
                .when(agentIdentityService).deleteCredential("missing", "agent-1");
        // "missing" credentialId — org guard still passes (we're still hitting agent-1 which is mocked above).
        // Service throws ResourceNotFoundException which maps to 404.
        mockMvc.perform(delete("/api/v1/agents/agent-1/credentials/missing")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    // ─── Mass-assignment regression pins ────────────────────────────────────
    //
    // Threat shape: previously the controller bound @RequestBody AgentCredential
    // (raw JPA entity). A caller could supply `id` (application-managed String PK,
    // not @GeneratedValue) in the request body. Spring Data JPA's save() merges
    // on existing id → the row belonging to ANOTHER tenant's agent gets
    // overwritten with the attacker's payload + the attacker's agentId.
    // The fix swapped the bind to AgentCredentialRequest, which has no `id` field
    // (and no `agentId` field — that's always taken from the path variable).

    @Test
    void createCredential_RejectsClientSuppliedId_AlwaysServerGenerated() throws Exception {
        when(agentIdentityService.createCredential(any())).thenReturn(sampleCredential);

        // Attacker payload: every field of a real credential PLUS `id` and `agentId`
        // pointing at a victim row. The DTO has no slot for either, so Jackson
        // silently drops them — the entity that reaches the service has id=null
        // (service then UUID-generates) and agentId=<path variable>.
        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "victim-cred-id-from-another-tenant",
                "agentId", "victim-agent-id-from-another-tenant",
                "credentialType", "API_KEY",
                "providerName", "stripe",
                "encryptedSecret", "attacker-secret",
                "enabled", true));

        mockMvc.perform(post("/api/v1/agents/agent-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AgentCredential> captor = ArgumentCaptor.forClass(AgentCredential.class);
        verify(agentIdentityService).createCredential(captor.capture());
        AgentCredential passed = captor.getValue();

        assertNull(passed.getId(),
                "attacker-supplied id must NOT reach the service — id is server-generated "
                        + "via UUID in AgentIdentityService.createCredential. Without this "
                        + "guard, save() would merge on the attacker's id and overwrite the "
                        + "victim tenant's credential row.");
        assertNotEquals("victim-agent-id-from-another-tenant", passed.getAgentId(),
                "attacker-supplied agentId must NOT reach the service — agentId is always "
                        + "taken from the path variable.");
        // Positive sanity: the field that SHOULD pass through did pass through.
        assertEquals("attacker-secret", passed.getEncryptedSecret());
        assertEquals("agent-1", passed.getAgentId());
    }

    @Test
    void updateCredential_RejectsClientSuppliedId_OnlyPathVariableUsedAtServiceLayer() throws Exception {
        when(agentIdentityService.updateCredential(eq("cred-1"), eq("agent-1"), any()))
                .thenReturn(sampleCredential);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", "different-cred-id-to-try-to-steer-the-update",
                "agentId", "victim-agent-id",
                "credentialType", "BEARER",
                "providerName", "github",
                "encryptedSecret", "new-secret",
                "enabled", false));

        mockMvc.perform(put("/api/v1/agents/agent-1/credentials/cred-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        // The service receives the path variable, never the body's id/agentId.
        verify(agentIdentityService).updateCredential(eq("cred-1"), eq("agent-1"), any());

        ArgumentCaptor<AgentCredential> captor = ArgumentCaptor.forClass(AgentCredential.class);
        verify(agentIdentityService).updateCredential(eq("cred-1"), eq("agent-1"), captor.capture());
        AgentCredential passed = captor.getValue();
        assertNull(passed.getId(),
                "attacker-supplied id in update body must NOT reach the service — "
                        + "credentialId comes from the path variable only");
        assertEquals("agent-1", passed.getAgentId(),
                "attacker-supplied agentId in update body must NOT reach the service — "
                        + "agentId comes from the path variable only");
    }

    @Test
    void createCredential_AttackerCannotSetCreatedAtOrUpdatedAt() throws Exception {
        when(agentIdentityService.createCredential(any())).thenReturn(sampleCredential);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "credentialType", "API_KEY",
                "providerName", "stripe",
                "encryptedSecret", "k",
                "enabled", true,
                "createdAt", "1970-01-01T00:00:00",
                "updatedAt", "1970-01-01T00:00:00"));

        mockMvc.perform(post("/api/v1/agents/agent-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isCreated());

        ArgumentCaptor<AgentCredential> captor = ArgumentCaptor.forClass(AgentCredential.class);
        verify(agentIdentityService).createCredential(captor.capture());
        AgentCredential passed = captor.getValue();
        assertNull(passed.getCreatedAt(),
                "attacker-supplied createdAt must NOT reach the entity — JPA @CreationTimestamp "
                        + "owns this field. Allowing client override would let an attacker "
                        + "back-date credentials to hide audit trails.");
    }

    @Test
    void createCredential_ValidationFailsOnBlankRequiredFields() throws Exception {
        // DTO has @NotBlank on credentialType + providerName. Missing both → 400.
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "encryptedSecret", "k",
                "enabled", true));

        mockMvc.perform(post("/api/v1/agents/agent-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(agentIdentityService, never()).createCredential(any());
    }

    @Test
    void getCredential_UnknownAgent_Returns404() throws Exception {
        // Agent not found in caller's org → org guard throws ResourceNotFoundException → 404.
        when(agentRegistry.findById(eq("unknown-agent"), isNull())).thenReturn(null);

        mockMvc.perform(get("/api/v1/agents/unknown-agent/credentials/cred-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));

        verify(agentIdentityService, never()).getCredential(any(), any());
    }
}
