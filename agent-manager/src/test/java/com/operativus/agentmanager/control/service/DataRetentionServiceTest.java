package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.AlertEventRepository;
import com.operativus.agentmanager.control.repository.GlobalSettingRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.entity.GlobalSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private RunRepository runRepository;
    @Mock private AgentAuditRepository auditRepository;
    @Mock private AlertEventRepository alertEventRepository;
    @Mock private GlobalSettingRepository globalSettingRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        service = new DataRetentionService(sessionRepository, runRepository, auditRepository, alertEventRepository, globalSettingRepository, jdbcTemplate);
        ReflectionTestUtils.setField(service, "sessionRetentionDays", 90);
        ReflectionTestUtils.setField(service, "runRetentionDays", 180);
        ReflectionTestUtils.setField(service, "auditRetentionDays", 365);
        ReflectionTestUtils.setField(service, "alertRetentionDays", 30);
    }

    @Test
    void dbSettingOverridesValueDefault_sessionsPurgedUsingDbCutoff() {
        // DB says 30 days; @Value default would keep this 60-day-old session
        when(globalSettingRepository.findById(eq(SettingsService.KEY_RETENTION_SESSIONS_DAYS)))
                .thenReturn(Optional.of(new GlobalSetting(SettingsService.KEY_RETENTION_SESSIONS_DAYS, "30", "")));
        when(globalSettingRepository.findById(eq(SettingsService.KEY_RETENTION_RUNS_DAYS))).thenReturn(Optional.empty());
        when(globalSettingRepository.findById(eq(SettingsService.KEY_RETENTION_AUDIT_DAYS))).thenReturn(Optional.empty());
        when(globalSettingRepository.findById(eq(SettingsService.KEY_RETENTION_ALERTS_DAYS))).thenReturn(Optional.empty());

        AgentSession session = mock(AgentSession.class);
        when(session.getUpdatedAt()).thenReturn(LocalDateTime.now().minusDays(60));
        when(sessionRepository.findAll()).thenReturn(List.of(session));
        when(alertEventRepository.findAll()).thenReturn(List.of());

        service.enforceRetentionPolicies();

        // 60 days > 30-day DB limit → session must be deleted
        verify(sessionRepository).deleteAll(List.of(session));
    }

    @Test
    void fallsBackToValueDefaultWhenDbEntryAbsent() {
        // No DB entries — all keys absent, falls back to @Value defaults
        when(globalSettingRepository.findById(anyString())).thenReturn(Optional.empty());

        // Session is 60 days old — younger than 90-day default → must NOT be deleted
        AgentSession session = mock(AgentSession.class);
        when(session.getUpdatedAt()).thenReturn(LocalDateTime.now().minusDays(60));
        when(sessionRepository.findAll()).thenReturn(List.of(session));
        when(alertEventRepository.findAll()).thenReturn(List.of());

        service.enforceRetentionPolicies();

        verify(sessionRepository, never()).deleteAll(any());
    }

    @Test
    void allFourRetentionKeysAreReadFromDb() {
        when(globalSettingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(sessionRepository.findAll()).thenReturn(List.of());
        when(alertEventRepository.findAll()).thenReturn(List.of());

        service.enforceRetentionPolicies();

        verify(globalSettingRepository).findById(SettingsService.KEY_RETENTION_SESSIONS_DAYS);
        verify(globalSettingRepository).findById(SettingsService.KEY_RETENTION_RUNS_DAYS);
        verify(globalSettingRepository).findById(SettingsService.KEY_RETENTION_AUDIT_DAYS);
        verify(globalSettingRepository).findById(SettingsService.KEY_RETENTION_ALERTS_DAYS);
    }

    @Test
    void agentRunEventsAndOrchestrationDecisions_PurgedOnAuditWindow() {
        when(globalSettingRepository.findById(anyString())).thenReturn(Optional.empty());
        when(sessionRepository.findAll()).thenReturn(List.of());
        when(alertEventRepository.findAll()).thenReturn(List.of());

        service.enforceRetentionPolicies();

        verify(jdbcTemplate).update(contains("DELETE FROM agent_run_events"), any(LocalDateTime.class));
        verify(jdbcTemplate).update(contains("DELETE FROM orchestration_decisions"), any(LocalDateTime.class));
    }
}
