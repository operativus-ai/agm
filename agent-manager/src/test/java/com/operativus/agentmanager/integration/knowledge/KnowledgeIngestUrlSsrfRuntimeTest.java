package com.operativus.agentmanager.integration.knowledge;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Locks the SSRF guard on the {@code POST /api/knowledge/ingest-url}
 *   endpoint. {@link com.operativus.agentmanager.control.service.KnowledgeService#ingestUrlAsync}
 *   spawns a virtual thread that does {@code Jsoup.connect(url).get()} with no built-in URL
 *   validation. Without this guard, an authenticated user could trigger AGM to fetch
 *   {@code http://169.254.169.254/latest/meta-data/...} (cloud metadata) or any RFC-1918
 *   address reachable from the AGM host, and the response would be persisted as KB content.
 *
 *   The guard mirrors {@code A2AController.validateBaseUrl}: parses the host as an IP literal
 *   without DNS and rejects loopback / RFC-1918 / link-local / any-local / non-http(s) /
 *   decimal-encoded-IPv4 / IPv4-mapped-IPv6-loopback. Each rejected URL must:
 *   <ul>
 *     <li>Return HTTP 400 (via {@code GlobalExceptionHandler.handleBusinessValidationException}).</li>
 *     <li>Leave {@code knowledge_contents} unchanged — the guard runs BEFORE
 *         {@code knowledgeRepo.save(contentEntity)} and BEFORE the VT spawn.</li>
 *   </ul>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Uses the {@code Fake*ModelConfig} import set established
 *   by sibling knowledge runtime tests.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
// Pin the SSRF guard to strict mode for THIS test class. application-test.properties sets
// agent.knowledge.ingest.allow-loopback-urls=true so the WireMock-based happy-path runtime
// tests can ingest http://localhost:<dynamic-port>. The strict policy below is what runs
// in production and is what this class verifies.
@TestPropertySource(properties = "agent.knowledge.ingest.allow-loopback-urls=false")
public class KnowledgeIngestUrlSsrfRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        // The endpoint dedups by URI globally — clear the table so each parameterized case
        // starts from a clean slate and the rejection isn't caused by a prior row.
        jdbc.update("DELETE FROM knowledge_contents");
    }

    /**
     * URLs the guard MUST reject. Each is a real-world SSRF vector that an authenticated
     * user could otherwise weaponize against the AGM host's network position.
     */
    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            // Loopback hostnames + IPv4 + IPv6
            "http://localhost",
            "http://localhost:8080/admin",
            "http://127.0.0.1",
            "https://127.0.0.1:8443/etc/passwd",
            "http://[::1]",
            // Rest of the 127.0.0.0/8 loopback range — string-equals on "127.0.0.1" misses these
            "http://127.0.0.2",
            "http://127.255.255.255",
            // IPv4-mapped IPv6 loopback — Jsoup follows this, InetAddress unwraps it
            "http://[::ffff:127.0.0.1]",
            // Decimal-encoded IPv4 — curl + browsers + Jsoup accept; InetAddress.getByName does not
            "http://2130706433",
            // Wildcard / any-address bind
            "http://0.0.0.0",
            // Cloud-metadata endpoints — the highest-blast-radius SSRF target
            "http://169.254.169.254",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            // RFC-1918 private ranges
            "http://10.0.0.1",
            "http://10.255.255.255/admin",
            "http://192.168.1.1",
            "http://192.168.255.255/admin",
            // RFC-1918 172.16/12 — boundary cases inside the range
            "http://172.16.0.1",
            "http://172.31.255.255",
            // Non-http(s) schemes — the guard explicitly rejects these so file:/// or javascript:
            // cannot be smuggled past the Jsoup call
            "file:///etc/passwd",
            "javascript:alert(1)",
            "gopher://localhost",
            "ftp://internal.acme",
    })
    void ingestUrl_rejectsSsrfTargetUrlsWith400(String maliciousUrl) {
        HttpHeaders auth = authenticatedHeaders("kb-ssrf-reject");

        Map<String, Object> kbBody = Map.of("name", "SSRF probe KB " + UUID.randomUUID(),
                "description", "SSRF guard runtime test");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(kbBody, auth), JSON_MAP);
        String kbId = (String) kbCreated.getBody().get("id");

        URI ingestUri = UriComponentsBuilder.fromUriString(url("/api/knowledge/ingest-url"))
                .queryParam("url", maliciousUrl)
                .queryParam("knowledgeBaseId", kbId)
                .build()
                .toUri();

        // String.class (not Map) so the response decodes regardless of content type — IPv6
        // bracketed URLs (e.g. http://[::1]) are rejected by Tomcat at the servlet layer with
        // a text/html error page BEFORE reaching the controller. That's still a 400 and still
        // pre-Jsoup-fetch, so it satisfies the SSRF guard contract; we just can't deserialize
        // those bodies as ProblemDetail JSON.
        ResponseEntity<String> resp = rest.exchange(
                ingestUri, HttpMethod.POST, new HttpEntity<>(auth), String.class);

        assertEquals(400, resp.getStatusCode().value(),
                "SSRF guard must reject ingest-url=" + maliciousUrl + " with HTTP 400 "
                        + "(BusinessValidationException → GlobalExceptionHandler, or Tomcat "
                        + "servlet-layer rejection for malformed bracketed-IPv6 URLs — either "
                        + "form blocks before reaching Jsoup)");

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_contents WHERE uri = ?",
                Integer.class, maliciousUrl);
        assertEquals(0, persistedRows,
                "rejected URL must NOT persist a knowledge_contents row — the guard runs "
                        + "before knowledgeRepo.save AND before the Jsoup-on-VT spawn");
    }

    /**
     * Public URLs the guard MUST allow through to the Jsoup fetch path. These exercise the
     * boundary just outside RFC-1918 (172.15.x and 172.32.x flank the 172.16/12 range) plus
     * an FQDN. They MUST NOT return 400 from the SSRF guard. They will likely return 400
     * downstream (Jsoup cannot resolve / cannot reach), but that's a different rejection
     * cause; what we're pinning here is that the SSRF guard itself does not over-reject.
     *
     * <p>Accept criterion is "not 400 from the guard message". The downstream Jsoup failure
     * path persists the document in {@code FAILED} status; we don't assert that here because
     * the happy-fetch path is already pinned by
     * {@code KnowledgeBaseRuntimeTest.ingestByUrl_persistsChunksAndReflectsStatus}.</p>
     */
    @ParameterizedTest(name = "accepts boundary {0}")
    @CsvSource({
            "https://172.15.255.255, just-below-rfc1918-172",
            "https://172.32.0.1, just-above-rfc1918-172",
            "https://peer.example.com, public-fqdn",
    })
    void ingestUrl_acceptsPublicBoundaryUrls(String publicUrl, String aliasSuffix) {
        HttpHeaders auth = authenticatedHeaders("kb-ssrf-accept-" + aliasSuffix);

        Map<String, Object> kbBody = Map.of("name", "SSRF accept KB " + UUID.randomUUID(),
                "description", "SSRF guard runtime test — accept side");
        ResponseEntity<Map<String, Object>> kbCreated = rest.exchange(
                url("/api/v1/knowledge-bases"), HttpMethod.POST,
                new HttpEntity<>(kbBody, auth), JSON_MAP);
        String kbId = (String) kbCreated.getBody().get("id");

        URI ingestUri = UriComponentsBuilder.fromUriString(url("/api/knowledge/ingest-url"))
                .queryParam("url", publicUrl)
                .queryParam("knowledgeBaseId", kbId)
                .build()
                .toUri();

        // String.class so we don't choke on the response body when the downstream Jsoup fetch
        // path returns a non-JSON shape on its async-failure write — we only care about the
        // synchronous controller return here.
        ResponseEntity<String> resp = rest.exchange(
                ingestUri, HttpMethod.POST, new HttpEntity<>(auth), String.class);

        int status = resp.getStatusCode().value();
        // 202 = accepted, queued for Jsoup fetch. Any non-400 means the guard did not
        // over-reject. A 400 here would indicate the guard's RFC-1918 boundary regressed —
        // e.g. someone widened the range to /8 on the 172 octet.
        assertEquals(true, status != 400,
                "public URL " + publicUrl + " (just outside RFC-1918) must NOT be rejected by "
                        + "the SSRF guard — got HTTP " + status);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kb-ssrf-1234",
                List.of("ROLE_USER"));
    }
}
