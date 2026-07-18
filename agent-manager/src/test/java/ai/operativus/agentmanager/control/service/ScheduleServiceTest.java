package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.entity.Schedule;
import ai.operativus.agentmanager.core.model.ScheduleDTO;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.ScheduleRepository;
import ai.operativus.agentmanager.control.repository.ScheduleRunRepository;
import ai.operativus.agentmanager.control.repository.WorkflowRepository;
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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ScheduleServiceTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ScheduleRunRepository scheduleRunRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ai.operativus.agentmanager.control.repository.SessionRepository sessionRepository;
    @Mock private ai.operativus.agentmanager.control.repository.SpotBatchJobRepository spotBatchJobRepository;
    @Mock private ObjectMapper objectMapper;

    private ScheduleService service;
    private MockedStatic<ai.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        // Stub AgentContextHolder so the service's tenant resolver returns a deterministic
        // orgId without needing a Spring SecurityContext. Tests pin "TEST_ORG" for clarity;
        // the service falls back to DEFAULT_SYSTEM_ORG only when getOrgId() returns null/blank.
        mockedContext = mockStatic(ai.operativus.agentmanager.core.callback.AgentContextHolder.class);
        mockedContext.when(ai.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId)
                .thenReturn("TEST_ORG");
        service = new ScheduleService(scheduleRepository, scheduleRunRepository, agentRepository, workflowRepository, sessionRepository, spotBatchJobRepository, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    @Test
    void getAllSchedules_ReturnsMappedDTOs() {
        Schedule schedule = new Schedule();
        schedule.setId("sched-1");
        schedule.setName("Daily Report");
        schedule.setCronExpression("0 0 9 * * ?");
        schedule.setTargetType("AGENT");
        schedule.setTargetId("agent-1");
        schedule.setActive(true);

        when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(schedule)));

        List<ScheduleDTO> result = service.getAllSchedules();

        assertEquals(1, result.size());
        assertEquals("Daily Report", result.get(0).name());
        assertEquals("0 0 9 * * ?", result.get(0).cronExpression());
    }

    @Test
    void getAllSchedules_Paginated_ReturnsPage() {
        Schedule schedule = new Schedule();
        schedule.setId("sched-1");
        schedule.setName("Daily Report");
        schedule.setCronExpression("0 0 9 * * ?");
        schedule.setTargetType("AGENT");
        schedule.setTargetId("agent-1");

        when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(schedule)));

        Page<ScheduleDTO> result = service.getAllSchedules(Pageable.unpaged());

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getSchedule_Exists_ReturnsDTO() {
        Schedule schedule = new Schedule();
        schedule.setId("sched-1");
        schedule.setName("Daily Report");
        schedule.setCronExpression("0 0 9 * * ?");
        schedule.setTargetType("AGENT");
        schedule.setTargetId("agent-1");

        when(scheduleRepository.findByIdAndOrgId("sched-1", "TEST_ORG"))
                .thenReturn(Optional.of(schedule));

        ScheduleDTO result = service.getSchedule("sched-1");

        assertEquals("Daily Report", result.name());
    }

    @Test
    void createSchedule_ValidCron_SavesAndReturnsDTO() {
        ScheduleDTO dto = new ScheduleDTO(null, "New Schedule", "Desc", "0 0 9 * * ?", "AGENT", "agent-1", null, "prompt", true, null, null, null, null, null, null);

        when(agentRepository.existsByIdAndOrgId("agent-1", "TEST_ORG")).thenReturn(true);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(i -> i.getArgument(0));

        ScheduleDTO result = service.createSchedule(dto);

        assertNotNull(result.id());
        assertEquals("New Schedule", result.name());

        ArgumentCaptor<Schedule> captor = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleRepository).save(captor.capture());
        assertEquals("0 0 9 * * ?", captor.getValue().getCronExpression());
        // Tenant scoping: the persisted Schedule must carry the caller's orgId.
        assertEquals("TEST_ORG", captor.getValue().getOrgId(),
                "createSchedule must stamp orgId from AgentContextHolder; got " + captor.getValue().getOrgId());
    }

    @Test
    void updateSchedule_Exists_UpdatesAndReturns() {
        Schedule existing = new Schedule();
        existing.setId("sched-1");
        existing.setName("Old Name");
        existing.setCronExpression("0 0 9 * * ?");
        existing.setTargetType("AGENT");
        existing.setTargetId("agent-1");
        existing.setVersion(0L);

        // version matches existing.getVersion() so the F3 client-known-version pre-check
        // passes; existing tests pinning happy-path update keep working.
        // F6 trailing null = no DAG dependency.
        ScheduleDTO update = new ScheduleDTO("sched-1", "Updated", "Desc", "0 0 10 * * ?", "AGENT", "agent-1", null, null, true, null, null, null, null, 0L, null);

        when(agentRepository.existsByIdAndOrgId("agent-1", "TEST_ORG")).thenReturn(true);
        when(scheduleRepository.findByIdAndOrgId("sched-1", "TEST_ORG"))
                .thenReturn(Optional.of(existing));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(i -> i.getArgument(0));

        ScheduleDTO result = service.updateSchedule("sched-1", update);

        assertEquals("Updated", result.name());
        verify(scheduleRepository).save(any(Schedule.class));
    }

    @Test
    void deleteSchedule_Deletes() {
        // Tenant guard: deletion only fires when the row belongs to the caller.
        when(scheduleRepository.existsByIdAndOrgId("sched-1", "TEST_ORG")).thenReturn(true);

        service.deleteSchedule("sched-1");

        verify(scheduleRepository).deleteById("sched-1");
    }

    @Test
    void getAllSchedules_Active_ComputesNextRunAtFromCron() {
        Schedule active = new Schedule();
        active.setId("sched-active");
        active.setName("Daily Report");
        // Every minute — guarantees a future trigger from any "now"
        active.setCronExpression("0 * * * * *");
        active.setTargetType("AGENT");
        active.setTargetId("agent-1");
        active.setActive(true);

        when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(active)));
        when(scheduleRunRepository.findLatestStartedAtByScheduleIds(any()))
                .thenReturn(List.of());

        Page<ScheduleDTO> result = service.getAllSchedules(Pageable.unpaged());

        ScheduleDTO dto = result.getContent().get(0);
        assertNotNull(dto.nextRunAt(), "nextRunAt must be computed for active schedules with valid cron");
        assertTrue(dto.nextRunAt().isAfter(LocalDateTime.now().minusMinutes(1)),
                "nextRunAt must be in the future relative to test start");
        assertNull(dto.lastRunAt(), "lastRunAt is null when the schedule has no runs");
    }

    @Test
    void getAllSchedules_Inactive_NextRunAtIsNull() {
        Schedule inactive = new Schedule();
        inactive.setId("sched-inactive");
        inactive.setName("Daily Report");
        inactive.setCronExpression("0 * * * * *");
        inactive.setTargetType("AGENT");
        inactive.setTargetId("agent-1");
        inactive.setActive(false);

        when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inactive)));
        when(scheduleRunRepository.findLatestStartedAtByScheduleIds(any()))
                .thenReturn(List.of());

        Page<ScheduleDTO> result = service.getAllSchedules(Pageable.unpaged());

        assertNull(result.getContent().get(0).nextRunAt(),
                "nextRunAt must be null for inactive schedules to avoid misleading the operator UI");
    }

    @Test
    void getAllSchedules_PopulatesLastRunAtFromRepoAggregate() {
        Schedule schedule = new Schedule();
        schedule.setId("sched-with-runs");
        schedule.setName("Daily Report");
        schedule.setCronExpression("0 * * * * *");
        schedule.setTargetType("AGENT");
        schedule.setTargetId("agent-1");
        schedule.setActive(true);

        LocalDateTime lastStartedAt = LocalDateTime.of(2026, 5, 9, 14, 30);

        when(scheduleRepository.findAllByOrgId(eq("TEST_ORG"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(schedule)));
        when(scheduleRunRepository.findLatestStartedAtByScheduleIds(any()))
                .thenReturn(Collections.singletonList(new Object[] { "sched-with-runs", lastStartedAt }));

        Page<ScheduleDTO> result = service.getAllSchedules(Pageable.unpaged());

        assertEquals(lastStartedAt, result.getContent().get(0).lastRunAt(),
                "lastRunAt must reflect the repo aggregate's MAX(startedAt) for the schedule");
    }

    // --- IDOR pin tests (S1) ---

    @Test
    void createSchedule_CrossOrgAgent_ThrowsIllegalArgument() {
        // targetId belongs to a different org — validateTargetOwnership must reject it
        ScheduleDTO dto = new ScheduleDTO(null, "Bad Schedule", "Desc", "0 0 9 * * ?",
                "AGENT", "agent-other-org", null, null, true, null, null, null, null, null, null);

        when(agentRepository.existsByIdAndOrgId("agent-other-org", "TEST_ORG")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createSchedule(dto),
                "createSchedule must reject a targetId that belongs to a different org");
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void createSchedule_CrossOrgWorkflow_ThrowsIllegalArgument() {
        ScheduleDTO dto = new ScheduleDTO(null, "Bad Schedule", "Desc", "0 0 9 * * ?",
                "WORKFLOW", "wf-other-org", null, null, true, null, null, null, null, null, null);

        when(workflowRepository.existsByIdAndOrgId("wf-other-org", "TEST_ORG")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.createSchedule(dto),
                "createSchedule must reject a workflow targetId that belongs to a different org");
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void updateSchedule_CrossOrgAgent_ThrowsIllegalArgument() {
        ScheduleDTO dto = new ScheduleDTO("sched-1", "Name", "Desc", "0 0 9 * * ?",
                "AGENT", "agent-other-org", null, null, true, null, null, null, null, null, null);

        when(agentRepository.existsByIdAndOrgId("agent-other-org", "TEST_ORG")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.updateSchedule("sched-1", dto),
                "updateSchedule must reject a targetId that belongs to a different org");
        verify(scheduleRepository, never()).save(any());
    }
}
