package com.operativus.agentmanager.integration.security;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin that the new {@code DELETE /api/models/{id}/rate-limit}
 * endpoint shipped in PR #239 produces a {@code system_audits} row with the correct
 * structured columns. The wire shape was claimed property-by-construction in PR #239;
 * this test makes it a property-by-test.
 *
 * <p><b>Issue #7 outcome (from {@code docs/plans/agm-clear-out.md}):</b> the interceptor's
 * {@code resolveResourceType} extracts the first path segment after {@code /api/}
 * (here: {@code models}), so the trailing {@code /rate-limit} does NOT pollute the
 * resolved {@code resource_type}. The {@code resource_id} comes from the
 * {@code @PathVariable("id")} URI template variable, also unaffected by the suffix
 * segment. <b>No interceptor remediation was needed</b> — the discovery surfaced the
 * existing implementation already handles sub-paths correctly.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RateLimitClearAuditLogRuntimeTest extends BaseIntegrationTest {

    private static final String MODEL_ID = "audit-fixture-model";
    private HttpHeaders auth;

    @BeforeEach
    void setUp() {
        truncateDatabase();
        seedModel(MODEL_ID, /*rpm=*/ 5);
        auth = authenticateAs(
                "rl-audit-admin", "rl-audit-admin@test.local", "pass-rla-1234",
                List.of("ROLE_ADMIN"));
    }

    @Test
    void deleteRateLimit_writesSystemAuditsRowWithCorrectStructuredColumns() {
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/" + MODEL_ID + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, auth),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Assertions on STRUCTURED COLUMNS (the contract), NOT on free-form payload.
        // Issue #3 / Anti-pattern A3 trade-off: structured columns are the contract;
        // the JSON request body is internal.
        Map<String, Object> row = jdbc.queryForObject(
                "SELECT resource_type, resource_id, action, username, http_method, request_path "
                        + "FROM system_audits "
                        + "WHERE resource_id = ? AND action = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                auditLogRowMapper(),
                MODEL_ID, "DELETE");

        assertThat(row).isNotNull();
        assertThat(row.get("resource_type"))
                .as("Issue #7: trailing /rate-limit segment must NOT pollute resource_type")
                .isEqualTo("MODEL");
        assertThat(row.get("resource_id"))
                .as("@PathVariable(\"id\") flows through to resource_id; suffix segment ignored")
                .isEqualTo(MODEL_ID);
        assertThat(row.get("action")).isEqualTo("DELETE");
        assertThat(row.get("username")).isEqualTo("rl-audit-admin");
        assertThat(row.get("http_method")).isEqualTo("DELETE");
        assertThat(row.get("request_path"))
                .as("full path is preserved for forensics, even though it isn't used for resource_type")
                .isEqualTo("/api/models/" + MODEL_ID + "/rate-limit");
    }

    @Test
    void deleteRateLimitTwiceIdempotent_writesTwoSystemAuditsRows() {
        // Idempotent at the service level (PR #239's clearRateLimit), but each HTTP
        // invocation IS still an admin mutation; both should be auditable.
        rest.exchange(url("/api/models/" + MODEL_ID + "/rate-limit"), HttpMethod.DELETE,
                new HttpEntity<>(null, auth), Void.class);
        rest.exchange(url("/api/models/" + MODEL_ID + "/rate-limit"), HttpMethod.DELETE,
                new HttpEntity<>(null, auth), Void.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE resource_id = ? AND action = 'DELETE' "
                        + "AND request_path = ?",
                Integer.class,
                MODEL_ID, "/api/models/" + MODEL_ID + "/rate-limit");
        assertThat(count)
                .as("each DELETE call audits — idempotency at the service layer doesn't suppress audit")
                .isEqualTo(2);
    }

    private void seedModel(String id, Integer rpm) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at, rate_limit_rpm)
                VALUES (?, ?, 'fake', 'fake-gpt', true, false, true, 'CHAT', now(), ?)
                ON CONFLICT (id) DO UPDATE SET rate_limit_rpm = EXCLUDED.rate_limit_rpm
                """,
                id, id, rpm);
    }

    private static RowMapper<Map<String, Object>> auditLogRowMapper() {
        return (rs, rowNum) -> Map.of(
                "resource_type", rs.getString("resource_type"),
                "resource_id", rs.getString("resource_id"),
                "action", rs.getString("action"),
                "username", rs.getString("username"),
                "http_method", rs.getString("http_method"),
                "request_path", rs.getString("request_path"));
    }
}
