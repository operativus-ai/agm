package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.registry.MemberResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OrchestratorMembersTest {

    private static final String ORG = "TEST_ORG";

    @Mock private AgentRegistry agentRegistry;
    @Mock private MemberResolver memberResolver;

    /** Minimal AgentDefinition fixture — Mockito can mock records since Java 16, and
     *  we only need a handful of accessors (id, members, active, maintenanceMode). */
    private AgentDefinition buildAgent(String id, boolean active, boolean maintenance, List<String> members) {
        AgentDefinition def = org.mockito.Mockito.mock(AgentDefinition.class);
        // Lenient — not every test invokes every accessor on every fixture (e.g. self-filter
        // path on rootAgent only needs id; the maintenance-filter path only checks one member).
        org.mockito.Mockito.lenient().when(def.id()).thenReturn(id);
        org.mockito.Mockito.lenient().when(def.members()).thenReturn(members);
        org.mockito.Mockito.lenient().when(def.active()).thenReturn(active);
        org.mockito.Mockito.lenient().when(def.maintenanceMode()).thenReturn(maintenance);
        return def;
    }

    // --- Flag OFF (production-default path) ---

    @Test
    void resolveActive_flagOff_usesRootAgentMembersDirectly_resolverNotCalled() {
        AgentDefinition root = buildAgent("team", true, false, List.of("m-a", "m-b"));
        AgentDefinition mA = buildAgent("m-a", true, false, null);
        AgentDefinition mB = buildAgent("m-b", true, false, null);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);
        when(agentRegistry.findById("m-b", ORG)).thenReturn(mB);

        OrchestratorMembers om = new OrchestratorMembers(Optional.of(memberResolver), false);
        List<AgentDefinition> result = om.resolveActive(root, agentRegistry, ORG, "user-1", null);

        assertEquals(2, result.size(), "Both members must resolve when active + not in maintenance");
        verifyNoInteractions(memberResolver);
    }

    @Test
    void resolveActive_flagOff_filtersInactive() {
        AgentDefinition root = buildAgent("team", true, false, List.of("m-a", "m-b"));
        AgentDefinition mA = buildAgent("m-a", true, false, null);
        AgentDefinition mBInactive = buildAgent("m-b", false, false, null);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);
        when(agentRegistry.findById("m-b", ORG)).thenReturn(mBInactive);

        OrchestratorMembers om = new OrchestratorMembers(Optional.of(memberResolver), false);
        List<AgentDefinition> result = om.resolveActive(root, agentRegistry, ORG, "user-1", null);

        assertEquals(1, result.size());
        assertEquals("m-a", result.get(0).id());
    }

    @Test
    void resolveActive_flagOff_filtersMaintenanceAndSelf() {
        AgentDefinition root = buildAgent("team", true, false, List.of("team", "m-a", "m-b"));
        AgentDefinition mA = buildAgent("m-a", true, true, null); // maintenance
        AgentDefinition mB = buildAgent("m-b", true, false, null);
        when(agentRegistry.findById("team", ORG)).thenReturn(root);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);
        when(agentRegistry.findById("m-b", ORG)).thenReturn(mB);

        OrchestratorMembers om = new OrchestratorMembers(Optional.of(memberResolver), false);
        List<AgentDefinition> result = om.resolveActive(root, agentRegistry, ORG, "user-1", null);

        assertEquals(1, result.size(), "Self + maintenance must both be filtered");
        assertEquals("m-b", result.get(0).id());
    }

    @Test
    void resolveActive_flagOff_resolverAbsent_stillWorks() {
        AgentDefinition root = buildAgent("team", true, false, List.of("m-a"));
        AgentDefinition mA = buildAgent("m-a", true, false, null);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);

        OrchestratorMembers om = new OrchestratorMembers(Optional.empty(), false);
        assertEquals(List.of(mA), om.resolveActive(root, agentRegistry, ORG, "user-1", null));
    }

    // --- Flag ON (REQ-DR-2 path) ---

    @Test
    void resolveActive_flagOn_usesMemberResolverForSourceIds() {
        AgentDefinition root = buildAgent("team", true, false, List.of("static-1", "static-2"));
        AgentDefinition mA = buildAgent("resolved-a", true, false, null);
        // Resolver returns a different set than the static list
        when(memberResolver.resolveMembers("team", ORG, "user-1", null))
                .thenReturn(List.of("resolved-a"));
        when(agentRegistry.findById("resolved-a", ORG)).thenReturn(mA);

        OrchestratorMembers om = new OrchestratorMembers(Optional.of(memberResolver), true);
        List<AgentDefinition> result = om.resolveActive(root, agentRegistry, ORG, "user-1", null);

        assertEquals(1, result.size());
        assertEquals("resolved-a", result.get(0).id(),
                "Flag-on path must use resolver's id list, not rootAgent.members()");
        verify(agentRegistry, never()).findById(eq("static-1"), any());
        verify(agentRegistry, never()).findById(eq("static-2"), any());
    }

    @Test
    void resolveActive_flagOn_resolverReturnsEmpty_returnsEmpty() {
        AgentDefinition root = buildAgent("team", true, false, List.of("static-1"));
        when(memberResolver.resolveMembers("team", ORG, "user-1", null)).thenReturn(List.of());

        OrchestratorMembers om = new OrchestratorMembers(Optional.of(memberResolver), true);
        assertTrue(om.resolveActive(root, agentRegistry, ORG, "user-1", null).isEmpty(),
                "Resolver-returned empty roster must not silently fall back to static list");
    }

    @Test
    void resolveActive_flagOn_resolverAbsent_fallsBackToStatic() {
        // Defensive: flag-on but bean missing (misconfiguration). Falls back to static
        // so the team still runs rather than failing the entire orchestration call.
        AgentDefinition root = buildAgent("team", true, false, List.of("m-a"));
        AgentDefinition mA = buildAgent("m-a", true, false, null);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);

        OrchestratorMembers om = new OrchestratorMembers(Optional.empty(), true);
        assertEquals(List.of(mA), om.resolveActive(root, agentRegistry, ORG, "user-1", null));
    }

    // --- Static back-compat ---

    @Test
    void staticResolveActive_bypasesResolverEntirely() {
        AgentDefinition root = buildAgent("team", true, false, List.of("m-a"));
        AgentDefinition mA = buildAgent("m-a", true, false, null);
        when(agentRegistry.findById("m-a", ORG)).thenReturn(mA);

        // Static method exists for legacy callers — must NEVER call a resolver
        List<AgentDefinition> result = OrchestratorMembers.resolveActive(root, agentRegistry, ORG);
        assertEquals(1, result.size());
    }
}
