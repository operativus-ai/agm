package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.service.SystemAuditService;
import ai.operativus.agentmanager.control.service.queue.JobQueueAdminState;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BackgroundJobControllerTest {

    private MockMvc mockMvc;
    private MeterRegistry meterRegistry;

    @Mock private BackgroundJobRepository jobRepository;
    @Mock private JobQueueAdminState adminState;
    @Mock private SystemAuditService systemAuditService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new BackgroundJobController(jobRepository, adminState, systemAuditService, meterRegistry)).build();
    }

    // --- List ---

    @Test
    void list_NoStatusFilter_DelegatesToFindAllPaged() throws Exception {
        BackgroundJob j = job("job-1", JobStatus.QUEUED, 0, 3);
        when(jobRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(j), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/observability/background-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value("job-1"))
                .andExpect(jsonPath("$.content[0].status").value("QUEUED"));
    }

    @Test
    void list_StatusFilter_DelegatesToFindByStatus() throws Exception {
        when(jobRepository.findByStatusOrderByCreatedAtDesc(eq(JobStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/observability/background-jobs?status=FAILED"))
                .andExpect(status().isOk());
    }

    // --- Retry ---

    @Test
    void retry_EligibleJob_Returns200AndIncrementsOk() throws Exception {
        when(jobRepository.atomicRetry("job-ok")).thenReturn(1);

        mockMvc.perform(post("/api/v1/observability/background-jobs/job-ok/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        assertThat(meterRegistry.get("agm.observability.bgjob.retry")
                .tag("outcome", "ok").counter().count()).isEqualTo(1.0);
    }

    @Test
    void retry_MissingJob_Returns404AndIncrementsNotFound() throws Exception {
        when(jobRepository.atomicRetry("job-missing")).thenReturn(0);
        when(jobRepository.findById("job-missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/observability/background-jobs/job-missing/retry"))
                .andExpect(status().isNotFound());

        assertThat(meterRegistry.get("agm.observability.bgjob.retry")
                .tag("outcome", "not_found").counter().count()).isEqualTo(1.0);
    }

    @Test
    void retry_NotFailedOrLocked_Returns409AndIncrementsNotFailed() throws Exception {
        // Row exists, retryCount < maxRetries, but status is COMPLETED (or locked).
        // Either way the UPDATE missed its predicate — 409 with reason=not_failed.
        BackgroundJob completed = job("job-completed", JobStatus.COMPLETED, 0, 3);
        when(jobRepository.atomicRetry("job-completed")).thenReturn(0);
        when(jobRepository.findById("job-completed")).thenReturn(Optional.of(completed));

        mockMvc.perform(post("/api/v1/observability/background-jobs/job-completed/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("not_failed"));

        assertThat(meterRegistry.get("agm.observability.bgjob.retry")
                .tag("outcome", "not_failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void retry_AtRetryCap_Returns422AndIncrementsMaxRetries() throws Exception {
        BackgroundJob capped = job("job-capped", JobStatus.FAILED, 3, 3);
        when(jobRepository.atomicRetry("job-capped")).thenReturn(0);
        when(jobRepository.findById("job-capped")).thenReturn(Optional.of(capped));

        mockMvc.perform(post("/api/v1/observability/background-jobs/job-capped/retry"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("max_retries"));

        assertThat(meterRegistry.get("agm.observability.bgjob.retry")
                .tag("outcome", "max_retries").counter().count()).isEqualTo(1.0);
    }

    // --- Status summary ---

    @Test
    void statusSummary_FillsZerosForMissingStatuses() throws Exception {
        // Repo returns rows for QUEUED and FAILED only.
        when(jobRepository.countGroupByStatus()).thenReturn(List.of(
                new Object[]{JobStatus.QUEUED, 7L},
                new Object[]{JobStatus.FAILED, 2L}));

        mockMvc.perform(get("/api/v1/observability/background-jobs/status-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.QUEUED").value(7))
                .andExpect(jsonPath("$.FAILED").value(2))
                .andExpect(jsonPath("$.PROCESSING").value(0))
                .andExpect(jsonPath("$.PAUSED").value(0))
                .andExpect(jsonPath("$.COMPLETED").value(0))
                .andExpect(jsonPath("$.DLQ").value(0));
    }

    @Test
    void statusSummary_EmptyRepo_AllZeros() throws Exception {
        when(jobRepository.countGroupByStatus()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/observability/background-jobs/status-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.QUEUED").value(0))
                .andExpect(jsonPath("$.PROCESSING").value(0))
                .andExpect(jsonPath("$.DLQ").value(0));
    }

    @Test
    void retry_LockedFailedRow_TreatedAsNotFailed() throws Exception {
        // retryCount < maxRetries AND status=FAILED, but locked_at is set — another
        // worker just picked it up. Predicate fails, fallback SELECT sees retry cap
        // is not reached, so it's a 409, not a 422.
        BackgroundJob locked = job("job-locked", JobStatus.FAILED, 1, 3);
        locked.setLockedAt(LocalDateTime.now());
        when(jobRepository.atomicRetry("job-locked")).thenReturn(0);
        when(jobRepository.findById("job-locked")).thenReturn(Optional.of(locked));

        mockMvc.perform(post("/api/v1/observability/background-jobs/job-locked/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("not_failed"));
    }

    private static BackgroundJob job(String id, JobStatus status, int retryCount, int maxRetries) {
        // createdAt is set via JPA auditing on persist; tests don't need it populated
        // since the controller's DTO mapping passes null through unchanged.
        BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setJobType("TEST_JOB");
        j.setStatus(status);
        j.setRetryCount(retryCount);
        j.setMaxRetries(maxRetries);
        return j;
    }
}
