package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.AgentCredentialRepository;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.HaltAllRunsResponse;
import com.operativus.agentmanager.core.model.QuarantineResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentResponseServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private RunRepository runRepository;
    @Mock private AgentCredentialRepository credentialRepository;
    @Mock private AgentAuditRepository agentAuditRepository;

    private MeterRegistry meterRegistry;
    private ObjectMapper objectMapper;
    private IncidentResponseService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
        service = new IncidentResponseService(
                agentRepository, runRepository, credentialRepository,
                agentAuditRepository, objectMapper, meterRegistry);
        // Cross-tenant guard added — bind a SecurityContext with a UserDetailsImpl
        // carrying orgId=test-org so AgentContextHolder.getOrgId() returns "test-org"
        // and the mocked findByIdAndOrgId(agentId, "test-org") matches.
        com.operativus.agentmanager.control.security.UserDetailsImpl principal =
                new com.operativus.agentmanager.control.security.UserDetailsImpl(
                        java.util.UUID.randomUUID(), "test-admin", "test@local",
                        "test-org", false, "{noop}pass", java.util.List.of());
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        var ctx = new org.springframework.security.core.context.SecurityContextImpl(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private AgentEntity agentWithMaintenanceMode(String id, boolean maintenance) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName("test-agent");
        agent.setOrgId("test-org");
        agent.setMaintenanceMode(maintenance);
        return agent;
    }

    @Test
    void quarantineAgent_HappyPath_PerformsAllStepsAtomicallyAndAuditsRunAndCredIds() throws Exception {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", false);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));
        when(runRepository.cancelRunningByAgentId(eq("agent-A"), anyString()))
                .thenReturn(List.of("run-1", "run-2", "run-3"));
        when(credentialRepository.disableByAgentId("agent-A"))
                .thenReturn(List.of("cred-1", "cred-2"));

        QuarantineResponse response = service.quarantineAgent("agent-A", "leak detected", "ops-1");

        assertThat(response.runsCancelled()).isEqualTo(3);
        assertThat(response.credentialsLocked()).isEqualTo(2);
        assertThat(response.alreadyQuarantined()).isFalse();

        // Side effects in order: agent save with maintenanceMode=true, runs cancelled, creds disabled, audit row
        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().isMaintenanceMode()).isTrue();
        verify(runRepository).cancelRunningByAgentId(eq("agent-A"), eq("Quarantined: leak detected"));
        verify(credentialRepository).disableByAgentId("agent-A");

        // Audit row JSON via ObjectMapper.readTree (key-order-stable assertions)
        ArgumentCaptor<AgentAuditEntity> auditCaptor = ArgumentCaptor.forClass(AgentAuditEntity.class);
        verify(agentAuditRepository).save(auditCaptor.capture());
        AgentAuditEntity audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("AGENT_QUARANTINE");
        assertThat(audit.getUsername()).isEqualTo("ops-1");
        JsonNode changeset = objectMapper.readTree(audit.getChangeset());
        assertThat(changeset.get("reason").asText()).isEqualTo("leak detected");
        assertThat(changeset.get("runsCancelled").asInt()).isEqualTo(3);
        assertThat(changeset.get("credentialsLocked").asInt()).isEqualTo(2);
        assertThat(changeset.get("cancelled_runs").size()).isEqualTo(3);
        assertThat(changeset.get("locked_credentials").size()).isEqualTo(2);

        assertThat(counter("quarantine", "ok")).isEqualTo(1.0);
        assertThat(counter("quarantine", "noop")).isEqualTo(0.0);
        assertThat(counter("quarantine", "error")).isEqualTo(0.0);
    }

    @Test
    void quarantineAgent_AlreadyQuarantined_IsIdempotentNoOp() {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", true);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));

        QuarantineResponse response = service.quarantineAgent("agent-A", "second attempt", "ops-2");

        assertThat(response.alreadyQuarantined()).isTrue();
        verify(agentRepository, never()).save(any());
        verify(runRepository, never()).cancelRunningByAgentId(any(), any());
        verify(credentialRepository, never()).disableByAgentId(any());
        verify(agentAuditRepository, never()).save(any());
        assertThat(counter("quarantine", "ok")).isEqualTo(0.0);
        assertThat(counter("quarantine", "noop")).isEqualTo(1.0);
    }

    @Test
    void quarantineAgent_NoRunsNoCredentials_StillWritesAuditRowForStateChangeEvent() {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", false);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));
        when(runRepository.cancelRunningByAgentId(eq("agent-A"), anyString())).thenReturn(List.of());
        when(credentialRepository.disableByAgentId("agent-A")).thenReturn(List.of());

        QuarantineResponse response = service.quarantineAgent("agent-A", "preemptive lock", "ops-1");

        assertThat(response.runsCancelled()).isEqualTo(0);
        assertThat(response.credentialsLocked()).isEqualTo(0);
        verify(agentAuditRepository, times(1)).save(any(AgentAuditEntity.class));
        assertThat(counter("quarantine", "ok")).isEqualTo(1.0);
    }

    @Test
    void quarantineAgent_AgentNotFound_Throws404AndDoesNotIncrementErrorMeter() {
        when(agentRepository.findByIdAndOrgId("missing", "test-org")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quarantineAgent("missing", "x", "ops-1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(agentRepository, never()).save(any());
        verify(agentAuditRepository, never()).save(any());
        // Error counter is for unexpected runtime failures, not 404s
        assertThat(counter("quarantine", "error")).isEqualTo(0.0);
    }

    @Test
    void quarantineAgent_CredentialDisableThrows_RollsBackAndIncrementsErrorMeter() {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", false);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));
        when(runRepository.cancelRunningByAgentId(eq("agent-A"), anyString()))
                .thenReturn(List.of("run-1"));
        when(credentialRepository.disableByAgentId("agent-A"))
                .thenThrow(new DataIntegrityViolationException("simulated DB constraint"));

        assertThatThrownBy(() -> service.quarantineAgent("agent-A", "test", "ops-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Audit row was NOT saved (the failure happened BEFORE audit save in the method body)
        verify(agentAuditRepository, never()).save(any());
        assertThat(counter("quarantine", "error")).isEqualTo(1.0);
        assertThat(counter("quarantine", "ok")).isEqualTo(0.0);
    }

    @Test
    void unquarantineAgent_TargetedReEnable_UsesAuditRowLockedCredentials() {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", true);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));

        // Stub the most-recent quarantine audit row with a known locked_credentials list.
        AgentAuditEntity priorQuarantine = new AgentAuditEntity(
                "agent-A", "AGENT_QUARANTINE", "ops-1",
                "{\"reason\":\"leak\",\"locked_credentials\":[\"cred-1\",\"cred-2\"]}");
        priorQuarantine.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        Page<AgentAuditEntity> page = new PageImpl<>(List.of(priorQuarantine));
        when(agentAuditRepository.search(eq("test-org"), eq("agent-A"), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);
        when(credentialRepository.enableByIds(any())).thenReturn(2);

        QuarantineResponse response = service.unquarantineAgent("agent-A", "false positive", "ops-2");

        assertThat(response.alreadyQuarantined()).isFalse();
        assertThat(response.credentialsLocked()).isEqualTo(2);
        // The eq matcher proves only the audit-recorded IDs are re-enabled (not blanket "enable all")
        verify(credentialRepository).enableByIds(eq(List.of("cred-1", "cred-2")));
        // The agent.maintenanceMode was flipped back to false
        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().isMaintenanceMode()).isFalse();
        assertThat(counter("unquarantine", "ok")).isEqualTo(1.0);
    }

    @Test
    void unquarantineAgent_NotCurrentlyQuarantined_IsNoOp() {
        AgentEntity agent = agentWithMaintenanceMode("agent-A", false);
        when(agentRepository.findByIdAndOrgId("agent-A", "test-org")).thenReturn(Optional.of(agent));

        service.unquarantineAgent("agent-A", "noop test", "ops-1");

        verify(agentRepository, never()).save(any());
        verify(credentialRepository, never()).enableByIds(any());
        verify(agentAuditRepository, never()).save(any());
        assertThat(counter("unquarantine", "noop")).isEqualTo(1.0);
    }

    @Test
    void haltAllRuns_HappyPath_OneAuditRowPerDistinctAffectedAgent() throws Exception {
        // 4 cancelled runs across 2 distinct agents in 2 distinct orgs.
        when(runRepository.cancelAllRunning(anyString())).thenReturn(List.of(
                new Object[]{"run-1", "agent-A", "org-1"},
                new Object[]{"run-2", "agent-A", "org-1"},
                new Object[]{"run-3", "agent-B", "org-2"},
                new Object[]{"run-4", "agent-B", "org-2"}));

        HaltAllRunsResponse response = service.haltAllRuns("platform incident", "super-1");

        assertThat(response.runsCancelled()).isEqualTo(4);
        assertThat(response.tenantsAffected()).isEqualTo(2);

        // One audit row per distinct affected agent (2 rows, not 4)
        ArgumentCaptor<AgentAuditEntity> auditCaptor = ArgumentCaptor.forClass(AgentAuditEntity.class);
        verify(agentAuditRepository, times(2)).save(auditCaptor.capture());
        // Both rows carry the same rollup changeset; verify on the first
        AgentAuditEntity row0 = auditCaptor.getAllValues().get(0);
        assertThat(row0.getAction()).isEqualTo("GLOBAL_HALT_ALL_RUNS");
        JsonNode changeset = objectMapper.readTree(row0.getChangeset());
        assertThat(changeset.get("runsCancelled").asInt()).isEqualTo(4);
        assertThat(changeset.get("affectedAgents").size()).isEqualTo(2);
        assertThat(changeset.get("affectedOrgs").size()).isEqualTo(2);

        assertThat(counter("kill_switch", "ok")).isEqualTo(1.0);
    }

    private double counter(String name, String outcome) {
        return meterRegistry.find("agm.incident." + name)
                .tag("outcome", outcome).counter().count();
    }
}
