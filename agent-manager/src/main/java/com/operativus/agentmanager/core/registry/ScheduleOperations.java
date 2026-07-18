package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.ScheduleDTO;
import java.util.List;

/**
 * Domain Responsibility: Generic contract for scheduling capabilities decoupled from concrete implementations.
 * Allows pure generic orchestration modules (e.g. Agent tools) to register execution cycles without importing implementation logic.
 */
public interface ScheduleOperations {

    ScheduleDTO createSchedule(ScheduleDTO dto);
    
    ScheduleDTO getSchedule(String id);
    
    List<ScheduleDTO> getAllSchedules();
    
    ScheduleDTO updateSchedule(String id, ScheduleDTO dto);
    
    void deleteSchedule(String id);
}
