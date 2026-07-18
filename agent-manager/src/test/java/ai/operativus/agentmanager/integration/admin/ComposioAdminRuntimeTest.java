package ai.operativus.agentmanager.integration.admin;

import ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Black-box runtime pin for the {@code /api/admin/composio/actions}
 *   CRUD surface introduced in Composio Path B PR-B. Validates the full request/response
 *   contract end-to-end through the real Spring Security filter chain, JPA layer, and event
 *   publisher: happy-path CRUD, validation 400s, optimistic-lock 409, not-found 404, the
 *   {@code system_audits} side effect, and the {@link ComposioConfigChangedEvent} hot-reload
 *   signal.
 *
 * <p>Authz role-matrix coverage (401 unauth / 403 wrong-role / SUPER_ADMIN passes) lives in
 *   {@link ai.operativus.agentmanager.integration.security.AdminEndpointAuthzRuntimeTest}.
 *   This class operates exclusively as SUPER_ADMIN to focus on the business contract.
 *
 * State: Stateless ({@link BaseIntegrationTest#truncateDatabase()} resets per-test).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        ComposioAdminRuntimeTest.RecordingEventListener.class})
public class ComposioAdminRuntimeTest extends BaseIntegrationTest {

    private static final String BASE = "/api/admin/composio/actions";

    @Autowired private RecordingEventListener events;

    private HttpHeaders superAdmin;

    @BeforeEach
    void setUp() {
        truncateDatabase();
        events.clear();
        superAdmin = authenticateAs("composio-admin", "composio-admin@test.local",
                "pass-cmp-1234", List.of("ROLE_SUPER_ADMIN"));
    }

    @Test
    void create_returns201_persistsRow_publishesEvent_writesAuditRow() {
        Map<String, Object> body = Map.of(
                "actionName", "gmail_send_email",
                "tier", 2,
                "enabled", true);

        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(body, superAdmin), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> created = response.getBody();
        assertThat(created.get("id")).isEqualTo("gmail_send_email");
        assertThat(created.get("actionName")).isEqualTo("GMAIL_SEND_EMAIL");
        assertThat(created.get("llmToolName")).isEqualTo("composio_gmail_send_email");
        assertThat(created.get("tier")).isEqualTo(2);
        assertThat(created.get("enabled")).isEqualTo(true);
        assertThat(created.get("createdBy")).isEqualTo("composio-admin");

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM composio_action_config WHERE id = ?",
                Integer.class, "gmail_send_email");
        assertThat(rowCount).isEqualTo(1);

        assertThat(events.reasons())
                .as("create must publish ComposioConfigChangedEvent for hot-reload")
                .contains("action_create");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND resource_id = ?",
                Integer.class, "COMPOSIO_ACTION_CREATE", "gmail_send_email");
        assertThat(auditCount)
                .as("service-layer audit row written on create")
                .isEqualTo(1);
    }

    @Test
    void create_uppercasesActionNameAndDerivesId() {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(Map.of("actionName", "  Slack_Post_Message  ",
                        "tier", 1, "enabled", true), superAdmin),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("id")).isEqualTo("slack_post_message");
        assertThat(response.getBody().get("actionName")).isEqualTo("SLACK_POST_MESSAGE");
        assertThat(response.getBody().get("llmToolName")).isEqualTo("composio_slack_post_message");
    }

    @Test
    void create_duplicateActionName_returns400() {
        Map<String, Object> body = Map.of("actionName", "DUPE_ACTION",
                "tier", 2, "enabled", true);
        ResponseEntity<Map> first = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(body, superAdmin), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(body, superAdmin), Map.class);
        assertThat(second.getStatusCode())
                .as("duplicate action_name surfaces as BusinessValidationException → 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_blankActionName_returns400() {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(Map.of("actionName", "", "tier", 2, "enabled", true),
                        superAdmin),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_tierOutOfRange_returns400() {
        ResponseEntity<Map> low = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(Map.of("actionName", "BAD_TIER_LOW", "tier", 0,
                        "enabled", true), superAdmin),
                Map.class);
        assertThat(low.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> high = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(Map.of("actionName", "BAD_TIER_HIGH", "tier", 4,
                        "enabled", true), superAdmin),
                Map.class);
        assertThat(high.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_returnsAllRows() {
        seedAction("alpha_one", 1);
        seedAction("alpha_two", 2);

        ResponseEntity<List> response = rest.exchange(
                url(BASE), HttpMethod.GET,
                new HttpEntity<>(superAdmin), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    // Singular GET /{id} handler was removed by PR #506 (Phase 2 BE-orphan cleanup —
    // no consumer; list endpoint covers the read-many case). The previous tests
    // `getById_unknown_returns404` and `getById_returnsRow` referenced the removed
    // endpoint and have been silently failing since #506 (integration tests excluded
    // from default ./mvnw test). Deleted here; the list endpoint at `BASE` GET covers
    // read coverage.

    @Test
    void update_returns200_persistsChanges_publishesEvent_writesAudit() {
        Map<String, Object> created = createAction("UPDATE_ME", 2, true);
        Integer version = (Integer) created.get("version");

        events.clear();

        Map<String, Object> updateBody = Map.of(
                "tier", 3, "enabled", false, "version", version);
        ResponseEntity<Map> response = rest.exchange(
                url(BASE + "/update_me"), HttpMethod.PUT,
                new HttpEntity<>(updateBody, superAdmin), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("tier")).isEqualTo(3);
        assertThat(response.getBody().get("enabled")).isEqualTo(false);
        assertThat(response.getBody().get("updatedBy")).isEqualTo("composio-admin");
        // Version should have incremented from the JPA @Version bump.
        assertThat((Integer) response.getBody().get("version")).isGreaterThan(version);

        assertThat(events.reasons()).contains("action_update");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND resource_id = ?",
                Integer.class, "COMPOSIO_ACTION_UPDATE", "update_me");
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void update_unknownId_returns404() {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE + "/missing"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("tier", 2, "enabled", true, "version", 0), superAdmin),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_staleVersion_returns409() {
        Map<String, Object> created = createAction("STALE_TARGET", 2, true);
        Integer currentVersion = (Integer) created.get("version");
        // Use a version that doesn't match (current + 999) — guaranteed stale.
        int staleVersion = currentVersion + 999;

        ResponseEntity<Map> response = rest.exchange(
                url(BASE + "/stale_target"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("tier", 3, "enabled", false,
                        "version", staleVersion), superAdmin),
                Map.class);

        assertThat(response.getStatusCode())
                .as("StaleDataException is mapped to 409 by GlobalExceptionHandler")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void delete_returns204_rowIsGone_publishesEventAndAudit() {
        createAction("BYE_ACTION", 2, true);
        events.clear();

        ResponseEntity<Void> deleteResp = rest.exchange(
                url(BASE + "/bye_action"), HttpMethod.DELETE,
                new HttpEntity<>(superAdmin), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // PR #506 removed singular GET /{id}. Verify deletion via JDBC row check
        // (cheaper than re-fetching the full list, and proves the row is actually gone
        // rather than just filtered).
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM composio_action_config WHERE id = ?",
                Integer.class, "bye_action");
        assertThat(rowCount).as("row must be removed from composio_action_config").isEqualTo(0);

        assertThat(events.reasons()).contains("action_delete");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND resource_id = ?",
                Integer.class, "COMPOSIO_ACTION_DELETE", "bye_action");
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void delete_unknownId_returns404() {
        ResponseEntity<Void> response = rest.exchange(
                url(BASE + "/never_existed"), HttpMethod.DELETE,
                new HttpEntity<>(superAdmin), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAction(String actionName, int tier, boolean enabled) {
        ResponseEntity<Map> response = rest.exchange(
                url(BASE), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "actionName", actionName,
                        "tier", tier,
                        "enabled", enabled), superAdmin),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void seedAction(String id, int tier) {
        jdbc.update("""
                INSERT INTO composio_action_config
                    (id, action_name, llm_tool_name, tier, enabled, version,
                     created_at, updated_at, created_by)
                VALUES (?, ?, ?, ?, true, 0, now(), now(), 'fixture')
                """,
                id, id.toUpperCase(), "composio_" + id, tier);
    }

    /**
     * Captures every {@link ComposioConfigChangedEvent} the application emits during a test.
     * Lives as an inner class registered via {@code @Import} so it is wired into the test
     * Spring context only; production runs are unaffected.
     */
    @Component
    static class RecordingEventListener {
        private final List<String> reasons = new CopyOnWriteArrayList<>();

        @EventListener
        public void onChange(ComposioConfigChangedEvent event) {
            reasons.add(event.reason());
        }

        List<String> reasons() {
            return List.copyOf(reasons);
        }

        void clear() {
            reasons.clear();
        }
    }
}
