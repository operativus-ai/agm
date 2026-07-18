package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.model.ScheduleDTO;
import ai.operativus.agentmanager.core.model.ScheduleRunDTO;
import ai.operativus.agentmanager.core.model.SpotBatchJobDTO;
import ai.operativus.agentmanager.control.service.ScheduleExecutionPoller;
import ai.operativus.agentmanager.control.service.ScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class SchedulesController {

    private final ScheduleService scheduleService;
    private final ScheduleExecutionPoller scheduleExecutionPoller;

    public SchedulesController(ScheduleService scheduleService,
                               ScheduleExecutionPoller scheduleExecutionPoller) {
        this.scheduleService = scheduleService;
        this.scheduleExecutionPoller = scheduleExecutionPoller;
    }

    /**
     * @summary Retrieves a paginated list of all scheduled tasks.
     * @logic Accepts Spring Data Pageable and delegates to ScheduleService.
     */
    @GetMapping
    public org.springframework.data.domain.Page<ScheduleDTO> listSchedules(@org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        return scheduleService.getAllSchedules(pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScheduleDTO> getSchedule(@PathVariable String id) {
        ScheduleDTO schedule = scheduleService.getSchedule(id);
        return schedule != null ? ResponseEntity.ok(schedule) : ResponseEntity.notFound().build();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDTO createSchedule(@RequestBody ScheduleDTO scheduleDTO) {
        return scheduleService.createSchedule(scheduleDTO);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScheduleDTO> updateSchedule(@PathVariable String id, @RequestBody ScheduleDTO scheduleDTO) {
        ScheduleDTO updated = scheduleService.updateSchedule(id, scheduleDTO);
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSchedule(@PathVariable String id) {
        scheduleService.deleteSchedule(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Paginated execution history for a schedule.
     * @logic F4 — was previously unbounded ({@code List<ScheduleRunDTO>}). High-frequency
     *        schedules over weeks would have leaked tens of thousands of rows per call.
     *        Now returns a {@code Page<ScheduleRunDTO>} with the standard nested-content
     *        wire shape (pinned by {@code spring.data.web.pageable.serialization-mode=direct}).
     *        Default sort is startedAt DESC and is enforced by the service-layer derived
     *        method name; the {@code Pageable}'s sort field is ignored.
     */
    @GetMapping("/{id}/runs")
    public org.springframework.data.domain.Page<ScheduleRunDTO> getScheduleRuns(
            @PathVariable String id,
            @org.springdoc.core.annotations.ParameterObject org.springframework.data.domain.Pageable pageable) {
        return scheduleService.getScheduleRuns(id, pageable);
    }

    @PostMapping("/{id}/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> triggerSchedule(@PathVariable String id) {
        scheduleExecutionPoller.manualTrigger(id);
        return ResponseEntity.accepted().body(Map.of("message", "Schedule execution triggered."));
    }

    /**
     * @summary Tenant-scoped spot-batch listing for the FinOps dashboard.
     * @logic F5 — was previously {@code spotBatchJobRepository.findAll()}, which leaked
     *        cross-tenant batch jobs to any ROLE_ADMIN. Now delegates to
     *        {@code ScheduleService.getSpotBatches} which filters by caller's org via
     *        {@code SpotBatchJobRepository.findAllByOrgId(callerOrgId)}.
     */
    @GetMapping("/batches")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SpotBatchJobDTO> getSpotBatches() {
        return scheduleService.getSpotBatches();
    }
}
