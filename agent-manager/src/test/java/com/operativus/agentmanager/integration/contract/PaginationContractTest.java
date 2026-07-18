package com.operativus.agentmanager.integration.contract;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the wire-format contract for Spring Data {@code Page<T>}
 *   serialization across the REST surface. Under Spring Boot 4 / Spring Data 4 the
 *   default shape is {@code {content, page: {size, number, totalElements, totalPages}}}
 *   — pagination metadata nested under {@code page}. The FE interface
 *   {@code PaginatedResponse<T>} (agent-manager-ui/src/shared/types/api.ts) consumes
 *   this nested shape. If Spring flips back to the legacy flat shape, every FE
 *   consumer breaks silently.
 * State: Stateless. Single happy-path call against {@code /api/v1/approvals/pending}
 *   (an empty Page is sufficient — pagination metadata serializes regardless of content).
 *
 * Contract pinned:
 *   - Top-level keys: content, page
 *   - Nested under page: size, number, totalElements, totalPages
 *   - No flat totalElements / totalPages at the top level
 *
 * If this test fails, either:
 *   1) Spring Boot reverted to the legacy flat shape — migrate the FE
 *      {@code PaginatedResponse<T>} back to flat fields in the same PR; OR
 *   2) Someone forced {@code spring.data.web.pageable.serialization-mode=direct}
 *      and Spring 4 happened to honor it — coordinate with the FE migration.
 */
public class PaginationContractTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void approvalsPending_serializesNestedPageShape() {
        HttpHeaders auth = authenticateAs(
                "pagination-contract-user",
                "pagination-contract@test.local",
                "pass-pagination-1234",
                List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/approvals/pending?page=0&size=20"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/v1/approvals/pending should return 200 for an authenticated user");

        Map<String, Object> body = response.getBody();
        Object pageObj = body.get("page");
        assertAll(
                () -> assertTrue(body.containsKey("content"),
                        "Page body must carry 'content' at top level"),
                () -> assertTrue(pageObj instanceof Map<?, ?>,
                        "Page body MUST carry a nested 'page' object (Spring Data 4 default)"),
                () -> assertFalse(body.containsKey("totalElements"),
                        "Top-level 'totalElements' is the legacy flat shape; FE reads page.totalElements"),
                () -> assertFalse(body.containsKey("totalPages"),
                        "Top-level 'totalPages' is the legacy flat shape; FE reads page.totalPages"));

        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) pageObj;
        assertAll(
                () -> assertTrue(page.containsKey("totalElements"),
                        "Nested page object must carry 'totalElements'"),
                () -> assertTrue(page.containsKey("totalPages"),
                        "Nested page object must carry 'totalPages'"),
                () -> assertTrue(page.containsKey("size"),
                        "Nested page object must carry 'size'"),
                () -> assertTrue(page.containsKey("number"),
                        "Nested page object must carry 'number'"));
    }
}
