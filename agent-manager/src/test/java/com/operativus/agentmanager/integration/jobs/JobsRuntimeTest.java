package com.operativus.agentmanager.integration.jobs;

import com.operativus.agentmanager.control.repository.BackgroundJobRepository;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.model.JobStatusResponse;
import com.operativus.agentmanager.core.model.enums.JobStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pins the HTTP contract for {@code JobsController}'s
 *   {@code GET /api/jobs/{jobId}} polling endpoint. The {@code JobStatusResponse} record
 *   shape is the contract the frontend job-status poller depends on.
 *   <p>
 *   <b>Org-scoping (PR #284):</b> {@code JobsController} resolves the caller's org via
 *   {@link com.operativus.agentmanager.control.security.CallerContext#resolveCallerOrgId}
 *   and the repository's EXISTS subquery joins through {@code agent_id → agents.org_id}.
 *   The unknown-ID 404 path requires the caller to have an org (else {@code requireNonNull}
 *   throws 500). These tests use {@link BaseIntegrationTest#registerLoginWithOrg} to set
 *   the JWT org claim and seed agents into the matching org so the EXISTS predicate
 *   resolves correctly.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class JobsRuntimeTest extends BaseIntegrationTest {

    private static final String TEST_ORG = "jobs-test-org";

    @Autowired private BackgroundJobRepository jobRepository;

    private BackgroundJob seedTerminalJob() {
        String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        // Seed the agent in TEST_ORG so the JobsController EXISTS subquery resolves.
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'fake-jobs-model', true, ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "Jobs Test Agent " + agentId, TEST_ORG);
        BackgroundJob j = new BackgroundJob(
                UUID.randomUUID().toString(),
                agentId,
                "test.poll",
                "{\"input\":\"x\"}");
        j.setStatus(JobStatus.COMPLETED);
        j.setRetryCount(0);
        j.setResult("{\"output\":\"ok\"}");
        return jobRepository.save(j);
    }

    private void seedJobsTestModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-jobs-model', 'fake-jobs-model', 'fake', 'fake-jobs-model',
                        true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private HttpHeaders auth() {
        seedJobsTestModel();
        return registerLoginWithOrg("jobs-poll-" + UUID.randomUUID().toString().substring(0, 6),
                TEST_ORG);
    }

    // case 1 — getJob on an existing terminal row returns 200 with the JobStatusResponse
    // record shape (id, jobType, status, priority, result, errorMessage, retryCount,
    // createdAt, startedAt, completedAt). Pins the frontend-facing contract.
    @Test
    void getJob_returns200WithJobStatusResponseShape() {
        HttpHeaders headers = auth();
        BackgroundJob seeded = seedTerminalJob();

        ResponseEntity<JobStatusResponse> response = rest.exchange(
                url("/api/jobs/" + seeded.getId()),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JobStatusResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JobStatusResponse body = response.getBody();
        assertNotNull(body, "body must deserialize as JobStatusResponse");
        assertEquals(seeded.getId(), body.id());
        assertEquals("test.poll", body.jobType());
        assertEquals(JobStatus.COMPLETED, body.status());
        assertEquals("{\"output\":\"ok\"}", body.result());
        assertEquals(0, body.retryCount());
        assertNull(body.errorMessage());
        assertNotNull(body.createdAt(), "createdAt is populated by @CreationTimestamp on save");
    }

    // case 2 — getJob on a missing id returns 404 (Optional → notFound().build()).
    @Test
    void getJob_returns404OnUnknownId() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/jobs/" + UUID.randomUUID()),
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
