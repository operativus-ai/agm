package ai.operativus.agentmanager.integration.jobs;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.control.service.queue.JobHandler;
import ai.operativus.agentmanager.control.service.queue.NonRetryableJobException;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobPriority;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.JobQueueTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pins the non-retryable failure contract on
 *   {@link PersistentJobQueueService}. A handler that throws
 *   {@link NonRetryableJobException} signals a terminal business outcome — the queue
 *   must DLQ the job on the FIRST failure WITHOUT re-running the handler, bypassing
 *   both retry layers (the in-line {@code RetryTemplate} and the outer re-queue).
 *
 *   <p>Motivation: {@code WorkflowExecutionJobHandler} throws this when a
 *   {@code workflow_run} ends FAILED. Retrying would re-invoke
 *   {@code executeWorkflowAsync}, minting a duplicate run row and re-executing every
 *   agent step. This test guards the queue half of that fix; the workflow half is
 *   pinned by {@code WorkflowsRuntimeTest.agentStepFailure_*}.
 *
 *   <p>Companion to {@code BackgroundJobRetryBackoffRuntimeTest}, which pins the
 *   opposite path: a generic exception DOES retry with exponential backoff.
 *
 * State: Stateless. The handler counts invocations via an AtomicInteger to prove the
 *   queue did not re-run it.
 */
@Import({
        JobQueueTestSupport.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class,
        BackgroundJobNonRetryableRuntimeTest.NonRetryableHandlerConfig.class
})
public class BackgroundJobNonRetryableRuntimeTest extends BaseIntegrationTest {

    private static final String NON_RETRYABLE_TYPE = "J2_NON_RETRYABLE_FAIL";

    @Autowired private PersistentJobQueueService queue;
    @Autowired private BackgroundJobRepository repo;
    @Autowired private JobQueueTestSupport jobs;
    @Autowired private NonRetryableHandlerConfig.CountingNonRetryableHandler handler;

    @Test
    void nonRetryableFailure_movesStraightToDlq_withoutRetryingOrRequeuing() {
        handler.invocations.set(0);
        BackgroundJob seeded = queue.enqueue(NON_RETRYABLE_TYPE, "agent-" + UUID.randomUUID(),
                "payload", JobPriority.NORMAL.getValue(), null);

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobFailure(seeded.getId(), Duration.ofSeconds(15));

        assertAll("non-retryable failure short-circuits both retry layers",
                () -> assertEquals(JobStatus.DLQ, terminal.getStatus(),
                        "a NonRetryableJobException must land the job in DLQ on the first failure"),
                () -> assertEquals(1, terminal.getRetryCount(),
                        "retry_count increments once (the failure is counted) but the job is NOT "
                                + "re-queued — a value > 1 means the outer re-queue path ran"),
                () -> assertNull(terminal.getNextRetryAt(),
                        "next_retry_at must NOT be set — the job is terminal, nothing will re-run it"),
                () -> assertEquals(1, handler.invocations.get(),
                        "the handler must run EXACTLY once — the in-line RetryTemplate must NOT "
                                + "re-invoke execute() for a NonRetryableJobException (maxAttempts "
                                + "would otherwise run it up to 3×)"));
    }

    /** Handler that always throws NonRetryableJobException and counts its invocations. */
    @TestConfiguration
    static class NonRetryableHandlerConfig {
        @Bean
        CountingNonRetryableHandler j2NonRetryableHandler() {
            return new CountingNonRetryableHandler();
        }

        static class CountingNonRetryableHandler implements JobHandler {
            final AtomicInteger invocations = new AtomicInteger();

            @Override public String jobType() { return NON_RETRYABLE_TYPE; }

            @Override public void execute(BackgroundJob job) {
                invocations.incrementAndGet();
                throw new NonRetryableJobException("J2 synthetic non-retryable failure for " + job.getId());
            }
        }
    }
}
