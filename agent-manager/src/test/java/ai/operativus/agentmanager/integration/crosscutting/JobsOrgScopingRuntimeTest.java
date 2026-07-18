package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Tenant-isolation black-box tests for {@code GET /api/jobs/{jobId}}.
 *   Validates that {@code JobsController}'s org-scoping (EXISTS subquery through
 *   {@code agents.org_id}) is enforced end-to-end against a live Postgres container.
 * State: Stateless. {@link BaseIntegrationTest#truncateDatabase()} resets between tests.
 *
 * Cases mapped to docs/specs/agm-fix-d.md §284.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class JobsOrgScopingRuntimeTest extends BaseIntegrationTest {

    // §284 case 1 — unauthenticated requests are rejected (401).
    @Test
    void getJob_Unauthenticated_Returns401() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/jobs/no-such-job"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // §284 case 2 — authenticated user retrieves own-org job (200).
    @Test
    void getJob_OwnOrgJob_Returns200() {
        String orgId = "org-" + shortUuid();
        String agentId = "agent-" + shortUuid();
        String jobId = UUID.randomUUID().toString();
        HttpHeaders auth = registerLoginWithOrg("user-owner-" + shortUuid(), orgId);

        seedAgentWithOrg(agentId, orgId);
        seedJobWithAgent(jobId, agentId);

        ResponseEntity<String> resp = authorizedGet("/api/jobs/" + jobId, auth, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains(jobId);
    }

    // §284 case 3 — authenticated user cannot retrieve another org's job (404, not 403).
    @Test
    void getJob_CrossOrgJob_Returns404() {
        String ownerOrg = "org-owner-" + shortUuid();
        String callerOrg = "org-caller-" + shortUuid();
        String agentId = "agent-" + shortUuid();
        String jobId = UUID.randomUUID().toString();
        HttpHeaders callerAuth = registerLoginWithOrg("user-caller-" + shortUuid(), callerOrg);

        seedAgentWithOrg(agentId, ownerOrg);
        seedJobWithAgent(jobId, agentId);

        ResponseEntity<String> resp = authorizedGet("/api/jobs/" + jobId, callerAuth, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static final String FAKE_MODEL_ID = "fake-jobs-scope-model";

    private String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, FAKE_MODEL_ID, FAKE_MODEL_ID, FAKE_MODEL_ID);
    }

    private void seedAgentWithOrg(String agentId, String orgId) {
        seedModel();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, ?, true, ?, now(), now())
                ON CONFLICT (id) DO NOTHING
                """, agentId, "Jobs Test Agent " + agentId, FAKE_MODEL_ID, orgId);
    }

    private void seedJobWithAgent(String jobId, String agentId) {
        jdbc.update("""
                INSERT INTO background_jobs
                    (id, job_type, agent_id, payload, status, priority,
                     retry_count, max_retries, created_at)
                VALUES (?, 'TEST_JOB', ?, '{}', 'QUEUED', 'NORMAL',
                        0, 3, now())
                """, jobId, agentId);
    }
}
