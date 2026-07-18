package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-T004 atomicity proof, deterministic and single-threaded. The retry SQL is
 * UPDATE … WHERE status='FAILED' AND locked_at IS NULL AND retry_count < max_retries.
 * Two sequential POSTs against the same FAILED row prove the row-level mutex without
 * threads or latches: call 1 returns 200 (rowsAffected=1, row → QUEUED); call 2 returns
 * 409 reason=not_failed (rowsAffected=0, the WHERE clause no longer matches because
 * status is now QUEUED). Concurrency is a property of Postgres' single-statement update;
 * we don't re-test that — we only verify the controller's contract.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
class BackgroundJobRetryAtomicityIntegrationTest extends BaseIntegrationTest {

    @Autowired private BackgroundJobRepository jobRepository;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void retry_SequentialCalls_FirstSucceedsSecondConflictsWithNotFailedReason() {
        String jobId = UUID.randomUUID().toString();
        BackgroundJob seed = new BackgroundJob();
        seed.setId(jobId);
        seed.setJobType("TEST_RETRY_JOB");
        seed.setPayload("{}");
        seed.setStatus(JobStatus.FAILED);
        seed.setRetryCount(0);
        seed.setMaxRetries(3);
        jobRepository.saveAndFlush(seed);

        String userSuffix = Long.toHexString(System.nanoTime());
        HttpHeaders auth = authenticateAs(
                "bgjob-admin-" + userSuffix,
                "bgjob-admin-" + userSuffix + "@test.local",
                "pass-bgjob-1234",
                List.of("ROLE_ADMIN"));

        double okBefore = counter("ok");
        double notFailedBefore = counter("not_failed");

        ResponseEntity<Map> first = rest.exchange(
                url("/api/v1/observability/background-jobs/" + jobId + "/retry"),
                HttpMethod.POST, new HttpEntity<>(auth), Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "ok");

        BackgroundJob afterFirst = jobRepository.findById(jobId).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(afterFirst.getLockedAt()).isNull();

        ResponseEntity<Map> second = rest.exchange(
                url("/api/v1/observability/background-jobs/" + jobId + "/retry"),
                HttpMethod.POST, new HttpEntity<>(auth), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("reason", "not_failed");

        BackgroundJob afterSecond = jobRepository.findById(jobId).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(JobStatus.QUEUED);

        assertThat(counter("ok") - okBefore).isEqualTo(1.0);
        assertThat(counter("not_failed") - notFailedBefore).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.find("agm.observability.bgjob.retry")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
