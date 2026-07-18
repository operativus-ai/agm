package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.RoutingDecisionRepository;
import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoutingDecisionRecorderServiceTest {

    @Mock private RoutingDecisionRepository repository;
    private SimpleMeterRegistry meterRegistry;
    private RoutingDecisionRecorderService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RoutingDecisionRecorderService(repository, meterRegistry);
    }

    @Test
    void recordDecision_resolvedAgent_persistsResolvedRow() {
        service.recordDecision("ORG-1", "user-1", "session-1", "hello",
                "router-team", RoutingDecisionEntity.StrategyUsed.DEFAULT_ROUTER,
                null, 0, 17, null);

        ArgumentCaptor<RoutingDecisionEntity> captor = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
        verify(repository).save(captor.capture());
        RoutingDecisionEntity saved = captor.getValue();
        assertEquals("ORG-1", saved.getOrgId());
        assertEquals("router-team", saved.getResolvedAgentId());
        assertEquals(RoutingDecisionEntity.ResolutionStatus.RESOLVED, saved.getResolutionStatus());
        assertEquals(RoutingDecisionEntity.StrategyUsed.DEFAULT_ROUTER, saved.getStrategyUsed());
        assertEquals(17, saved.getLatencyMs());
        assertEquals(64, saved.getMessageHash().length(),
                "SHA-256 hex must be 64 chars regardless of message length");
        assertEquals(5, saved.getMessageLength());
    }

    @Test
    void recordDecision_nullResolvedAgent_marksUnresolved() {
        service.recordDecision("ORG-1", null, null, "msg",
                null, RoutingDecisionEntity.StrategyUsed.NONE,
                null, 0, 3, "no strategy matched");

        ArgumentCaptor<RoutingDecisionEntity> captor = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(RoutingDecisionEntity.ResolutionStatus.UNRESOLVED, captor.getValue().getResolutionStatus());
        assertEquals("no strategy matched", captor.getValue().getRationale());
    }

    @Test
    void recordDecision_repositoryThrows_swallowsAndDoesNotPropagate() {
        doThrow(new RuntimeException("simulated DB failure")).when(repository).save(any());
        // Must not propagate — recorder is best-effort observability.
        assertDoesNotThrow(() -> service.recordDecision("ORG-1", "u", null, "m",
                null, RoutingDecisionEntity.StrategyUsed.NONE, null, 0, 1, null));
    }

    @Test
    void recordDecision_emitsStrategyAndOutcomeCounter() {
        service.recordDecision("ORG-1", "u", null, "m",
                "agent-x", RoutingDecisionEntity.StrategyUsed.RULE_SUBSTRING,
                null, 5, 12, null);

        double count = meterRegistry.find("agm.routing.decisions")
                .tag("strategy", "RULE_SUBSTRING")
                .tag("outcome", "RESOLVED")
                .tag("org", "ORG-1")
                .counter().count();
        assertEquals(1.0, count, 0.0001);
    }
}
