package ai.operativus.agentmanager.integration.admin;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Black-box runtime pin for the {@code /api/admin/composio/connection}
 *   per-org CRUD surface introduced in Composio Path B PR-C. Validates: tenant scoping (the
 *   service stamps {@code orgId} server-side; cross-org reads return 404), idempotent upsert
 *   (PUT creates first, updates next), optimistic-lock 409, and the {@code system_audits}
 *   side effect tagged with {@code COMPOSIO_CONNECTION_*}.
 *
 * <p>Authz role-matrix coverage (401 unauth / 403 ROLE_USER / pass on ROLE_ADMIN) lives in
 *   {@link ai.operativus.agentmanager.integration.security.AdminEndpointAuthzRuntimeTest}.
 *   This class operates exclusively as ROLE_ADMIN to focus on the business contract.
 *
 * State: Stateless ({@link BaseIntegrationTest#truncateDatabase()} resets per-test).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ComposioConnectionAdminRuntimeTest extends BaseIntegrationTest {

    private static final String BASE = "/api/admin/composio/connection";
    private static final String ORG_A = "org-a-conn";
    private static final String ORG_B = "org-b-conn";

    private HttpHeaders adminA;

    @BeforeEach
    void setUp() {
        truncateDatabase();
        adminA = registerLoginWithOrg("conn-admin-a", ORG_A);
    }

    @Test
    void get_noRowYet_returns404() {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.GET, new HttpEntity<>(adminA), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void put_firstCallCreates_secondCallUpdates_writesAuditRows() {
        // First PUT — version null → create path
        Map<String, Object> createBody = Map.of("connectionId", "conn-A-001");
        ResponseEntity<Map> create = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(createBody, adminA), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(create.getBody().get("orgId")).isEqualTo(ORG_A);
        assertThat(create.getBody().get("connectionId")).isEqualTo("conn-A-001");
        Integer version = (Integer) create.getBody().get("version");
        assertThat(version).isNotNull();

        // Second PUT — version present → update path
        Map<String, Object> updateBody = Map.of("connectionId", "conn-A-002", "version", version);
        ResponseEntity<Map> update = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(updateBody, adminA), Map.class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(update.getBody().get("connectionId")).isEqualTo("conn-A-002");
        assertThat((Integer) update.getBody().get("version"))
                .as("saveAndFlush surfaces the bumped @Version on update")
                .isGreaterThan(version);

        // Audit rows: one create, one update
        Integer createCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND org_id = ?",
                Integer.class, "COMPOSIO_CONNECTION_CREATE", ORG_A);
        Integer updateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND org_id = ?",
                Integer.class, "COMPOSIO_CONNECTION_UPDATE", ORG_A);
        assertThat(createCount).isEqualTo(1);
        assertThat(updateCount).isEqualTo(1);
    }

    @Test
    void put_blankConnectionId_returns400() {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", ""), adminA), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void put_staleVersionOnUpdate_returns409() {
        // Seed a row.
        rest.exchange(url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-init"), adminA), Map.class);

        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-second",
                        "version", 999), adminA),
                Map.class);
        assertThat(response.getStatusCode())
                .as("stale-version surfaces as 409 via StaleDataException")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void put_missingVersionOnUpdate_returns409() {
        // First PUT seeds a row at version=0.
        rest.exchange(url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-seed"), adminA), Map.class);

        // Second PUT without a version field on an existing row must NOT silently overwrite —
        // service treats null version on existing row as stale.
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-overwrite"), adminA), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void delete_returns204_thenGetReturns404_writesAudit() {
        rest.exchange(url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-bye"), adminA), Map.class);

        ResponseEntity<Void> deleteResp = rest.exchange(
                url(BASE), HttpMethod.DELETE, new HttpEntity<>(adminA), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getResp = rest.exchange(
                url(BASE), HttpMethod.GET, new HttpEntity<>(adminA), Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND org_id = ?",
                Integer.class, "COMPOSIO_CONNECTION_DELETE", ORG_A);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void delete_noRow_returns404() {
        ResponseEntity<Void> response = rest.exchange(
                url(BASE), HttpMethod.DELETE, new HttpEntity<>(adminA), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void crossOrgIsolation_orgBCannotSeeOrgARow() {
        // org A creates a row.
        rest.exchange(url(BASE), HttpMethod.PUT,
                new HttpEntity<>(Map.of("connectionId", "conn-A-secret"), adminA), Map.class);

        // org B authenticates as a separate ADMIN user in a different org.
        HttpHeaders adminB = registerLoginWithOrg("conn-admin-b", ORG_B);

        // org B sees no row.
        ResponseEntity<Map> getB = rest.exchange(
                url(BASE), HttpMethod.GET, new HttpEntity<>(adminB), Map.class);
        assertThat(getB.getStatusCode())
                .as("Service scopes to caller orgId — org B's GET does NOT leak org A's row")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // org B's DELETE also can't touch org A's row.
        ResponseEntity<Void> delB = rest.exchange(
                url(BASE), HttpMethod.DELETE, new HttpEntity<>(adminB), Void.class);
        assertThat(delB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // org A's row is intact.
        ResponseEntity<Map> getA = rest.exchange(
                url(BASE), HttpMethod.GET, new HttpEntity<>(adminA), Map.class);
        assertThat(getA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getA.getBody().get("connectionId")).isEqualTo("conn-A-secret");
    }

    @Test
    void requestDtoHasNoOrgId_serverStampsCallerOrg() {
        // Even if a malicious client sneaks an "orgId" field into the JSON, jackson
        // should ignore it (record DTO has no orgId field) and the service stamps
        // the caller's resolved org. This pins the tenant-bypass mitigation.
        Map<String, Object> spoofed = Map.of(
                "connectionId", "conn-server-stamped",
                "orgId", "some-other-org");
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.PUT,
                new HttpEntity<>(spoofed, adminA), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("orgId"))
                .as("Service must stamp caller's resolved orgId, ignoring client-supplied value")
                .isEqualTo(ORG_A);

        Integer countInOrgA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM composio_connection_config WHERE org_id = ?",
                Integer.class, ORG_A);
        assertThat(countInOrgA).isEqualTo(1);
        Integer countInOtherOrg = jdbc.queryForObject(
                "SELECT COUNT(*) FROM composio_connection_config WHERE org_id = ?",
                Integer.class, "some-other-org");
        assertThat(countInOtherOrg).isEqualTo(0);
    }

    @Test
    void unauthenticated_returns401_onAllVerbs() {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            Object body = method == HttpMethod.PUT ? Map.of("connectionId", "x") : null;
            ResponseEntity<String> response = rest.exchange(
                    url(BASE), method, new HttpEntity<>(body), String.class);
            assertThat(response.getStatusCode())
                    .as("unauth " + method + " " + BASE)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
