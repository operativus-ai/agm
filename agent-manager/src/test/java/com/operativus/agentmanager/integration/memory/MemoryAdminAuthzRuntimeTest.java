package com.operativus.agentmanager.integration.memory;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Focused authz pin for the three admin-gated endpoints on
 *   {@link com.operativus.agentmanager.control.controller.MemoryController}:
 *   <ul>
 *     <li>{@code POST /api/memories} — addMemory</li>
 *     <li>{@code DELETE /api/memories} — deleteMemories</li>
 *     <li>{@code POST /api/memories/optimize} — optimizeMemories</li>
 *   </ul>
 *
 *   <p>Closes 3 TODO entries on {@code AdminEndpointCoverageArchTest.ADMIN_ENDPOINT_COVERAGE}
 *   — bumps those tags from "TODO: needs focused authz test" to
 *   "focused: MemoryAdminAuthzRuntimeTest".
 *
 *   <p>For each endpoint the matrix asserts:
 *   <ol>
 *     <li>Anonymous request → 401 (rejected at JWT filter)</li>
 *     <li>ROLE_USER request → 403 (rejected at {@code @PreAuthorize("hasRole('ADMIN')")} gate)</li>
 *     <li>ROLE_ADMIN request → 2xx (proves the gate doesn't block admins, plus the
 *         handler executes with a syntactically valid body / param)</li>
 *   </ol>
 *
 *   <p>The non-admin GET endpoints on this controller ({@code searchMemories},
 *   {@code getMemoryStats}, {@code getMemoryTopics}) are NOT in scope here — they are
 *   open to ROLE_USER by design and apply user-scoped filtering at the service layer.
 *   That contract is documented in the controller Javadoc and exercised elsewhere.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class MemoryAdminAuthzRuntimeTest extends BaseIntegrationTest {

    // ─── POST /api/memories (addMemory) ─────────────────────────────────────

    @Test
    void addMemory_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "anon-probe"), HttpHeaders.EMPTY),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "anon POST must hit JWT filter; got " + resp.getStatusCode());
    }

    @Test
    void addMemory_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ma1-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "user-probe"), userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER POST must hit the @PreAuthorize(\"hasRole('ADMIN')\") gate; "
                        + "got " + resp.getStatusCode());
    }

    @Test
    void addMemory_roleAdmin_returns200_andHandlerExecutes() {
        HttpHeaders adminAuth = adminHeaders("ma1-admin");
        ResponseEntity<Map<String, String>> resp = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "admin-add-" + UUID.randomUUID()), adminAuth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "ROLE_ADMIN POST must pass the gate; got " + resp.getStatusCode());
        assertNotNull(resp.getBody().get("message"),
                "successful add must return a 'message' field");
        assertEquals("Memory saved", resp.getBody().get("message"));
    }

    // ─── DELETE /api/memories (deleteMemories) ──────────────────────────────

    @Test
    void deleteMemories_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of("anon-id-1"), HttpHeaders.EMPTY),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void deleteMemories_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ma2-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of("user-id-1"), userAuth),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER DELETE must hit the @PreAuthorize gate; got " + resp.getStatusCode());
    }

    @Test
    void deleteMemories_roleAdmin_returns204() {
        HttpHeaders adminAuth = adminHeaders("ma2-admin");
        // ID must be UUID-shaped — pgvector store rejects non-UUID document ids with a
        // 400 from the upstream JDBC binding (the column is uuid). We're testing the
        // authz gate, not the service's id-format validation; use a syntactically-valid
        // UUID so the gate-passes case actually reaches a 204 no-op.
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of(UUID.randomUUID().toString()), adminAuth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "ROLE_ADMIN DELETE must pass the gate; got " + resp.getStatusCode());
    }

    // ─── POST /api/memories/optimize (optimizeMemories) ─────────────────────

    @Test
    void optimizeMemories_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories/optimize"), HttpMethod.POST,
                new HttpEntity<>(HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void optimizeMemories_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ma3-user");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/memories/optimize"), HttpMethod.POST,
                new HttpEntity<>(userAuth), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER POST must hit the @PreAuthorize gate; got " + resp.getStatusCode());
    }

    @Test
    void optimizeMemories_roleAdmin_returns202_withJobId() {
        HttpHeaders adminAuth = adminHeaders("ma3-admin");
        ResponseEntity<Map<String, String>> resp = rest.exchange(
                url("/api/memories/optimize"), HttpMethod.POST,
                new HttpEntity<>(adminAuth),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "ROLE_ADMIN POST must enqueue an async optimization job; got "
                        + resp.getStatusCode());
        assertNotNull(resp.getBody().get("jobId"),
                "response must echo the enqueued background_job id so the FE can poll status");
        assertTrue(!resp.getBody().get("jobId").isBlank(),
                "jobId must be non-blank");
    }

    // ─── Cross-tenant isolation (DELETE) ─────────────────────────────────────

    /**
     * Pins the service-layer cross-tenant guard added with this PR. Pre-fix
     * {@code MemoryService.deleteMemories(List<String> ids)} called
     * {@code vectorStore.delete(ids)} with whatever ids the caller passed in — any
     * foreign-tenant admin who learned another org's vector_id (e.g. via an audit log
     * or a leaked support ticket) could delete that org's memories. The class-level
     * {@code @PreAuthorize("hasRole('ADMIN')")} gate stops a regular user but does
     * not stop a foreign-tenant admin; admin-role ≠ admin-of-target-tenant. Same shape
     * as PR #972's cancelRun cross-tenant guard.
     */
    @Test
    void deleteMemories_crossTenantVectorId_doesNotDeleteOtherOrgRow() {
        String orgA = "org-mem-a-" + UUID.randomUUID().toString().substring(0, 8);
        String orgB = "org-mem-b-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders adminA = registerLoginWithOrg("mem-cross-a-admin", orgA);
        HttpHeaders adminB = registerLoginWithOrg("mem-cross-b-admin", orgB);

        // Org A admin writes a memory; the row is stamped with org_id=A by
        // MemoryService.addMemory.
        String aContent = "org-A memory secret " + UUID.randomUUID();
        ResponseEntity<Map<String, String>> aAdd = rest.exchange(
                url("/api/memories"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", aContent), adminA),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, aAdd.getStatusCode(),
                "Org A admin must succeed in adding a memory to its own tenant");

        // Look up the vector_id for org A's row directly — the controller doesn't
        // return it, so JDBC against agentic_memories is the only path.
        String vectorId = jdbc.queryForObject(
                "SELECT vector_id FROM agentic_memories WHERE org_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, orgA);
        assertNotNull(vectorId,
                "agentic_memories row must exist with org_id=A after addMemory");

        // Org B admin probes DELETE with org A's vector_id. With the cross-tenant guard
        // in place, the service silently no-ops on rows that don't belong to org B.
        ResponseEntity<Void> bDelete = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of(vectorId), adminB),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, bDelete.getStatusCode(),
                "DELETE always returns 204 — service-layer no-ops without leaking existence");

        // Org A's row MUST still exist. Pre-fix this row was deleted by org B's call.
        Long aRowsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentic_memories WHERE vector_id = ? AND org_id = ?",
                Long.class, vectorId, orgA);
        assertEquals(1L, aRowsAfter,
                "Org A's memory row must survive a foreign-tenant admin's DELETE attempt "
                        + "by vector_id. Pre-fix the row was deleted (cross-tenant exploit).");

        // Org A admin can still delete its own row — the guard scopes deletion, not the
        // entire delete surface.
        ResponseEntity<Void> aDelete = rest.exchange(
                url("/api/memories"), HttpMethod.DELETE,
                new HttpEntity<>(List.of(vectorId), adminA),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, aDelete.getStatusCode());
        Long aRowsFinal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agentic_memories WHERE vector_id = ?", Long.class, vectorId);
        assertEquals(0L, aRowsFinal,
                "Org A admin must still be able to delete its own row — the guard only "
                        + "blocks cross-tenant access, not legitimate self-tenant deletes");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-mem-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-mem-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
