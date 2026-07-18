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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the empty-result wire shape on all four observability
 *   aggregate endpoints. The FE Analytics tabs render arrays directly
 *   ({@code data.distribution.map(...)}, {@code data.tools.map(...)}, etc.); a regression
 *   that returns {@code null} instead of {@code []} for an empty window would surface
 *   as a runtime NPE in the React rendering layer.
 *
 *   <p>Tests with a fresh-tenant caller (no seeded data) and asserts each endpoint's
 *   array fields exist and are empty — not null, not missing.
 *
 *   <p>Endpoints pinned:
 *   <ul>
 *     <li>{@code /orchestration} → {@code distribution[]} + {@code overTime[]}</li>
 *     <li>{@code /orchestration-decisions} → {@code content[]} (Page envelope)</li>
 *     <li>{@code /sessions} → {@code buckets[]}</li>
 *     <li>{@code /tools} → {@code tools[]} + {@code overTime[]}</li>
 *     <li>{@code /safety} → {@code cells[]} + {@code flaggedRunsTopN[]}</li>
 *   </ul>
 *
 *   <p>Same family as the dashboard {@code DashboardEmptyDataShapesRuntimeTest} from
 *   the recent session's dashboard plan.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ObservabilityAggregateEmptyShapeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void orchestrationAggregates_emptyWindow_returnsArraysNotNulls() {
        HttpHeaders auth = freshTenant("orch-empty");

        Map<String, Object> body = getJson(auth, "/api/v1/observability/aggregates/orchestration?window=1&granularity=DAY");
        assertArrayPresent(body, "distribution");
        assertArrayPresent(body, "overTime");
    }

    @Test
    void orchestrationDecisions_emptyWindow_returnsEmptyPageContent() {
        HttpHeaders auth = freshTenant("orch-decisions-empty");

        Map<String, Object> body = getJson(auth,
                "/api/v1/observability/aggregates/orchestration-decisions?strategy=ROUTER&page=0&size=20");
        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) body.get("content");
        assertNotNull(content,
                "Page envelope must always include a content array — null collapses to JS .map() NPE on the FE");
        assertTrue(content.isEmpty(),
                "no seeded decisions → content must be empty (not unrelated rows from another tenant)");
    }

    @Test
    void sessionsAggregates_emptyWindow_returnsArraysNotNulls() {
        HttpHeaders auth = freshTenant("sess-empty");

        Map<String, Object> body = getJson(auth, "/api/v1/observability/aggregates/sessions?window=1");
        assertArrayPresent(body, "buckets");
    }

    @Test
    void toolsAggregates_emptyWindow_returnsArraysNotNulls() {
        HttpHeaders auth = freshTenant("tools-empty");

        Map<String, Object> body = getJson(auth, "/api/v1/observability/aggregates/tools?window=1&granularity=DAY");
        assertArrayPresent(body, "tools");
        assertArrayPresent(body, "overTime");
    }

    @Test
    void safetyAggregates_emptyWindow_returnsArraysNotNulls() {
        HttpHeaders auth = freshTenant("safety-empty");

        Map<String, Object> body = getJson(auth, "/api/v1/observability/aggregates/safety?window=1");
        assertArrayPresent(body, "cells");
        assertArrayPresent(body, "flaggedRunsTopN");
    }

    // ─── helpers ───

    private HttpHeaders freshTenant(String label) {
        // A unique orgId per test so the empty-window assertion can't accidentally see
        // rows from another concurrent test (test ordering / Testcontainers reuse).
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return registerLoginWithOrg(label + "-" + tag, "org-empty-" + label + "-" + tag);
    }

    private Map<String, Object> getJson(HttpHeaders auth, String path) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "empty-window aggregate must return 200, not 404/500 — path: " + path);
        Map<String, Object> body = resp.getBody();
        assertNotNull(body, "empty-window aggregate must return a body, not 204/null — path: " + path);
        return body;
    }

    private static void assertArrayPresent(Map<String, Object> body, String field) {
        Object value = body.get(field);
        assertNotNull(value,
                "field '" + field + "' must be present and non-null on the empty-result response — "
                        + "JS .map() on null crashes the FE Analytics tab");
        assertTrue(value instanceof List,
                "field '" + field + "' must serialize as a JSON array, not an object/string. Got: " + value);
        assertTrue(((List<?>) value).isEmpty(),
                "field '" + field + "' must be an EMPTY array for a fresh tenant with no seeded data — "
                        + "non-empty here means cross-tenant leak. Got: " + value);
    }
}
