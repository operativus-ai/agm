package ai.operativus.agentmanager.integration.extensions;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins optimistic-lock conflict detection on
 *   {@link ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity}.
 *   Two concurrent {@code PUT /api/v1/extensions/{id}} requests, both supplying the
 *   same {@code version} field they read at v0, must produce exactly one 200 (winner)
 *   and one 409 (stale) — last-write-wins is closed.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. No WireMock, no FakeChatModel — this is a pure
 *   controller-surface conflict test.
 *
 * <p>Production change pinned by this test:</p>
 * <ul>
 *   <li>{@code ExtensionRegistrationEntity} gained {@code @Version Long version} +
 *       a Liquibase changeset (065) adding the column with default 0.</li>
 *   <li>{@code ExtensionController} gained {@code PUT /{id}} that requires the
 *       client-known {@code version}; mismatch throws
 *       {@code ObjectOptimisticLockingFailureException} → 409 via
 *       {@code GlobalExceptionHandler}.</li>
 *   <li>{@code ExtensionRegistrationDTO} gained a nullable {@code version} field; a
 *       legacy 6-arg constructor preserves source compatibility for callers that
 *       construct DTOs without the field (e.g. NATIVE_SPI merge in {@code getExtensions}).</li>
 * </ul>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionOptimisticLockRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void concurrentPuts_sameVersion_produceOneOkOne409_dbReflectsTheWinner() throws Exception {
        HttpHeaders auth = authenticatedHeaders("ext-optlock-runner");

        String id = "ext-optlock-" + UUID.randomUUID();
        Long v0 = registerAndReturnVersion(auth, id, "http://example.com/initial");
        assertThat(v0)
                .as("new extension must start with version 0")
                .isEqualTo(0L);

        String urlA = "http://example.com/from-operator-A";
        String urlB = "http://example.com/from-operator-B";

        // Two PUTs in parallel, both based on v0. CountDownLatch keeps them aligned at
        // request start so the race window is forced — otherwise scheduler luck could
        // serialize them and the test would only exercise the post-conflict path.
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ResponseEntity<Map<String, Object>>> futA = CompletableFuture.supplyAsync(
                    () -> { awaitGate(gate); return putWithVersion(auth, id, urlA, v0); }, vts);
            CompletableFuture<ResponseEntity<Map<String, Object>>> futB = CompletableFuture.supplyAsync(
                    () -> { awaitGate(gate); return putWithVersion(auth, id, urlB, v0); }, vts);

            gate.countDown();
            CompletableFuture.allOf(futA, futB).get(15, TimeUnit.SECONDS);

            HttpStatusCode statusA = futA.get().getStatusCode();
            HttpStatusCode statusB = futB.get().getStatusCode();

            boolean exactlyOneWinner =
                    (statusA == HttpStatus.OK && statusB == HttpStatus.CONFLICT)
                            || (statusA == HttpStatus.CONFLICT && statusB == HttpStatus.OK);

            assertThat(exactlyOneWinner)
                    .as("exactly one 200 + one 409. Observed statusA=%s, statusB=%s — "
                            + "anything else (e.g. both 200) means last-write-wins is still active",
                            statusA, statusB)
                    .isTrue();
        }

        // DB final state: version=1 + url is whichever operator's value won the 200.
        Map<String, Object> finalRow = jdbc.queryForMap(
                "SELECT version, url FROM extensions WHERE id = ?", id);
        assertThat(((Number) finalRow.get("version")).longValue())
                .as("after exactly one successful UPDATE, version MUST have incremented to 1")
                .isEqualTo(1L);
        assertThat(finalRow.get("url"))
                .as("final url MUST be one of the two operator-submitted values — proving the winning PUT actually committed")
                .isIn(urlA, urlB);

        // A third PUT with the stale v0 must STILL fail — defense-in-depth.
        ResponseEntity<Map<String, Object>> stale = putWithVersion(auth, id,
                "http://example.com/stale-retry", v0);
        assertThat(stale.getStatusCode())
                .as("a follow-up PUT with the original v0 version after the row has moved to v1 must continue to be rejected")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private Long registerAndReturnVersion(HttpHeaders auth, String id, String url) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E8 lock-test webhook");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E8 fixture");
        regBody.put("active", true);
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth), JSON_MAP);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object versionRaw = created.getBody().get("version");
        assertThat(versionRaw)
                .as("POST response MUST echo version — toDto includes it after E8")
                .isNotNull();
        return ((Number) versionRaw).longValue();
    }

    private ResponseEntity<Map<String, Object>> putWithVersion(
            HttpHeaders auth, String id, String url, Long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id);
        body.put("name", "E8 lock-test webhook updated");
        body.put("type", "WEBHOOK");
        body.put("url", url);
        body.put("description", "E8 fixture (updated)");
        body.put("active", true);
        body.put("version", version);
        // Spring-Boot RestTemplate doesn't throw on 4xx by default in TestRestTemplate — the
        // response carries the status, which is what the assertion wants.
        return rest.exchange(url("/api/v1/extensions/" + id), HttpMethod.PUT,
                new HttpEntity<>(body, auth), JSON_MAP);
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test gate interrupted", e);
        }
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-e8-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
