package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MemberResolverServiceTest {

    private static final String ORG = "TEST_ORG";

    @Mock private AgentRepository agentRepository;

    private MemberResolverService service() {
        return new MemberResolverService(agentRepository);
    }

    private AgentEntity team(String id, String resolverType, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(ORG);
        a.setTeam(true);
        a.setMembers(members);
        a.setMemberResolverType(resolverType);
        return a;
    }

    // --- STATIC strategy ---

    @Test
    void resolveMembers_static_returnsAgentsMembersVerbatim() {
        AgentEntity teamAgent = team("team-1", "STATIC", List.of("m-a", "m-b", "m-c"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertEquals(List.of("m-a", "m-b", "m-c"), result,
                "STATIC must return agents.members in stored order");
    }

    @Test
    void resolveMembers_static_nullMembers_returnsEmptyList() {
        AgentEntity teamAgent = team("team-1", "STATIC", null);
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertTrue(result.isEmpty(),
                "null members must become empty list — never null");
    }

    @Test
    void resolveMembers_static_emptyMembers_returnsEmptyList() {
        AgentEntity teamAgent = team("team-1", "STATIC", new ArrayList<>());
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertTrue(result.isEmpty());
    }

    // --- ORG_TIER + FEATURE_FLAG stubs ---

    @Test
    void resolveMembers_orgTier_stubReturnsStaticRoster() {
        AgentEntity teamAgent = team("team-1", "ORG_TIER", List.of("m-a", "m-b"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertEquals(List.of("m-a", "m-b"), result,
                "ORG_TIER is a v1 stub — must fall through to the static roster");
    }

    @Test
    void resolveMembers_featureFlag_stubReturnsStaticRoster() {
        AgentEntity teamAgent = team("team-1", "FEATURE_FLAG", List.of("m-a"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertEquals(List.of("m-a"), result,
                "FEATURE_FLAG is a v1 stub — must fall through to the static roster");
    }

    // --- defensive parsing ---

    @Test
    void resolveMembers_unknownResolverType_fallsBackToStatic() {
        AgentEntity teamAgent = team("team-1", "GARBAGE_VALUE", List.of("m-a"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertEquals(List.of("m-a"), result,
                "Hand-edited DB row with bad enum value must fall back to STATIC, not crash");
    }

    @Test
    void resolveMembers_nullResolverType_fallsBackToStatic() {
        AgentEntity teamAgent = team("team-1", null, List.of("m-a"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        List<String> result = service().resolveMembers("team-1", ORG, "user-1", null);

        assertEquals(List.of("m-a"), result);
    }

    @Test
    void resolveMembers_blankResolverType_fallsBackToStatic() {
        AgentEntity teamAgent = team("team-1", "   ", List.of("m-a"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        assertEquals(List.of("m-a"), service().resolveMembers("team-1", ORG, "user-1", null));
    }

    @Test
    void resolveMembers_lowercaseResolverType_normalizedToEnum() {
        AgentEntity teamAgent = team("team-1", "org_tier", List.of("m-a"));
        when(agentRepository.findByIdAndOrgId("team-1", ORG)).thenReturn(Optional.of(teamAgent));

        // Lowercase / mixed-case is normalized (case-insensitive parse). Useful when
        // a hand-typed migration drops the wrong case into the column.
        assertEquals(List.of("m-a"), service().resolveMembers("team-1", ORG, "user-1", null));
    }

    // --- cross-tenant defense ---

    @Test
    void resolveMembers_crossTenantTeamAgent_returnsEmptyList() {
        when(agentRepository.findByIdAndOrgId("team-other-org", ORG)).thenReturn(Optional.empty());

        List<String> result = service().resolveMembers("team-other-org", ORG, "user-1", null);

        assertTrue(result.isEmpty(),
                "Cross-tenant team agent must yield empty roster (not throw) — the orchestrator surfaces 'no members configured' transparently");
    }

    @Test
    void resolveMembers_nullTeamAgentId_returnsEmpty() {
        assertTrue(service().resolveMembers(null, ORG, "user-1", null).isEmpty());
    }

    @Test
    void resolveMembers_blankTeamAgentId_returnsEmpty() {
        assertTrue(service().resolveMembers("  ", ORG, "user-1", null).isEmpty());
    }

    @Test
    void resolveMembers_nullOrgId_returnsEmpty() {
        assertTrue(service().resolveMembers("team-1", null, "user-1", null).isEmpty());
    }

    @Test
    void resolveMembers_blankOrgId_returnsEmpty() {
        assertTrue(service().resolveMembers("team-1", "  ", "user-1", null).isEmpty());
    }
}
