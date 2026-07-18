package ai.operativus.agentmanager.integration.security;

import ai.operativus.agentmanager.compute.security.PiiAuditLogDTO;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the tenant-isolation contract of
 * {@code GET /api/v1/pii-policies/audit-log} (added with changeset 112). The pii_audit_log table
 * gained an {@code org_id} column so the log can be exposed without cross-tenant leakage; this test
 * proves the endpoint returns ONLY the caller's org rows and that a foreign {@code agentId} filter
 * cannot exfiltrate another tenant's entries (IDOR).
 * State: Stateless. Per-test isolation: pii_audit_log is cleared in {@link #reset()}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class PiiAuditLogRuntimeTest extends BaseIntegrationTest {

    private static final String ORG_A = "org-pii-audit-a";
    private static final String ORG_B = "org-pii-audit-b";
    private static final String PATH = "/api/v1/pii-policies/audit-log";

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM pii_audit_log");
        // Org A: two entries on two different agents. Org B: one entry.
        // session_id left NULL — it has a FK to agent_sessions and is irrelevant to org/agent scoping.
        seed("agent-a1", "SSN", ORG_A);
        seed("agent-a2", "EMAIL", ORG_A);
        seed("agent-b1", "SSN", ORG_B);
    }

    private void seed(String agentId, String policyName, String orgId) {
        jdbc.update("""
                INSERT INTO pii_audit_log (id, agent_id, policy_name, scrub_strategy,
                                           occurrences, session_id, created_at, org_id)
                VALUES (?, ?, ?, 'REDACT', 1, NULL, now(), ?)
                """, UUID.randomUUID(), agentId, policyName, orgId);
    }

    @Test
    void auditLog_returnsOnlyCallerOrgEntries() {
        HttpHeaders authA = registerLoginWithOrg("pii-audit-admin-a", ORG_A);
        HttpHeaders authB = registerLoginWithOrg("pii-audit-admin-b", ORG_B);

        ResponseEntity<PiiAuditLogDTO[]> a = authorizedGet(PATH, authA, PiiAuditLogDTO[].class);
        ResponseEntity<PiiAuditLogDTO[]> b = authorizedGet(PATH, authB, PiiAuditLogDTO[].class);

        assertAll("tenant scoping",
                () -> assertEquals(HttpStatus.OK, a.getStatusCode()),
                () -> assertEquals(2, a.getBody().length, "org A must see exactly its 2 entries"),
                () -> assertTrue(Arrays.stream(a.getBody()).allMatch(e ->
                                e.agentId().equals("agent-a1") || e.agentId().equals("agent-a2")),
                        "org A must NOT see org B's entry"),
                () -> assertEquals(1, b.getBody().length, "org B must see exactly its 1 entry"),
                () -> assertEquals("agent-b1", b.getBody()[0].agentId()));
    }

    @Test
    void auditLog_foreignAgentIdFilter_returnsEmpty_noIdorLeak() {
        HttpHeaders authA = registerLoginWithOrg("pii-audit-idor-a", ORG_A);

        // Org A asks for org B's agent explicitly — the org_id filter must win, yielding zero rows.
        ResponseEntity<PiiAuditLogDTO[]> res =
                authorizedGet(PATH + "?agentId=agent-b1", authA, PiiAuditLogDTO[].class);

        assertAll("IDOR blocked",
                () -> assertEquals(HttpStatus.OK, res.getStatusCode()),
                () -> assertEquals(0, res.getBody().length,
                        "a foreign agentId must not leak another tenant's PII audit entries"));
    }

    @Test
    void auditLog_ownAgentIdFilter_narrowsToThatAgent() {
        HttpHeaders authA = registerLoginWithOrg("pii-audit-own-a", ORG_A);

        ResponseEntity<PiiAuditLogDTO[]> res =
                authorizedGet(PATH + "?agentId=agent-a1", authA, PiiAuditLogDTO[].class);

        assertAll("own-agent filter",
                () -> assertEquals(HttpStatus.OK, res.getStatusCode()),
                () -> assertEquals(1, res.getBody().length),
                () -> assertEquals("agent-a1", res.getBody()[0].agentId()));
    }
}
