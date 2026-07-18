package ai.operativus.agentmanager.compute.extensions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain Responsibility: Locks the SSRF guard on
 *   {@link WebhookWorkflowStepExecutor#executeStep}. The executor is invoked when a
 *   workflow step's {@code executorId} starts with {@code http://} or {@code https://};
 *   the operator-supplied URL is then POSTed via JDK {@link java.net.http.HttpClient}.
 *   Without the guard, a workflow could exfiltrate via {@code http://169.254.169.254/...}
 *   (cloud metadata) or any RFC-1918 address reachable from the AGM host.
 *
 *   <p>Strict-mode guard (allowLoopback=false) is exercised here. The 22 rejection
 *   vectors mirror the K1 {@code KnowledgeIngestUrlSsrfRuntimeTest} list because all
 *   three callers ({@code A2AController}, {@code KnowledgeService},
 *   {@code WebhookWorkflowStepExecutor}) delegate to the same
 *   {@link ai.operativus.agentmanager.core.security.SsrfGuard#validate}.
 *
 *   <p>Each rejected URL must cause {@code executeStep} to throw
 *   {@link RuntimeException} with a message containing the SsrfGuard rejection text —
 *   no outbound {@link java.net.http.HttpClient} call may be attempted (the rejection
 *   throws before the {@code HttpRequest.newBuilder}).
 *
 * State: Stateless. Pure unit test — no Spring context, no Testcontainers; the executor
 *   constructor takes a boolean flag directly so strict mode is just {@code new
 *   WebhookWorkflowStepExecutor(false)}.
 */
class WebhookWorkflowStepExecutorSsrfTest {

    private final WebhookWorkflowStepExecutor executor =
            new WebhookWorkflowStepExecutor(false /* strict, no-loopback mode */);

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            // Loopback hostnames + IPv4 + IPv6
            "http://localhost",
            "http://localhost:8080/admin",
            "http://127.0.0.1",
            "https://127.0.0.1:8443/admin/api",
            "http://[::1]/path",
            // Rest of 127.0.0.0/8
            "http://127.0.0.2",
            "http://127.255.255.255",
            // IPv4-mapped IPv6 loopback
            "http://[::ffff:127.0.0.1]/path",
            // Decimal-encoded IPv4 — JDK HttpClient DOES accept this; SsrfGuard's manual
            // all-digits parser catches it.
            "http://2130706433/path",
            // Wildcard / any-address bind
            "http://0.0.0.0/admin",
            // Cloud metadata — highest blast radius
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://169.254.169.254",
            // RFC-1918 private ranges
            "http://10.0.0.1/api",
            "http://10.255.255.255/admin",
            "http://192.168.1.1/router",
            "http://192.168.255.255",
            "http://172.16.0.1",
            "http://172.31.255.255/admin",
            // Non-http(s) schemes
            "file:///etc/passwd",
            "javascript:alert(1)",
            "gopher://localhost",
            "ftp://internal.acme/secrets",
    })
    void executeStep_rejectsSsrfTargetUrls(String maliciousUrl) {
        assertThatThrownBy(() -> executor.executeStep(maliciousUrl,
                "wf-id", "run-id", "payload", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Webhook URL rejected by SSRF guard");
    }

    /**
     * Public-FQDN / outside-RFC1918-boundary URLs MUST pass the guard. The downstream
     * HttpClient send() will fail (unknown host, refused, etc.) but with a different
     * exception kind — what we pin here is "the SSRF guard did not reject before the
     * HttpClient call was attempted." The wrapper exception text is "Extension Webhook
     * execution failed." (from the surrounding try/catch on the actual send), NOT the
     * SSRF rejection prefix.
     */
    @ParameterizedTest(name = "accepts boundary {0}")
    @CsvSource({
            "https://172.15.255.255/path, just-below-rfc1918-172",
            "https://172.32.0.1/path, just-above-rfc1918-172",
            "https://peer.example.com/api, public-fqdn",
    })
    void executeStep_acceptsBoundaryUrls_failsLaterInHttpClient(String publicUrl, String label) {
        // The guard MUST NOT reject these. The HttpClient will then fail downstream
        // (unknown host, refused connection, etc.) — that's a different error path, and
        // its message DOES NOT contain "Webhook URL rejected by SSRF guard".
        assertThatThrownBy(() -> executor.executeStep(publicUrl,
                "wf-id", "run-id-" + label, "payload", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageNotContaining("Webhook URL rejected by SSRF guard");
    }
}
