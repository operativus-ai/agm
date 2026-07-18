package ai.operativus.agentmanager.integration.compliance;

import ai.operativus.agentmanager.control.repository.ErasureRequestRepository;
import ai.operativus.agentmanager.core.entity.ErasureRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the HTTP-level idempotency contract on
 *   {@code POST /api/compliance/erasure-requests}. The current implementation creates a
 *   NEW {@link ErasureRequest} row + enqueues a NEW erasure job on every submit, even for
 *   the same {@code userId} within seconds. This test makes that behavior explicit so a
 *   future addition of idempotency (e.g. 409 on duplicate-within-window, or silent
 *   collapse) is a deliberate test update accompanied by FE/operator coordination.
 *
 *   <p>Why this matters: GDPR Article 17 erasure is high-impact and slow (touches many
 *   tables). A network-retry loop on the FE could fire the request 3-5x before the first
 *   completes. Without idempotency, the system runs N concurrent erasure jobs against the
 *   same user; per-handler row counts in the audit summary become misleading, and the
 *   bookkeeping audit trail balloons with N "PENDING → IN_PROGRESS → COMPLETED" rows.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Two sequential submits</b> for the same {@code userId} both succeed with
 *         distinct {@code jobId}s — non-idempotent.</li>
 *     <li><b>Two persisted {@link ErasureRequest} rows</b> result, both queryable via
 *         {@code GET /erasure-requests?userId=X}.</li>
 *     <li><b>Audit trail accumulates</b> — the list endpoint returns N rows after N submits
 *         (audit trail intent, since this is the only durable record of who requested
 *         erasure and when).</li>
 *   </ul>
 *
 *   <p>Sibling to {@link ComplianceAdminAuthzRuntimeTest} (which pins authz on these
 *   endpoints) and {@link ai.operativus.agentmanager.integration.crosscutting.RetentionErasureRuntimeTest}
 *   (which exercises the service-layer erasure handler chain). This test covers the HTTP
 *   surface idempotency dimension neither sibling covers.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ErasureRequestIdempotencyRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, String>> JOB_ID_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> REQUEST_LIST =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private ErasureRequestRepository erasureRequestRepository;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    /**
     * Seeds the erasure target as a real user in DEFAULT_SYSTEM_ORG (the admin's org). Bug #50 added
     * {@code ComplianceController.requireSameOrgOrSelf}, which now 404s on a non-existent / foreign-org
     * target — so the synthetic target usernames these tests submit must actually exist same-org.
     * Reuses {@code authenticateAs} (register + stamp DEFAULT_SYSTEM_ORG); the returned headers are unused.
     */
    private void seedTargetUserInDefaultOrg(String username) {
        authenticateAs(username, username + "@erase.local", "pass-erase-1234", List.of("ROLE_USER"));
    }

    @Test
    void twoSequentialSubmitsForSameUserIdCreateTwoDistinctErasureRequests() {
        HttpHeaders adminAuth = authenticateAs("erasure-idempotency-admin",
                "erasure-idempotency-admin@test.local", "pass-eai-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        String targetUserId = "target-user-for-erasure";
        seedTargetUserInDefaultOrg(targetUserId);

        ResponseEntity<Map<String, String>> first = rest.exchange(
                url("/api/compliance/erasure-requests?userId=" + targetUserId),
                HttpMethod.POST,
                new HttpEntity<>(null, adminAuth),
                JOB_ID_RESPONSE);

        ResponseEntity<Map<String, String>> second = rest.exchange(
                url("/api/compliance/erasure-requests?userId=" + targetUserId),
                HttpMethod.POST,
                new HttpEntity<>(null, adminAuth),
                JOB_ID_RESPONSE);

        // Pin: both submits succeed with 202 (current non-idempotent behavior).
        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode(),
                "first erasure submit must succeed with 202; got " + first.getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, second.getStatusCode(),
                "second erasure submit (duplicate userId) currently also succeeds with "
                        + "202 — this is the non-idempotent behavior being pinned. If "
                        + "idempotency is added (return 409 or 200 with existing jobId), update "
                        + "this assertion and add the new contract test.");

        // Pin: distinct jobIds — confirms two separate jobs were enqueued.
        String firstJobId = first.getBody().get("jobId");
        String secondJobId = second.getBody().get("jobId");
        assertNotNull(firstJobId, "first response missing jobId");
        assertNotNull(secondJobId, "second response missing jobId");
        assertNotEquals(firstJobId, secondJobId,
                "current behavior creates distinct jobs per submit; if these collapsed to "
                        + "one, idempotency has been added — update this test.");
    }

    @Test
    void synchronousDeleteEraseTwiceCreatesTwoErasureRequestRows() {
        // Uses the deprecated-but-still-mounted DELETE /erase/{userId} which calls
        // ErasureOrchestrationService.submitAndProcess synchronously. Same idempotency
        // dimension as POST /erasure-requests, but observable in the same HTTP request
        // because the row write is synchronous (the async job-queue poller is disabled
        // in tests via 86_400_000ms scheduler intervals — see application-test.properties).
        HttpHeaders adminAuth = authenticateAs("erasure-sync-admin",
                "erasure-sync-admin@test.local", "pass-esa-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        String targetUserId = "target-user-sync-erasure";
        seedTargetUserInDefaultOrg(targetUserId);

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/compliance/erase/" + targetUserId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/compliance/erase/" + targetUserId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertEquals(HttpStatus.OK, first.getStatusCode(),
                "first synchronous erasure must succeed with 200");
        assertEquals(HttpStatus.OK, second.getStatusCode(),
                "second synchronous erasure (duplicate userId) currently also succeeds — "
                        + "this is the non-idempotent behavior being pinned.");

        // Two ErasureRequest rows must exist — one per call. If idempotency collapses these,
        // update this test AND renegotiate audit-trail semantics (the audit trail of WHO
        // requested erasure must still be preserved per submitter).
        List<ErasureRequest> persisted = erasureRequestRepository
                .findByUserIdOrderByRequestedAtDesc(targetUserId);
        assertEquals(2, persisted.size(),
                "expected exactly 2 ErasureRequest rows after 2 synchronous DELETE calls; got "
                        + persisted.size()
                        + ". If idempotency collapses to 1 row, update this test.");
    }

    @Test
    void listErasureRequestsReturnsAllSynchronousSubmitsForUserId() {
        HttpHeaders adminAuth = authenticateAs("erasure-list-admin",
                "erasure-list-admin@test.local", "pass-elr-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        String targetUserId = "target-user-list";
        seedTargetUserInDefaultOrg(targetUserId);

        // Use the synchronous DELETE path so rows are written before the GET; see comment
        // on synchronousDeleteEraseTwiceCreatesTwoErasureRequestRows for the async caveat.
        rest.exchange(
                url("/api/compliance/erase/" + targetUserId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        rest.exchange(
                url("/api/compliance/erase/" + targetUserId),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        ResponseEntity<List<Map<String, Object>>> list = rest.exchange(
                url("/api/compliance/erasure-requests?userId=" + targetUserId),
                HttpMethod.GET,
                new HttpEntity<>(null, adminAuth),
                REQUEST_LIST);

        assertEquals(HttpStatus.OK, list.getStatusCode(),
                "list endpoint must return 200; got " + list.getStatusCode());
        assertNotNull(list.getBody(), "list endpoint must return a non-null body");
        assertEquals(2, list.getBody().size(),
                "GET /erasure-requests must return one entry per submit (audit trail); got "
                        + list.getBody().size());
        list.getBody().forEach(row -> assertTrue(
                targetUserId.equals(row.get("userId")),
                "row should reference target userId; got " + row.get("userId")));
    }
}
