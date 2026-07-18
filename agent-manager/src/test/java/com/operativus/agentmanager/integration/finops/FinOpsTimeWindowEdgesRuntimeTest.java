package com.operativus.agentmanager.integration.finops;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the silent {@code Math.max(1, Math.min(days, 90))} clamp
 *   on the FinOps trend-window endpoints. All 4 endpoints use the same clamp:
 *   <ul>
 *     <li>{@code GET /api/v1/finops/trends?days=N}</li>
 *     <li>{@code GET /api/v1/finops/allocations?days=N}</li>
 *     <li>{@code GET /api/v1/finops/allocations/by-model?days=N}</li>
 *     <li>{@code GET /api/v1/finops/cache-impact?days=N}</li>
 *   </ul>
 *
 *   <p><b>Pinned semantics</b>: the clamp is SILENT — there is no 400 for out-of-range
 *   inputs. {@code days=-5} silently becomes 1, {@code days=999} silently becomes 90.
 *   FE may rely on this graceful degradation.
 *
 *   <p>This test exercises the endpoints with edge values; success (200) suffices to
 *   verify the clamp didn't crash or reject. Response-body content depends on data
 *   that's not seeded here.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class FinOpsTimeWindowEdgesRuntimeTest extends BaseIntegrationTest {

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("finops-window-edges-admin",
                "finops-window-edges-admin@test.local", "pass-fwea-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private static final List<String> CLAMPED_ENDPOINTS = List.of(
            "/api/v1/finops/trends",
            "/api/v1/finops/allocations",
            "/api/v1/finops/allocations/by-model",
            "/api/v1/finops/cache-impact");

    @Test
    void daysZeroIsSilentlyClampedToOneAcrossAllEndpoints() {
        for (String endpoint : CLAMPED_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(endpoint + "?days=0"),
                    HttpMethod.GET,
                    new HttpEntity<>(adminAuth),
                    String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    endpoint + " with days=0 must succeed via Math.max(1,...) clamp; got "
                            + response.getStatusCode());
        }
    }

    @Test
    void negativeDaysIsSilentlyClampedToOneAcrossAllEndpoints() {
        for (String endpoint : CLAMPED_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(endpoint + "?days=-5"),
                    HttpMethod.GET,
                    new HttpEntity<>(adminAuth),
                    String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    endpoint + " with days=-5 must succeed via Math.max(1,...); got "
                            + response.getStatusCode());
        }
    }

    @Test
    void oversizedDaysIsSilentlyClampedTo90AcrossAllEndpoints() {
        for (String endpoint : CLAMPED_ENDPOINTS) {
            ResponseEntity<String> response = rest.exchange(
                    url(endpoint + "?days=999"),
                    HttpMethod.GET,
                    new HttpEntity<>(adminAuth),
                    String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    endpoint + " with days=999 must succeed via Math.min(...,90); got "
                            + response.getStatusCode());
        }
    }

    @Test
    void boundaryDaysOneAndNinetyAreAcceptedAcrossAllEndpoints() {
        for (String endpoint : CLAMPED_ENDPOINTS) {
            for (int days : new int[]{1, 90}) {
                ResponseEntity<String> response = rest.exchange(
                        url(endpoint + "?days=" + days),
                        HttpMethod.GET,
                        new HttpEntity<>(adminAuth),
                        String.class);
                assertEquals(HttpStatus.OK, response.getStatusCode(),
                        endpoint + " with days=" + days + " must succeed; got "
                                + response.getStatusCode());
            }
        }
    }
}
