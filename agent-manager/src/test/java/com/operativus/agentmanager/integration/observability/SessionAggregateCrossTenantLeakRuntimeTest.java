package com.operativus.agentmanager.integration.observability;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
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
 * Domain Responsibility: Pins tenant scoping on {@code /api/v1/observability/aggregates/sessions}.
 *   The aggregate's underlying SQL uses {@code (:orgId IS NULL OR s.org_id IS NULL OR s.org_id = :orgId)}
 *   — the {@code :orgId IS NULL} branch is the super-admin bypass and {@code s.org_id IS NULL}
 *   matches legacy pre-tenant rows. A regression that drops the {@code AgentContextHolder.getOrgId()}
 *   binding (or that bypasses the third predicate) would surface another tenant's
 *   session counts in the caller's rollup.
 *
 *   <p>Pin: seed sessions per org with a known cardinality ratio (1 in org-A, 3 in org-B),
 *   call as an org-A user, and assert the aggregate sessionCount sums to 1 — NOT 4.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SessionAggregateCrossTenantLeakRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void sessionAggregates_orgACallerSeesOnlyOwnSessionCount() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-sess-A-" + tag;
        String orgB = "org-sess-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("sess-userA-" + tag, orgA);

        // Seed agents + sessions per org. The aggregate query joins agent_runs ↔
        // agent_sessions; we don't strictly need runs to assert the count, but the
        // session rows themselves drive findSessionAnalytics's COUNT(DISTINCT session_id).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now()),
                       (?, 'agent B', 'gpt-4o-mini', true, ?, now(), now())
                """,
                "agent-sess-A-" + tag, orgA,
                "agent-sess-B-" + tag, orgB);

        // 1 session in org-A.
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'userA', ?, ?, now() - interval '1 hour', now() - interval '1 hour')
                """, "sess-A-" + tag, orgA, "agent-sess-A-" + tag);

        // 3 sessions in org-B — the leak candidates.
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                    VALUES (?, 'userB', ?, ?, now() - interval '1 hour', now() - interval '1 hour')
                    """, "sess-B-" + tag + "-" + i, orgB, "agent-sess-B-" + tag);
        }

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/observability/aggregates/sessions?window=30"),
                HttpMethod.GET, new HttpEntity<>(userA), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "tenant caller must read their own session aggregates");

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) body.get("buckets");
        assertNotNull(buckets, "response must carry buckets list");

        long totalSessionCountForOrgA = buckets.stream()
                .mapToLong(b -> ((Number) b.get("sessionCount")).longValue())
                .sum();
        assertEquals(1L, totalSessionCountForOrgA,
                "org-A caller must see exactly ONE session in the aggregate sum. A 4 here "
                        + "means org-B's 3 sessions leaked through findSessionAnalytics's "
                        + "orgId filter — cross-tenant rollup leak (same family as the "
                        + "FinOps aggregate IDOR).");
    }
}
