package ai.operativus.agentmanager.integration.dashboard;

import ai.operativus.agentmanager.control.config.PaginationDefaultsConfig;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the {@code PaginationDefaultsConfig.MAX_PAGE_SIZE=200}
 *   clamp across the dashboard's paginated read endpoints — defense against accidental
 *   full-table dumps that pin a request thread for seconds.
 *
 *   <p>Pinned endpoints:
 *   <ul>
 *     <li>{@code GET /api/v1/observability/background-jobs?size=10000}</li>
 *     <li>{@code GET /api/alerts/events?size=10000}</li>
 *   </ul>
 *
 *   <p>Both use Spring Data {@code Pageable}, resolved by the global
 *   {@code PageableHandlerMethodArgumentResolverCustomizer} that calls
 *   {@code resolver.setMaxPageSize(MAX_PAGE_SIZE)}. The clamp is invisible to the handler
 *   — {@code pageable.getPageSize()} arrives as 200 even when the request asked for 10000.
 *
 *   <p>Why pin: a future refactor that swaps the global resolver for an endpoint-local
 *   builder bypassing {@code PaginationDefaultsConfig} would silently re-enable unbounded
 *   reads. This test catches that.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class DashboardPaginationClampRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("pagination-clamp-admin",
                "pagination-clamp-admin@test.local", "pass-pca-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void backgroundJobsListSizeOverMaxIsClampedTo200() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/observability/background-jobs?page=0&size=10000"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /background-jobs must succeed; got " + response.getStatusCode());
        int pageSize = extractPageSize(response);
        assertTrue(pageSize <= PaginationDefaultsConfig.MAX_PAGE_SIZE,
                "size=10000 must be clamped to MAX_PAGE_SIZE ("
                        + PaginationDefaultsConfig.MAX_PAGE_SIZE
                        + "); got " + pageSize);
    }

    @Test
    void alertingEventsListSizeOverMaxIsClampedTo200() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/alerts/events?page=0&size=10000"),
                HttpMethod.GET,
                new HttpEntity<>(adminAuth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/alerts/events must succeed; got " + response.getStatusCode());
        int pageSize = extractPageSize(response);
        assertTrue(pageSize <= PaginationDefaultsConfig.MAX_PAGE_SIZE,
                "size=10000 must be clamped to MAX_PAGE_SIZE ("
                        + PaginationDefaultsConfig.MAX_PAGE_SIZE
                        + "); got " + pageSize);
    }

    /**
     * Spring Boot 4 nests page metadata under {@code body.page}:
     * {@code {content: [...], page: {size, number, totalElements, totalPages}}}.
     */
    @SuppressWarnings("unchecked")
    private int extractPageSize(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        assertNotNull(pageMeta, "Page response missing nested `page` object");
        return ((Number) pageMeta.get("size")).intValue();
    }
}
