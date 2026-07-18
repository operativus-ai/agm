package ai.operativus.agentmanager.control.service;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.Schedule;
import ai.operativus.agentmanager.core.entity.ScheduleRun;
import ai.operativus.agentmanager.core.model.ScheduleDTO;
import ai.operativus.agentmanager.core.model.ScheduleRunDTO;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.ScheduleRepository;
import ai.operativus.agentmanager.control.repository.ScheduleRunRepository;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import ai.operativus.agentmanager.control.repository.WorkflowRepository;
import ai.operativus.agentmanager.core.registry.ScheduleOperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Manages CRON-based scheduling persistence for Agents, Teams, and Workflows.
 * State: Stateless (Pure JPA Service)
 */
@Service
public class ScheduleService implements ScheduleOperations {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    
    private final ScheduleRepository scheduleRepository;
    private final ScheduleRunRepository scheduleRunRepository;
    private final AgentRepository agentRepository;
    private final WorkflowRepository workflowRepository;
    private final SessionRepository sessionRepository;
    private final ai.operativus.agentmanager.control.repository.SpotBatchJobRepository spotBatchJobRepository;
    private final ObjectMapper objectMapper;

    public ScheduleService(ScheduleRepository scheduleRepository, ScheduleRunRepository scheduleRunRepository,
                           AgentRepository agentRepository, WorkflowRepository workflowRepository,
                           SessionRepository sessionRepository,
                           ai.operativus.agentmanager.control.repository.SpotBatchJobRepository spotBatchJobRepository,
                           ObjectMapper objectMapper) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.agentRepository = agentRepository;
        this.workflowRepository = workflowRepository;
        this.sessionRepository = sessionRepository;
        this.spotBatchJobRepository = spotBatchJobRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ScheduleDTO> getAllSchedules() {
        // Tenant-scoped: only return schedules owned by the caller's org. The
        // ScheduleOperations interface keeps this method for non-paginated callers
        // (registry-level introspection); the public REST surface uses the paginated
        // overload below.
        List<Schedule> entities = scheduleRepository.findAllByOrgId(callerOrgId(),
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        Map<String, LocalDateTime> lastRuns = lastRunsByScheduleId(entities);
        return entities.stream()
                .map(s -> mapToDTO(s, lastRuns.get(s.getId())))
                .collect(Collectors.toList());
    }

    /**
     * @summary Retrieves a paginated set of scheduled tasks owned by the caller's org.
     * @logic Delegates to the tenant-scoped repository finder and maps entities to DTOs via {@code Page.map()}.
     *        Batch-fetches the latest {@code startedAt} per schedule to populate {@code lastRunAt}
     *        without an N+1.
     */
    public org.springframework.data.domain.Page<ScheduleDTO> getAllSchedules(org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Schedule> page =
                scheduleRepository.findAllByOrgId(callerOrgId(), pageable);
        Map<String, LocalDateTime> lastRuns = lastRunsByScheduleId(page.getContent());
        return page.map(s -> mapToDTO(s, lastRuns.get(s.getId())));
    }

    @Override
    public ScheduleDTO getSchedule(String id) {
        return scheduleRepository.findByIdAndOrgId(id, callerOrgId())
                .map(s -> mapToDTO(s, lastRunForSchedule(s.getId())))
                .orElse(null);
    }

    private void validateTargetOwnership(String targetType, String targetId, String orgId) {
        if (targetType == null || targetId == null) return;
        boolean owned = switch (targetType.toUpperCase()) {
            case "AGENT", "TEAM" -> agentRepository.existsByIdAndOrgId(targetId, orgId);
            case "WORKFLOW" -> workflowRepository.existsByIdAndOrgId(targetId, orgId);
            default -> true;
        };
        if (!owned) throw new IllegalArgumentException("Target not found for ID: " + targetId);
    }

    private void validateResumeSessionOwnership(String resumeSessionId, String orgId) {
        if (resumeSessionId == null || resumeSessionId.isBlank()) return;
        if (!sessionRepository.existsBySessionIdAndOrgId(resumeSessionId, orgId)) {
            throw new IllegalArgumentException(
                    "resumeSessionId must reference a session in the caller's organization");
        }
    }

    /**
     * F6 — Validate the DAG dependency chain on create/update.
     *   <ul>
     *     <li>{@code null} / blank dependsOnScheduleId → no-op (no dependency).</li>
     *     <li>Self-reference rejected (update path).</li>
     *     <li>Parent must exist in the caller's org (cross-tenant parent rejected).</li>
     *     <li>Walk the parent chain; if {@code selfId} appears anywhere, reject as a cycle.</li>
     *     <li>Bounded walk (64 hops) to guarantee termination on stored data that
     *         shouldn't exist but might (defense-in-depth).</li>
     *   </ul>
     */
    private void validateDependencyChain(String selfId, String dependsOnScheduleId, String orgId) {
        if (dependsOnScheduleId == null || dependsOnScheduleId.isBlank()) return;
        if (selfId != null && selfId.equals(dependsOnScheduleId)) {
            throw new IllegalArgumentException("Schedule cannot depend on itself");
        }
        if (!scheduleRepository.existsByIdAndOrgId(dependsOnScheduleId, orgId)) {
            throw new IllegalArgumentException("dependsOnScheduleId not found for ID: " + dependsOnScheduleId);
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (selfId != null) visited.add(selfId);
        String cursor = dependsOnScheduleId;
        int hops = 0;
        while (cursor != null && !cursor.isBlank()) {
            if (++hops > 64) {
                throw new IllegalArgumentException("Dependency chain too deep at: " + cursor);
            }
            if (!visited.add(cursor)) {
                throw new IllegalArgumentException("Dependency cycle detected at: " + cursor);
            }
            Optional<Schedule> parent = scheduleRepository.findByIdAndOrgId(cursor, orgId);
            if (parent.isEmpty()) break;
            cursor = parent.get().getDependsOnScheduleId();
        }
    }

    @Override
    public ScheduleDTO createSchedule(ScheduleDTO dto) {
        validateCron(dto.cronExpression());
        String orgId = callerOrgId();
        validateTargetOwnership(dto.targetType(), dto.targetId(), orgId);
        validateResumeSessionOwnership(dto.resumeSessionId(), orgId);
        // F6 — DAG dependency: parent must exist in the same org. (Cycle detection
        // is vacuous on create since the row has no id yet, but the parent-exists
        // and self-reference checks still apply via validateDependencyChain.)
        validateDependencyChain(null, dto.dependsOnScheduleId(), orgId);
        Schedule schedule = new Schedule(
                UUID.randomUUID().toString(),
                dto.name(),
                dto.description(),
                dto.cronExpression(),
                dto.targetType(),
                dto.targetId(),
                dto.resumeSessionId(),
                dto.contextualPrompt(),
                dto.isActive() != null ? dto.isActive() : true
        );
        schedule.setDependsOnScheduleId(dto.dependsOnScheduleId());
        // Server-derived orgId — the request body's @JsonProperty(READ_ONLY) field
        // already silences body-injected orgId, but we set it explicitly here as
        // defense-in-depth in case Jackson configuration ever drifts.
        schedule.setOrgId(orgId);
        Schedule saved = scheduleRepository.save(schedule);
        // Newly-created schedule has no runs yet — lastRunAt is null.
        return mapToDTO(saved, null);
    }

    @Override
    public ScheduleDTO updateSchedule(String id, ScheduleDTO dto) {
        validateCron(dto.cronExpression());
        String orgId = callerOrgId();
        validateTargetOwnership(dto.targetType(), dto.targetId(), orgId);
        validateResumeSessionOwnership(dto.resumeSessionId(), orgId);
        // F6 — DAG dependency: self-reference + cycle detection. Runs before the
        // findByIdAndOrgId check so a malformed body is rejected with 400 even on
        // cross-tenant ids (mirrors validateTargetOwnership order). Same caveat:
        // 404-vs-400 leak is consistent with the existing validate-then-find shape.
        validateDependencyChain(id, dto.dependsOnScheduleId(), orgId);
        Optional<Schedule> optionalSchedule = scheduleRepository.findByIdAndOrgId(id, orgId);
        if (optionalSchedule.isPresent()) {
            Schedule schedule = optionalSchedule.get();
            // Client-known-version conflict detection — F3. PUT requires the version the
            // client read; mismatch surfaces as 409 via GlobalExceptionHandler. JPA's
            // @Version on the entity provides defense-in-depth at the DB layer if two
            // requests race past the equality check.
            if (dto.version() == null) {
                throw new IllegalArgumentException(
                        "version is required for PUT — supply the version returned by GET or POST");
            }
            if (!java.util.Objects.equals(dto.version(), schedule.getVersion())) {
                throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        Schedule.class, id);
            }
            schedule.setName(dto.name());
            schedule.setDescription(dto.description());
            schedule.setCronExpression(dto.cronExpression());
            schedule.setTargetType(dto.targetType());
            schedule.setTargetId(dto.targetId());
            schedule.setResumeSessionId(dto.resumeSessionId());
            schedule.setContextualPrompt(dto.contextualPrompt());
            schedule.setDependsOnScheduleId(dto.dependsOnScheduleId());
            if (dto.isActive() != null) {
                schedule.setActive(dto.isActive());
            }
            // orgId is immutable post-create; body cannot rewrite tenant.
            Schedule saved = scheduleRepository.save(schedule);
            return mapToDTO(saved, lastRunForSchedule(saved.getId()));
        }
        return null;
    }

    @Override
    public void deleteSchedule(String id) {
        // Cross-tenant delete is a no-op (existsByIdAndOrgId returns false → skip). Same
        // controller-level shape as before (DELETE returns 204 unconditionally), but the
        // mutation never touches another tenant's row.
        if (scheduleRepository.existsByIdAndOrgId(id, callerOrgId())) {
            scheduleRepository.deleteById(id);
        }
    }

    /**
     * @summary Retrieves a paginated slice of the historical execution logs for a specific schedule.
     * @logic Sorted by most recent execution first; the derived-method DESC ordering wins
     *   regardless of the {@code Pageable}'s sort. Cross-tenant lookups return an empty
     *   {@code Page} (the parent schedule is invisible, so its runs are too).
     *
     *   Pagination was added in F4 (PR #707-ish, changeset n/a — wire-shape only) because
     *   the previous unbounded return could leak tens of thousands of rows per call for
     *   high-frequency schedules.
     */
    public org.springframework.data.domain.Page<ScheduleRunDTO> getScheduleRuns(
            String scheduleId, org.springframework.data.domain.Pageable pageable) {
        if (!scheduleRepository.existsByIdAndOrgId(scheduleId, callerOrgId())) {
            return org.springframework.data.domain.Page.empty(pageable);
        }
        return scheduleRunRepository
                .findByScheduleIdOrderByStartedAtDesc(scheduleId, pageable)
                .map(this::mapRunToDTO);
    }

    /**
     * @summary Tenant-scoped spot-batch listing for {@code GET /api/v1/schedules/batches}.
     * @logic Filters by {@code callerOrgId()} so admins of different tenants no longer
     *        see each other's batch jobs. Coerces {@code null} progress / cost to 0 to
     *        preserve the wire-shape contract pinned by
     *        {@code SchedulesBatchesEndpointRuntimeTest}. F5.
     */
    public List<ai.operativus.agentmanager.core.model.SpotBatchJobDTO> getSpotBatches() {
        return spotBatchJobRepository.findAllByOrgId(callerOrgId()).stream()
                .map(entity -> new ai.operativus.agentmanager.core.model.SpotBatchJobDTO(
                        entity.getId(),
                        entity.getJob(),
                        entity.getStatus(),
                        entity.getProgress() != null ? entity.getProgress() : 0,
                        entity.getCost() != null ? entity.getCost() : 0.0,
                        entity.getCompute()))
                .collect(Collectors.toList());
    }

    /**
     * Resolves the caller's {@code orgId} from {@link AgentContextHolder}, falling back to
     * {@link TenantConstants#DEFAULT_SYSTEM_ORG} when the context is unset (system callers
     * like the scheduler poller). Mirrors the helper pattern in
     * {@code KnowledgeBaseController.callerOrgId()}.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId == null || orgId.isBlank()) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
    }

    private void validateCron(String cron) {
        if (!CronExpression.isValidExpression(cron)) {
             throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }
    }

    /**
     * Computes the next-trigger {@link LocalDateTime} for an active schedule, or null if the
     * schedule is inactive, the cron expression is invalid, or the cron has no future trigger
     * from the current instant. Mirrors the {@link ZonedDateTime}-based approach in
     * {@code ScheduleExecutionPoller#isScheduleDue} so on-the-wire timestamps match the
     * scheduler's evaluation domain.
     */
    private static LocalDateTime computeNextRunAt(Schedule s) {
        if (s.getActive() == null || !s.getActive()) {
            return null;
        }
        try {
            CronExpression expr = CronExpression.parse(s.getCronExpression());
            ZonedDateTime next = expr.next(ZonedDateTime.now(ZoneId.systemDefault()));
            return next == null ? null : next.toLocalDateTime();
        } catch (IllegalArgumentException e) {
            // Defensive: pre-existing rows could carry an invalid expression that bypasses
            // the create/update validation gate (e.g. predates validateCron). Don't 500 the
            // listing — just omit nextRunAt for that row.
            log.warn("Schedule [{}] has an invalid cron expression [{}]; nextRunAt unavailable",
                    s.getId(), s.getCronExpression());
            return null;
        }
    }

    private Map<String, LocalDateTime> lastRunsByScheduleId(List<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return Map.of();
        }
        List<String> ids = schedules.stream().map(Schedule::getId).collect(Collectors.toList());
        return scheduleRunRepository.findLatestStartedAtByScheduleIds(ids).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (LocalDateTime) row[1]
                ));
    }

    private LocalDateTime lastRunForSchedule(String scheduleId) {
        return scheduleRunRepository.findLatestStartedAtByScheduleIds(List.of(scheduleId)).stream()
                .findFirst()
                .map(row -> (LocalDateTime) row[1])
                .orElse(null);
    }

    private ScheduleDTO mapToDTO(Schedule s, LocalDateTime lastRunAt) {
        return new ScheduleDTO(
            s.getId(),
            s.getName(),
            s.getDescription(),
            s.getCronExpression(),
            s.getTargetType(),
            s.getTargetId(),
            s.getResumeSessionId(),
            s.getContextualPrompt(),
            s.getActive(),
            s.getCreatedAt(),
            s.getUpdatedAt(),
            lastRunAt,
            computeNextRunAt(s),
            s.getVersion(),
            s.getDependsOnScheduleId()
        );
    }

    private ScheduleRunDTO mapRunToDTO(ScheduleRun r) {
        try {
            return new ScheduleRunDTO(
                r.getId(),
                r.getScheduleId(),
                r.getStatus(),
                r.getStartedAt(),
                r.getCompletedAt(),
                r.getErrorMessage(),
                r.getOutput() != null ? objectMapper.readTree(r.getOutput()) : null,
                r.getAgentRunId()
            );
        } catch (Exception e) {
            log.error("Error parsing output JSON for run [{}]", r.getId(), e);
            return new ScheduleRunDTO(r.getId(), r.getScheduleId(), r.getStatus(), r.getStartedAt(), r.getCompletedAt(), r.getErrorMessage(), null, r.getAgentRunId());
        }
    }
}
