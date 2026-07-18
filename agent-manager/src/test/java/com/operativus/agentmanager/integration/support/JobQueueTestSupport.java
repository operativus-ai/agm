package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Domain Responsibility: Drives {@link PersistentJobQueueService} synchronously from
 *   tests. Production polls {@code processQueue()} every 30s on a {@code @Scheduled}
 *   tick, but {@code application-test.properties} pushes that interval to 24h
 *   (decision 4.4) — tests instead invoke the work method directly and then poll
 *   the job row for terminal state via {@link Awaitility}.
 * State: Stateless (delegates to injected Spring beans).
 *
 * Why Awaitility: {@code processQueue()} dispatches each job to a virtual thread
 *   ({@code Thread.ofVirtual().start(...)}), so the call returns before the handler
 *   has run. Polling the job row is the only reliable signal that work finished
 *   without leaning on {@code Thread.sleep}.
 *
 * Usage: {@code @Import(JobQueueTestSupport.class)} on the test class, then
 *   {@code @Autowired protected JobQueueTestSupport jobs;}.
 */
public class JobQueueTestSupport {

    private static final Set<JobStatus> TERMINAL = EnumSet.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.DLQ);
    private static final Set<JobStatus> FAILED = EnumSet.of(JobStatus.FAILED, JobStatus.DLQ);

    private final PersistentJobQueueService queue;
    private final BackgroundJobRepository jobs;

    public JobQueueTestSupport(PersistentJobQueueService queue, BackgroundJobRepository jobs) {
        this.queue = queue;
        this.jobs = jobs;
    }

    /** Drains a single batch of QUEUED jobs the same way the @Scheduled tick would. */
    public void processNow() {
        queue.processQueue();
    }

    /** Polls until the job reaches COMPLETED. Fails the test if it terminates in FAILED/DLQ. */
    public BackgroundJob awaitJobSuccess(String jobId, Duration timeout) {
        BackgroundJob job = awaitTerminal(jobId, timeout);
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new AssertionError("Expected job " + jobId + " to COMPLETE but ended in "
                    + job.getStatus() + " (error=" + job.getErrorMessage() + ")");
        }
        return job;
    }

    /** Polls until the job reaches FAILED or DLQ. Fails the test if it COMPLETEs instead. */
    public BackgroundJob awaitJobFailure(String jobId, Duration timeout) {
        BackgroundJob job = awaitTerminal(jobId, timeout);
        if (!FAILED.contains(job.getStatus())) {
            throw new AssertionError("Expected job " + jobId + " to FAIL but ended in " + job.getStatus());
        }
        return job;
    }

    /** Polls until the job reaches any terminal status (COMPLETED / FAILED / DLQ). */
    public BackgroundJob awaitJobTerminal(String jobId, Duration timeout) {
        return awaitTerminal(jobId, timeout);
    }

    public BackgroundJob find(String jobId) {
        return jobs.findById(jobId)
                .orElseThrow(() -> new AssertionError("Job not found: " + jobId));
    }

    private BackgroundJob awaitTerminal(String jobId, Duration timeout) {
        Awaitility.await("job " + jobId + " terminal status")
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jobs.findById(jobId)
                        .map(j -> TERMINAL.contains(j.getStatus()))
                        .orElse(false));
        return find(jobId);
    }
}
