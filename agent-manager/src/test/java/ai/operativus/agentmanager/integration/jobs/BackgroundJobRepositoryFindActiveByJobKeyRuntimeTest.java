package ai.operativus.agentmanager.integration.jobs;

import ai.operativus.agentmanager.control.repository.BackgroundJobRepository;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.enums.JobStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link BackgroundJobRepository#findActiveByJobKey}, the
 *   deduplication guard consumed by {@code PersistentJobQueueService.enqueue}. Wrong
 *   results have direct cost:
 *   <ul>
 *     <li><b>False positive</b> (returns active row when none exists) → legit
 *         enqueue silently dropped, the user's intended work never runs</li>
 *     <li><b>False negative</b> (returns empty when an active row exists) →
 *         silent double-enqueue of the same job → for LLM jobs, billable
 *         duplicate spend AND duplicate side effects</li>
 *   </ul>
 *
 *   <p>The query: {@code WHERE j.jobKey = :jobKey AND j.status NOT IN
 *   ('COMPLETED', 'FAILED', 'DLQ', 'CANCELLED')}. Three independent constraints:
 *   <ol>
 *     <li>Key match — only rows with the exact {@code jobKey}</li>
 *     <li>Active-status filter — only rows whose status is NOT one of the 4 terminal
 *         statuses (so active set = QUEUED, PROCESSING, PAUSED — pinned exhaustively)</li>
 *     <li>Single result via {@code Optional} — relies on the application invariant
 *         "at most one active row per jobKey" (typically enforced by a unique
 *         partial index, but the query returns Optional rather than List so a
 *         data inconsistency would error rather than silently picking one)</li>
 *   </ol>
 *
 *   <p>The query uses string literals for the terminal-status set, so a future
 *   {@link JobStatus} rename (e.g. DLQ → DEAD_LETTER) would silently miss those
 *   rows, breaking dedup. This test pins all four terminal values by name.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BackgroundJobRepositoryFindActiveByJobKeyRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private BackgroundJobRepository jobRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    // ════════════════════════════════════════════════════════════════
    // Happy path — active statuses are returned
    // ════════════════════════════════════════════════════════════════

    @Test
    void findActiveByJobKey_queuedJob_isReturned() {
        BackgroundJob j = jobRepo.save(job("agent-run:user-A", JobStatus.QUEUED));
        Optional<BackgroundJob> result = jobRepo.findActiveByJobKey("agent-run:user-A");
        assertTrue(result.isPresent(), "QUEUED job must be returned as active");
        assertEquals(j.getId(), result.get().getId());
    }

    @Test
    void findActiveByJobKey_processingJob_isReturned() {
        // Mid-execution rows must dedup against new enqueues — otherwise a second
        // copy starts while the first is still running. For LLM jobs, billable.
        jobRepo.save(job("agent-run:user-B", JobStatus.PROCESSING));
        assertTrue(jobRepo.findActiveByJobKey("agent-run:user-B").isPresent(),
                "PROCESSING job MUST be returned as active — guards against double-execution");
    }

    @Test
    void findActiveByJobKey_pausedJob_isReturned() {
        // PAUSED is intentionally part of the active set — admin-pause should NOT
        // allow new enqueues to slip past dedup.
        jobRepo.save(job("agent-run:user-C", JobStatus.PAUSED));
        assertTrue(jobRepo.findActiveByJobKey("agent-run:user-C").isPresent(),
                "PAUSED job MUST be returned as active — admin pause must not break dedup");
    }

    // ════════════════════════════════════════════════════════════════
    // Terminal statuses — exhaustive exclusion
    // ════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"COMPLETED", "FAILED", "DLQ", "CANCELLED"})
    void findActiveByJobKey_terminalStatusesAreExcluded(JobStatus terminalStatus) {
        // Each terminal-status row must be invisible to dedup — otherwise stale
        // COMPLETED/FAILED/etc rows would forever block re-enqueue.
        jobRepo.save(job("agent-run:terminal-test", terminalStatus));
        assertFalse(jobRepo.findActiveByJobKey("agent-run:terminal-test").isPresent(),
                "status=" + terminalStatus + " must NOT be returned as active");
    }

    // ════════════════════════════════════════════════════════════════
    // Key scoping
    // ════════════════════════════════════════════════════════════════

    @Test
    void findActiveByJobKey_differentJobKey_notReturned() {
        jobRepo.save(job("agent-run:user-A", JobStatus.QUEUED));
        assertFalse(jobRepo.findActiveByJobKey("agent-run:user-B").isPresent(),
                "querying for different jobKey must not return user-A's row");
    }

    @Test
    void findActiveByJobKey_unknownKey_returnsEmpty() {
        jobRepo.save(job("agent-run:user-A", JobStatus.QUEUED));
        assertFalse(jobRepo.findActiveByJobKey("agent-run:nonexistent-key").isPresent());
    }

    @Test
    void findActiveByJobKey_noRowsAtAll_returnsEmpty() {
        assertFalse(jobRepo.findActiveByJobKey("any-key").isPresent());
    }

    // Note on history coexistence: the schema has
    //   CREATE UNIQUE INDEX idx_background_jobs_job_key ON background_jobs(job_key)
    //   WHERE job_key IS NOT NULL;
    // so terminal + active rows can NEVER share a jobKey. The dedup query's
    // "active-status filter" is defense-in-depth — at runtime there's at most one row
    // per jobKey anyway. Re-enqueue after a terminal run requires that the prior row
    // be deleted or its jobKey nulled first. The parameterized terminal-exclusion
    // test above already covers the active-filter logic per-status; coexistence pins
    // would test a scenario the schema prevents.

    // ════════════════════════════════════════════════════════════════
    // Null-jobKey edge — defensive
    // ════════════════════════════════════════════════════════════════

    @Test
    void findActiveByJobKey_nullJobKey_returnsEmpty() {
        // jobKey is nullable on the table; SQL `j.jobKey = NULL` is always false.
        // Caller passing null should get empty, not error and not match null-jobKey rows.
        jobRepo.save(job(null, JobStatus.QUEUED));
        assertFalse(jobRepo.findActiveByJobKey(null).isPresent(),
                "null jobKey query must return empty (SQL = NULL semantics)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static BackgroundJob job(String jobKey, JobStatus status) {
        BackgroundJob j = new BackgroundJob();
        j.setId(UUID.randomUUID().toString()); // String @Id, no @GeneratedValue
        j.setJobType("test-job-type");
        j.setPayload("{}"); // NOT NULL
        j.setStatus(status);
        j.setJobKey(jobKey);
        j.setRetryCount(0);
        j.setMaxRetries(3);
        // createdAt is auto-managed (no setter); Hibernate/Spring auditing fills it.
        return j;
    }
}
