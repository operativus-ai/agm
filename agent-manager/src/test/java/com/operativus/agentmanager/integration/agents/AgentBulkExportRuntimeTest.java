package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins {@code POST /api/admin/agents/bulk-export} —
 *   the async job-enqueue path for bulk agent export. Controller enqueues a
 *   {@code BULK_EXPORT} job in {@code background_jobs} and returns 202 with the
 *   jobId; actual export work happens in the {@code BulkExportJobHandler}.
 *
 *   <p>This canary covers the controller's enqueue contract only. Job-execution
 *   coverage is a separate concern; the canary tests the surface clients
 *   interact with.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentBulkExportRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void bulkExportWithIds_returns202_andEnqueuesBackgroundJob() {
        String orgId = "org-bulkexp-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulkexp", orgId);
        String a1 = seedAgent(orgId);
        String a2 = seedAgent(orgId);

        long jobsBefore = countBulkExportJobs();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/bulk-export"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of(a1, a2)), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "bulk-export must return 202 ACCEPTED (async enqueue contract); got "
                        + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().get("jobId"),
                "response must carry the enqueued jobId");
        assertEquals(jobsBefore + 1, countBulkExportJobs(),
                "exactly one BULK_EXPORT job row must be enqueued");
    }

    @Test
    void bulkExportWithEmptyIds_returns202_andStillEnqueuesJob() {
        // Controller does not pre-validate; an empty job is a downstream concern.
        // Pin this so a controller-level "reject empty" change later is intentional.
        String orgId = "org-bulkexp-empty-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("bulkexp-empty", orgId);

        long jobsBefore = countBulkExportJobs();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/bulk-export"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.of()), auth),
                JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "empty ids list still enqueues — no controller-side guard today");
        assertEquals(jobsBefore + 1, countBulkExportJobs(),
                "empty-ids request still enqueues a BULK_EXPORT job row (current contract)");
    }

    private String seedAgent(String orgId) {
        String agentId = "agent-bulkexp-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Bulk export test agent", orgId);
        return agentId;
    }

    private long countBulkExportJobs() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE job_type = 'BULK_EXPORT'",
                Long.class);
    }
}
