package com.operativus.agentmanager.integration.a2a;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Locks the SSRF guard on the {@code POST /api/v1/a2a/peers}
 *   register-peer endpoint. The FE register-peer modal advertises "Private/loopback
 *   addresses are blocked by SSRF guards" — this test pins that contract end-to-end against
 *   the real controller. The guard implementation lives in
 *   {@code A2AController#validateBaseUrl}; tests are parameterized over the boundary cases
 *   the production guard claims to reject (loopback, RFC-1918 /8, /12, /16 ranges, the
 *   AWS/GCP/Azure metadata endpoint, and non-http(s) schemes) plus a sanity-check happy path
 *   so a future "wide-open" regression cannot ship green.
 *
 *   Each rejected URL must return HTTP 400 and leave {@code a2a_remote_agents} unchanged
 *   (the guard runs BEFORE persistence). The happy path must return 200 and persist exactly
 *   one row.
 *
 * State: Stateless. Inherits the integration scaffolding from
 *   {@link BaseIntegrationTest} and reuses the {@code Fake*ModelConfig} import set already
 *   established by sibling A2A runtime tests.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aSsrfGuardRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM a2a_remote_agents");
    }

    /**
     * URLs the guard MUST reject. Each is a real-world SSRF vector that the FE
     * "Private/loopback addresses are blocked" copy implies coverage for.
     */
    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            // Loopback hostnames + IPv4 + IPv6
            "http://localhost",
            "http://localhost:8080/v1",
            "http://127.0.0.1",
            "https://127.0.0.1:8443/path",
            "http://[::1]",
            // Wildcard / any-address bind
            "http://0.0.0.0",
            // Cloud metadata services — the highest-blast-radius SSRF target
            "http://169.254.169.254",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            // RFC-1918 private ranges
            "http://10.0.0.1",
            "http://10.255.255.255/v1",
            "http://192.168.1.1",
            "http://192.168.255.255/v1",
            // RFC-1918 172.16/12 — boundary cases inside the range
            "http://172.16.0.1",
            "http://172.31.255.255",
            // Non-http(s) schemes — the guard explicitly rejects these
            "file:///etc/passwd",
            "javascript:alert(1)",
            "gopher://localhost",
            "ftp://internal.acme",
    })
    void registerPeer_rejectsSsrfTargetUrls(String maliciousUrl) {
        HttpHeaders auth = userHeaders("a2a-ssrf-reject");
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-agent-ssrf");
        body.put("baseUrl", maliciousUrl);
        body.put("alias", "peer-ssrf-" + UUID.randomUUID());
        body.put("apiKey", "sk-irrelevant");

        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(400, register.getStatusCode().value(),
                "SSRF guard must reject baseUrl=" + maliciousUrl + " with HTTP 400");

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ?",
                Integer.class, body.get("alias"));
        assertEquals(0, persistedRows,
                "rejected URL must not persist any a2a_remote_agents row — the guard runs before persistence");
    }

    /**
     * RFC-1918 boundary cases OUTSIDE the 172.16/12 private range. These addresses are
     * public and the guard must NOT block them — otherwise the FE register-peer flow
     * would over-reject and legitimate enterprise peers couldn't be wired up.
     */
    @ParameterizedTest(name = "accepts boundary {0}")
    @CsvSource({
            "https://172.15.255.255, just-below-rfc1918-172",
            "https://172.32.0.1, just-above-rfc1918-172",
            "https://peer.example.com, public-fqdn",
    })
    void registerPeer_acceptsPublicBoundaryUrls(String publicUrl, String aliasSuffix) {
        HttpHeaders auth = userHeaders("a2a-ssrf-accept-" + aliasSuffix);
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-agent-public");
        body.put("baseUrl", publicUrl);
        body.put("alias", "peer-ok-" + aliasSuffix + "-" + UUID.randomUUID());
        body.put("apiKey", "sk-irrelevant");

        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(200, register.getStatusCode().value(),
                "public URL " + publicUrl + " (just outside RFC-1918) must NOT be SSRF-blocked");
    }

    /**
     * SSRF bypass vectors that the prior string-equals guard missed. Now actively
     * asserted (was {@code @Disabled} pending hardening). {@code A2AController
     * .validateBaseUrl} resolves IP literals via {@link java.net.InetAddress} and
     * uses {@code isLoopbackAddress / isSiteLocalAddress / isLinkLocalAddress /
     * isAnyLocalAddress} predicates rather than string equality.
     *
     * <p>Notable: {@code http://2130706433} is the decimal-encoded form of 127.0.0.1.
     *   {@code InetAddress.getByName} does NOT accept it, but curl + browsers do,
     *   so the controller parses the all-digits form manually before checking
     *   {@code isLoopbackAddress()}. If this case ever fails again, the manual
     *   decimal-parse branch in {@code parseIpLiteralOrNull} has rotted.
     *
     * <p>Notable: {@code http://[::ffff:127.0.0.1]} is the IPv4-mapped IPv6 form
     *   of 127.0.0.1. Java's {@code InetAddress.isLoopbackAddress()} unwraps the
     *   mapping automatically — no controller-side mapping needed.
     */
    @ParameterizedTest(name = "rejects bypass {0}")
    @ValueSource(strings = {
            "http://127.0.0.2",                 // rest of 127.0.0.0/8 loopback range
            "http://127.255.255.255",           // top of 127.0.0.0/8
            "http://[::ffff:127.0.0.1]",        // IPv4-mapped IPv6 loopback
            "http://2130706433",                // decimal-encoded 127.0.0.1
    })
    void registerPeer_rejectsLoopbackBypassVectors(String bypassUrl) {
        HttpHeaders auth = userHeaders("a2a-ssrf-bypass");
        Map<String, Object> body = new HashMap<>();
        body.put("remoteAgentId", "remote-agent-bypass");
        body.put("baseUrl", bypassUrl);
        body.put("alias", "peer-bypass-" + UUID.randomUUID());
        body.put("apiKey", "sk-irrelevant");

        ResponseEntity<Map<String, Object>> register = rest.exchange(
                url("/api/v1/a2a/peers"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(400, register.getStatusCode().value(),
                "SSRF guard must reject the loopback bypass baseUrl=" + bypassUrl
                        + " — a 200 here would mean the InetAddress.isLoopbackAddress() check "
                        + "regressed (e.g., the decimal-parse branch was removed, or the guard "
                        + "flipped back to string-equals)");

        Integer persistedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_remote_agents WHERE alias = ?",
                Integer.class, body.get("alias"));
        assertEquals(0, persistedRows,
                "rejected URL must not persist any a2a_remote_agents row");
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-ssrf", List.of("ROLE_USER"));
    }
}
