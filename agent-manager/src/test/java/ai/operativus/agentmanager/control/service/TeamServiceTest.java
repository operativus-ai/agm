package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.Team;
import ai.operativus.agentmanager.core.entity.TeamMember;
import ai.operativus.agentmanager.core.model.TeamDTO;
import ai.operativus.agentmanager.core.model.TeamHealthDTO;
import ai.operativus.agentmanager.control.repository.TeamRepository;
import ai.operativus.agentmanager.control.repository.TeamMemberRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private ai.operativus.agentmanager.control.repository.TransitionEdgeRepository transitionEdgeRepository;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private ai.operativus.agentmanager.control.finops.service.DailySpendService dailySpendService;

    private TeamService service;
    private MockedStatic<ai.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        // Tenant scoping resolves orgId via AgentContextHolder; stub deterministically
        // (the service falls back to DEFAULT_SYSTEM_ORG only when getOrgId() returns null/blank).
        mockedContext = mockStatic(ai.operativus.agentmanager.core.callback.AgentContextHolder.class);
        mockedContext.when(ai.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                .thenReturn("TEST_ORG");
        service = new TeamService(teamRepository, teamMemberRepository, transitionEdgeRepository, entityManager, dailySpendService);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    @Test
    void getAllTeams_ReturnsMappedDTOs() {
        Team team = new Team();
        team.setId("team-1");
        team.setName("Alpha Team");
        team.setTeamMode("ROUTER");

        when(teamRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(team)));

        List<TeamDTO> result = service.getAllTeams();

        assertEquals(1, result.size());
        assertEquals("Alpha Team", result.get(0).name());
        assertEquals("ROUTER", result.get(0).teamMode());
    }

    @Test
    void getAllTeams_Paginated_ReturnsPage() {
        Team team = new Team();
        team.setId("team-1");
        team.setName("Alpha Team");

        when(teamRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(team)));
        // toDtoEnriched calls teamMemberRepository.findByTeamId and entityManager.find;
        // both are mocks that return null/empty — the test only asserts the totalElements.

        Page<TeamDTO> result = service.getAllTeams(Pageable.unpaged());

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getTeamById_Exists_ReturnsDTO() {
        Team team = new Team();
        team.setId("team-1");
        team.setName("Alpha Team");

        when(teamRepository.findByIdAndOrgId("team-1", "TEST_ORG")).thenReturn(Optional.of(team));

        Optional<TeamDTO> result = service.getTeamById("team-1");

        assertTrue(result.isPresent());
        assertEquals("Alpha Team", result.get().name());
    }

    @Test
    void getTeamById_NotFound_ReturnsEmpty() {
        when(teamRepository.findByIdAndOrgId("missing", "TEST_ORG")).thenReturn(Optional.empty());

        Optional<TeamDTO> result = service.getTeamById("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void createTeam_GeneratesIdAndSaves() {
        TeamDTO dto = new TeamDTO(null, "New Team", "Desc", "SEQUENTIAL", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        when(teamRepository.save(any(Team.class))).thenAnswer(i -> i.getArgument(0));

        TeamDTO result = service.createTeam(dto);

        assertNotNull(result.id());
        assertEquals("New Team", result.name());

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository).save(captor.capture());
        assertEquals("SEQUENTIAL", captor.getValue().getTeamMode());
        // Tenant scoping: created Team must carry the caller's orgId.
        assertEquals("TEST_ORG", captor.getValue().getOrgId(),
                "createTeam must stamp orgId from AgentContextHolder; got " + captor.getValue().getOrgId());
    }

    @Test
    void updateTeam_Exists_UpdatesFields() {
        Team existing = new Team();
        existing.setId("team-1");
        existing.setName("Old Name");

        TeamDTO update = new TeamDTO("team-1", "New Name", "New Desc", "SEQUENTIAL", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        when(teamRepository.findByIdAndOrgId("team-1", "TEST_ORG")).thenReturn(Optional.of(existing));
        when(teamRepository.save(any(Team.class))).thenAnswer(i -> i.getArgument(0));

        TeamDTO result = service.updateTeam("team-1", update);

        assertEquals("New Name", result.name());
        verify(teamRepository).save(any(Team.class));
    }

    @Test
    void getTeamHealth_currentDailySpend_sumsMemberAgentSpend() {
        // Un-stub of the previously-hardcoded 0.0: team health daily spend is the sum over the
        // team's member agents for today, delegated to DailySpendService.
        Team team = new Team();
        team.setId("team-1");
        team.setName("Alpha Team");
        team.setMaxDailySpend(50.0);

        TeamMember m1 = mock(TeamMember.class);
        when(m1.getAgentId()).thenReturn("agent-a");
        TeamMember m2 = mock(TeamMember.class);
        when(m2.getAgentId()).thenReturn("agent-b");

        when(teamRepository.findByIdAndOrgId("team-1", "TEST_ORG")).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamId("team-1")).thenReturn(List.of(m1, m2));
        when(transitionEdgeRepository.countByTeamId("team-1")).thenReturn(0L);
        when(entityManager.find(eq(AgentEntity.class), any())).thenReturn(null);
        when(dailySpendService.currentTeamDailySpendUsd(any())).thenReturn(12.5);

        TeamHealthDTO health = service.getTeamHealth("team-1");

        assertEquals(12.5, health.currentDailySpend());
        assertEquals(50.0, health.maxDailySpend());

        // The two member agentIds are exactly what the spend aggregation is scoped to.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(dailySpendService).currentTeamDailySpendUsd(captor.capture());
        assertTrue(captor.getValue().containsAll(List.of("agent-a", "agent-b")));
    }

    @Test
    void deleteTeam_Deletes() {
        // Tenant guard: deletion only fires when the row belongs to the caller.
        when(teamRepository.existsByIdAndOrgId("team-1", "TEST_ORG")).thenReturn(true);

        service.deleteTeam("team-1");

        verify(teamRepository).deleteById("team-1");
    }
}
