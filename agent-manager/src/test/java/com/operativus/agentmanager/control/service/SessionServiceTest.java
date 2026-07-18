package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.AgentSession;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.SessionRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SessionServiceTest {

    private static final String ORG = "org-test";

    @Mock private SessionRepository sessionRepository;
    @Mock private RunRepository runRepository;

    private SessionService service;
    private MockedStatic<AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        // SessionService resolves orgId via AgentContextHolder.getOrgId() first (ScopedValue
        // path), then falls back to SecurityContextHolder. Unit tests bind a fixed orgId here
        // so the tenant-scoped repository methods receive a predictable value.
        mockedContext = mockStatic(AgentContextHolder.class);
        mockedContext.when(AgentContextHolder::getOrgId).thenReturn(ORG);
        service = new SessionService(sessionRepository, runRepository);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    @Test
    void listSessions_WithUserId_FiltersByUserOrgAndTTL() {
        AgentSession session = new AgentSession();
        when(sessionRepository.findByUserIdAndOrgIdAndUpdatedAtAfter(eq("user-1"), eq(ORG), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<AgentSession> result = service.listSessions("user-1", null, Pageable.unpaged());

        assertEquals(1, result.getTotalElements());
        verify(sessionRepository).findByUserIdAndOrgIdAndUpdatedAtAfter(eq("user-1"), eq(ORG), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void listSessions_WithAgentId_FiltersByAgentOrgAndTTL() {
        AgentSession session = new AgentSession();
        when(sessionRepository.findByAgentIdAndOrgIdAndUpdatedAtAfter(eq("agent-1"), eq(ORG), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session)));

        Page<AgentSession> result = service.listSessions(null, "agent-1", Pageable.unpaged());

        assertEquals(1, result.getTotalElements());
        verify(sessionRepository).findByAgentIdAndOrgIdAndUpdatedAtAfter(eq("agent-1"), eq(ORG), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void listSessions_NoFilters_ReturnsByOrgAndTTLOnly() {
        when(sessionRepository.findByOrgIdAndUpdatedAtAfter(eq(ORG), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new AgentSession(), new AgentSession())));

        Page<AgentSession> result = service.listSessions(null, null, Pageable.unpaged());

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void getSession_ActiveSessionInCallerOrg_ReturnsSession() {
        AgentSession session = new AgentSession();
        session.setOrgId(ORG);
        session.setUpdatedAt(LocalDateTime.now()); // recently updated
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(session));

        Optional<AgentSession> result = service.getSession("sess-1", null);

        assertTrue(result.isPresent());
    }

    @Test
    void getSession_ExpiredSession_ThrowsBusinessValidation() {
        AgentSession session = new AgentSession();
        session.setOrgId(ORG);
        session.setUpdatedAt(LocalDateTime.now().minusHours(48)); // expired
        when(sessionRepository.findById("sess-old")).thenReturn(Optional.of(session));

        assertThrows(BusinessValidationException.class, () -> service.getSession("sess-old", null));
    }

    @Test
    void getSession_NotFound_ReturnsEmpty() {
        when(sessionRepository.findById("missing")).thenReturn(Optional.empty());

        Optional<AgentSession> result = service.getSession("missing", null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getSession_CrossTenant_ReturnsEmpty() {
        // Existence-leak protection: a session owned by org-other must not surface to ORG.
        AgentSession session = new AgentSession();
        session.setOrgId("org-other");
        session.setUpdatedAt(LocalDateTime.now());
        when(sessionRepository.findById("sess-foreign")).thenReturn(Optional.of(session));

        Optional<AgentSession> result = service.getSession("sess-foreign", null);

        assertTrue(result.isEmpty());
    }

    @Test
    void listRunsForSession_ScopedToCallerOrg() {
        AgentRun run = new AgentRun();
        when(runRepository.findBySessionIdAndOrgIdOrderByCreatedAtAsc("sess-1", ORG))
                .thenReturn(List.of(run));

        List<AgentRun> result = service.listRunsForSession("sess-1", null);

        assertEquals(1, result.size());
    }

    @Test
    void deleteSession_OwnedByCallerOrg_DeletesById() {
        when(sessionRepository.existsBySessionIdAndOrgId("sess-1", ORG)).thenReturn(true);

        service.deleteSession("sess-1", null);

        verify(sessionRepository).deleteById("sess-1");
    }

    @Test
    void deleteSession_CrossTenant_NoOps() {
        when(sessionRepository.existsBySessionIdAndOrgId("sess-foreign", ORG)).thenReturn(false);

        service.deleteSession("sess-foreign", null);

        verify(sessionRepository, never()).deleteById(any());
    }
}
