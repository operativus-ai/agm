package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.dto.SkillRequest;
import com.operativus.agentmanager.control.dto.SkillResponse;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.AgentSkillRepository;
import com.operativus.agentmanager.control.repository.SkillRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentSkill;
import com.operativus.agentmanager.core.entity.Skill;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SkillServiceTest {

    private static final String ORG = "TEST_ORG";

    @Mock private SkillRepository skillRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentRepository agentRepository;

    private SkillService service;
    private MockedStatic<AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        mockedContext = mockStatic(AgentContextHolder.class);
        mockedContext.when(AgentContextHolder::getOrgId).thenReturn(ORG);
        service = new SkillService(skillRepository, agentSkillRepository, agentRepository);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    // --- createSkill ---

    @Test
    void createSkill_validRequest_persistsAndStampsOrgId() {
        SkillRequest req = new SkillRequest("weather", "Get weather", "Use the weather_lookup tool.",
                Set.of("weather_lookup"), true);

        when(skillRepository.existsByOrgIdAndName(ORG, "weather")).thenReturn(false);
        when(skillRepository.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));

        SkillResponse resp = service.createSkill(req);

        assertNotNull(resp.id());
        assertEquals("weather", resp.name());
        assertEquals(ORG, resp.orgId());

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillRepository).save(captor.capture());
        assertEquals(ORG, captor.getValue().getOrgId(),
                "createSkill must stamp orgId from AgentContextHolder");
        assertTrue(captor.getValue().getAllowedTools().contains("weather_lookup"));
    }

    @Test
    void createSkill_duplicateNameInOrg_throwsIllegalArgument() {
        SkillRequest req = new SkillRequest("weather", null, null, Set.of(), true);

        when(skillRepository.existsByOrgIdAndName(ORG, "weather")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createSkill(req),
                "Duplicate skill name within an org must reject creation");
        verify(skillRepository, never()).save(any());
    }

    @Test
    void createSkill_invalidToolNameWithSpace_throwsIllegalArgument() {
        SkillRequest req = new SkillRequest("bad", null, null, Set.of("invalid tool"), true);

        assertThrows(IllegalArgumentException.class, () -> service.createSkill(req),
                "Tool names with spaces violate the [a-z][a-z0-9_]* format");
        verify(skillRepository, never()).save(any());
    }

    @Test
    void createSkill_invalidToolNameUpperCase_throwsIllegalArgument() {
        SkillRequest req = new SkillRequest("bad", null, null, Set.of("BadToolName"), true);

        assertThrows(IllegalArgumentException.class, () -> service.createSkill(req),
                "Tool names must be snake_case; upper-case rejected");
        verify(skillRepository, never()).save(any());
    }

    @Test
    void createSkill_emptyToolName_throwsIllegalArgument() {
        SkillRequest req = new SkillRequest("bad", null, null, Set.of(""), true);

        assertThrows(IllegalArgumentException.class, () -> service.createSkill(req));
        verify(skillRepository, never()).save(any());
    }

    @Test
    void createSkill_nullAllowedTools_isAcceptedAsEmptySet() {
        SkillRequest req = new SkillRequest("notools", "desc", "snippet", null, true);

        when(skillRepository.existsByOrgIdAndName(ORG, "notools")).thenReturn(false);
        when(skillRepository.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));

        SkillResponse resp = service.createSkill(req);

        assertEquals("notools", resp.name());
        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillRepository).save(captor.capture());
        assertTrue(captor.getValue().getAllowedTools().isEmpty());
    }

    // --- getSkill / listSkills ---

    @Test
    void getSkill_exists_returnsResponse() {
        Skill skill = new Skill("s1", ORG, "weather", "desc", "snippet");
        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.of(skill));

        SkillResponse resp = service.getSkill("s1");

        assertEquals("weather", resp.name());
        assertEquals(ORG, resp.orgId());
    }

    @Test
    void getSkill_crossTenant_throwsResourceNotFound() {
        // Cross-tenant access: findByIdAndOrgId returns empty for another org's skill,
        // service throws 404-equivalent rather than 403 (per §79 RBAC pattern).
        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getSkill("s1"));
    }

    @Test
    void listSkills_returnsPagedResponses() {
        Skill skill = new Skill("s1", ORG, "weather", "desc", "snippet");
        when(skillRepository.findAllByOrgId(eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(skill)));

        Page<SkillResponse> page = service.listSkills(Pageable.unpaged());

        assertEquals(1, page.getTotalElements());
        assertEquals("weather", page.getContent().get(0).name());
    }

    // --- updateSkill ---

    @Test
    void updateSkill_exists_updatesAndReturns() {
        Skill existing = new Skill("s1", ORG, "old", "desc", "snippet");
        SkillRequest req = new SkillRequest("new", "new desc", "new snippet",
                Set.of("tool_a", "tool_b"), false);

        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.of(existing));
        when(skillRepository.existsByOrgIdAndName(ORG, "new")).thenReturn(false);
        when(skillRepository.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));

        SkillResponse resp = service.updateSkill("s1", req);

        assertEquals("new", resp.name());
        assertEquals("new desc", resp.description());
        assertEquals("new snippet", resp.systemPromptSnippet());
        assertFalse(resp.active());
        assertTrue(resp.allowedTools().contains("tool_a"));
    }

    @Test
    void updateSkill_crossTenant_throwsResourceNotFound() {
        SkillRequest req = new SkillRequest("x", null, null, Set.of(), true);
        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updateSkill("s1", req));
        verify(skillRepository, never()).save(any());
    }

    @Test
    void updateSkill_renameToTakenName_throwsIllegalArgument() {
        Skill existing = new Skill("s1", ORG, "old", "desc", "snippet");
        SkillRequest req = new SkillRequest("taken", null, null, Set.of(), true);

        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.of(existing));
        when(skillRepository.existsByOrgIdAndName(ORG, "taken")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.updateSkill("s1", req));
        verify(skillRepository, never()).save(any());
    }

    @Test
    void updateSkill_sameName_doesNotCheckUniqueness() {
        Skill existing = new Skill("s1", ORG, "same", "desc", "snippet");
        SkillRequest req = new SkillRequest("same", "updated desc", null, Set.of(), true);

        when(skillRepository.findByIdAndOrgId("s1", ORG)).thenReturn(Optional.of(existing));
        when(skillRepository.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));

        SkillResponse resp = service.updateSkill("s1", req);

        assertEquals("updated desc", resp.description());
        verify(skillRepository, never()).existsByOrgIdAndName(eq(ORG), eq("same"));
    }

    // --- deleteSkill ---

    @Test
    void deleteSkill_exists_deletesAttachmentsAndSkill() {
        when(skillRepository.existsByIdAndOrgId("s1", ORG)).thenReturn(true);

        service.deleteSkill("s1");

        verify(agentSkillRepository).deleteAllBySkillId("s1");
        verify(skillRepository).deleteById("s1");
    }

    @Test
    void deleteSkill_crossTenant_throwsResourceNotFound() {
        when(skillRepository.existsByIdAndOrgId("s1", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.deleteSkill("s1"));
        verify(skillRepository, never()).deleteById(any());
        verify(agentSkillRepository, never()).deleteAllBySkillId(any());
    }

    // --- attachSkill / detachSkill ---

    @Test
    void attachSkill_bothEntitiesOwnedByCallerOrg_persistsAttachment() {
        when(agentRepository.existsByIdAndOrgId("agent-1", ORG)).thenReturn(true);
        when(skillRepository.existsByIdAndOrgId("skill-1", ORG)).thenReturn(true);
        when(agentSkillRepository.existsByAgentIdAndSkillId("agent-1", "skill-1")).thenReturn(false);

        service.attachSkill("agent-1", "skill-1", 50);

        ArgumentCaptor<AgentSkill> captor = ArgumentCaptor.forClass(AgentSkill.class);
        verify(agentSkillRepository).save(captor.capture());
        assertEquals("agent-1", captor.getValue().getAgentId());
        assertEquals("skill-1", captor.getValue().getSkillId());
        assertEquals(50, captor.getValue().getPriority());
    }

    @Test
    void attachSkill_crossTenantAgent_throwsResourceNotFound() {
        when(agentRepository.existsByIdAndOrgId("agent-other", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.attachSkill("agent-other", "skill-1", 100));
        verify(agentSkillRepository, never()).save(any());
    }

    @Test
    void attachSkill_crossTenantSkill_throwsResourceNotFound() {
        when(agentRepository.existsByIdAndOrgId("agent-1", ORG)).thenReturn(true);
        when(skillRepository.existsByIdAndOrgId("skill-other", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.attachSkill("agent-1", "skill-other", 100));
        verify(agentSkillRepository, never()).save(any());
    }

    @Test
    void attachSkill_alreadyAttached_isIdempotent() {
        when(agentRepository.existsByIdAndOrgId("agent-1", ORG)).thenReturn(true);
        when(skillRepository.existsByIdAndOrgId("skill-1", ORG)).thenReturn(true);
        when(agentSkillRepository.existsByAgentIdAndSkillId("agent-1", "skill-1")).thenReturn(true);

        service.attachSkill("agent-1", "skill-1", 100);

        verify(agentSkillRepository, never()).save(any());
    }

    @Test
    void detachSkill_ownedAgent_deletes() {
        when(agentRepository.existsByIdAndOrgId("agent-1", ORG)).thenReturn(true);

        service.detachSkill("agent-1", "skill-1");

        verify(agentSkillRepository).deleteByAgentIdAndSkillId("agent-1", "skill-1");
    }

    @Test
    void detachSkill_crossTenantAgent_throwsResourceNotFound() {
        when(agentRepository.existsByIdAndOrgId("agent-other", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.detachSkill("agent-other", "skill-1"));
        verify(agentSkillRepository, never()).deleteByAgentIdAndSkillId(any(), any());
    }

    // --- findActiveSkillsForAgent (SkillOperations SPI) ---

    @Test
    void findActiveSkillsForAgent_delegatesToRepository() {
        Skill s1 = new Skill("s1", ORG, "skill1", null, null);
        Skill s2 = new Skill("s2", ORG, "skill2", null, null);
        when(skillRepository.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(s1, s2));

        List<Skill> result = service.findActiveSkillsForAgent("agent-1");

        assertEquals(2, result.size());
        assertEquals("s1", result.get(0).getId());
        assertEquals("s2", result.get(1).getId());
    }

    @Test
    void findActiveSkillsForAgent_noSkills_returnsEmptyList() {
        when(skillRepository.findActiveSkillsForAgent("agent-empty")).thenReturn(List.of());

        List<Skill> result = service.findActiveSkillsForAgent("agent-empty");

        assertTrue(result.isEmpty());
    }
}
