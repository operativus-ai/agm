package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins the observed behavior of {@code DELETE /api/agents/{agentId}/runs/{runId}}
 *   when (a) the runId belongs to a different tenant, and (b) the runId does not exist
 *   anywhere. Sibling pin to {@link RunsControllerCrossTenantIdorRuntimeTest} (which covers
 *   GET tenant scoping on {@code /api/v1/runs}) — the {@code /api/agents/...} surface is a
 *   different controller (AgentsController.cancelRun, line 231) and the same IDOR family
 *   has to be verified independently.
 *
 *   <p><strong>Pre-pin reality observed in production code:</strong>
 *   <ol>
 *     <li>{@code AgentsController.cancelRun} (line 231) calls
 *         {@code agentOperations.cancelRun(runId)} blind — no orgId scoping, no caller
 *         identity check, no path-param vs body verification of {@code agentId} vs the
 *         run's actual {@code agent_id}.</li>
 *     <li>{@code RunExecutionManager.cancel(runId)} (line 277) looks up the run by id
 *         only ({@code runRepository.findById(runId)}). If the run belongs to another
 *         tenant, the finalizer fallback still fires and transitions that tenant's row
 *         to {@code CANCELLED}. <strong>This is a cross-tenant IDOR.</strong></li>
 *     <li>If the runId does not exist, the {@code runRepository.findById(runId).ifPresent}
 *         block is a no-op and the controller returns 204 unconditionally — so a caller
 *         cannot distinguish "I cancelled a real run" from "I made up a runId" via the
 *         response status.</li>
 *   </ol>
 *
 *   <p>This test pins the current behavior AS-IS so a fix can flip the assertions without
 *   silently changing the contract. The fix should:
 *   <ul>
 *     <li>Add a {@code requireSameOrg} guard in {@code AgentService.cancelRun} (or the
 *         registry-side {@code AgentOperations.cancelRun}) — returns 404 when the run
 *         belongs to another tenant, matching the §79 RBAC pattern.</li>
 *     <li>Optionally: return 404 for non-existent runIds so DELETE retries can
 *         distinguish a successful cancel from a typo. Pre-fix the controller swallows
 *         both into 204 — a defensible idempotency choice — so this is a UX call rather
 *         than a security fix.</li>
 *   </ul>
 *
 *   <p>Three pins:
 *   <ol>
 *     <li><strong>Cross-tenant DELETE returns 204 AND clobbers the other tenant's row</strong>
 *         — the IDOR. A follow-up fix should flip this to 404 and assert the row
 *         remains untouched.</li>
 *     <li><strong>Non-existent runId returns 204</strong> — DELETE-idempotency choice. The
 *         finalizer fallback is a no-op without a row.</li>
 *     <li><strong>Same-tenant DELETE returns 204 AND transitions the row to CANCELLED</strong>
 *         — control case. Confirms the IDOR-flag pin isn't accidentally always passing.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RunsCancelCrossTenantAndUnknownRuntimeTest extends BaseIntegrationTest {

    // Post-fix: DELETE /runs/{id} for another tenant's runId returns 404 and does NOT
    // mutate the row. AgentService.cancelRun adds a requireSameOrg guard that throws
    // ResourceNotFoundException when the run's org_id doesn't match the JWT-bound caller
    // org. §79 RBAC pattern — 404 not 403 to avoid leaking tenant-membership.
    @Test
    void cancelRunForOtherOrgsRunId_returns404_andRunRemainsRunning() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-cancel-A-" + tag;
        String orgB = "org-cancel-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("cancel-userA-" + tag, orgA);

        seedModel();

        String agentB = "cancel-agent-B-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'cross-tenant agent', 'gpt-4o-mini', true, ?, now(), now())
                """, agentB, orgB);

        String runB = "cancel-run-B-" + tag;
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, user_id, status, input, output,
                                        created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'RUNNING', 'b-input', NULL, now(), now())
                """, runB, agentB, orgB);

        installPermissiveErrorHandler();

        // org-A caller invokes DELETE on org-B's runId — the IDOR.
        ResponseEntity<Void> response = rest.exchange(
                url("/api/agents/" + agentB + "/runs/" + runB),
                HttpMethod.DELETE, new HttpEntity<>(userA), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "post-fix: cross-tenant DELETE must return 404 (§79 RBAC pattern: 404 not "
                        + "403 to avoid leaking tenant-membership). 204 here means the "
                        + "requireSameOrg guard in AgentService.cancelRun regressed.");

        // Post-fix: the org-B row must remain RUNNING. The guard throws before reaching
        // the finalizer, so no status mutation can occur from a cross-tenant DELETE.
        Map<String, Object> rowAfter = jdbc.queryForMap(
                "SELECT status, org_id FROM agent_runs WHERE id = ?", runB);
        assertEquals("RUNNING", rowAfter.get("status"),
                "post-fix: cross-tenant DELETE must NOT mutate the org-B row. CANCELLED "
                        + "here means the guard let the cancel write through — IDOR regressed.");
        assertEquals(orgB, rowAfter.get("org_id"),
                "row's org_id must NOT be mutated by a cross-tenant cancel attempt — sanity check");
    }

    // Pin: non-existent runId → 204. The finalizer fallback's findById.ifPresent is a no-op.
    // This is a defensible idempotency choice (DELETE retries converge) but means clients
    // cannot distinguish a successful cancel from a typo via the status code.
    @Test
    void cancelNonExistentRunId_returns204_currentBehaviorPin() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-cancel-A-" + tag;
        HttpHeaders userA = registerLoginWithOrg("cancel-unknown-" + tag, orgA);

        seedModel();
        String agentA = "cancel-agent-A-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now())
                """, agentA, orgA);

        installPermissiveErrorHandler();
        String madeUpRunId = "made-up-run-" + UUID.randomUUID();
        ResponseEntity<Void> response = rest.exchange(
                url("/api/agents/" + agentA + "/runs/" + madeUpRunId),
                HttpMethod.DELETE, new HttpEntity<>(userA), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE on a non-existent runId returns 204 — the controller calls "
                        + "agentOperations.cancelRun blind, the finalizer's findById.ifPresent "
                        + "is a no-op for unknown ids. A switch to 404 would be a UX improvement "
                        + "(client distinguishes successful cancel from typo) but is not security-critical.");

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE id = ?", Integer.class, madeUpRunId);
        assertEquals(0, rowCount,
                "non-existent runId must not somehow materialize a row from the cancel path");
    }

    // Control case: same-tenant DELETE on own RUNNING run transitions it to CANCELLED.
    // Confirms the IDOR-pin above isn't accidentally always passing — if cancel were a
    // total no-op, both tests would pass but the contract would be broken.
    @Test
    void cancelOwnRunningRun_returns204_andTransitionsRowToCancelled_controlCase() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-cancel-self-" + tag;
        HttpHeaders userA = registerLoginWithOrg("cancel-self-" + tag, orgA);

        seedModel();
        String agentA = "cancel-self-agent-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now())
                """, agentA, orgA);

        String runA = "cancel-self-run-" + tag;
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, user_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'RUNNING', 'a-input', NULL, now(), now())
                """, runA, agentA, orgA);

        installPermissiveErrorHandler();
        ResponseEntity<Void> response = rest.exchange(
                url("/api/agents/" + agentA + "/runs/" + runA),
                HttpMethod.DELETE, new HttpEntity<>(userA), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "same-tenant cancel must return 204");

        String status = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runA);
        assertNotNull(status);
        assertEquals("CANCELLED", status,
                "same-tenant cancel must transition own RUNNING run to CANCELLED — "
                        + "control case; if this fails the cancel path itself regressed");
    }

    // ─── helpers ───

    private void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void installPermissiveErrorHandler() {
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
    }
}
