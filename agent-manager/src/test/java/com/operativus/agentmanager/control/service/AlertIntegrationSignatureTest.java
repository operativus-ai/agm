package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AlertIntegration;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the HMAC-SHA256 signature contract for outbound webhook
 * dispatch. Receivers depend on the exact canonical string ({@code timestamp + "." + body})
 * and the {@code sha256=<hex>} prefix; if either drifts, every existing receiver breaks
 * silently. These tests are the wire-format contract.
 */
class AlertIntegrationSignatureTest {

    @Test
    void computeHmacSha256_matchesRfc4231Style_fixedVector() {
        // Generated via:
        //   echo -n '1700000000000.{"hello":"world"}' | openssl dgst -sha256 -hmac 'shhh'
        String sig = AlertIntegrationService.computeHmacSha256(
                "shhh", "1700000000000.{\"hello\":\"world\"}");
        assertThat(sig).isEqualTo("2b7ab014b8d9ebf84c9f394967c0309abea8fc4588189aedaaf00c3e938a6038");
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void computeHmacSha256_emptySecret_throwsIllegalStateException() {
        assertThatThrownBySigning("", "anything");
    }

    @Test
    void buildSignedRequest_addsBothHeaders_whenSecretSet() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration integration = new AlertIntegration();
        integration.setEndpointUrl("https://example.invalid/hook");
        integration.setSigningSecret("shhh");

        HttpRequest req = service.buildSignedRequest(integration, "{\"hello\":\"world\"}");

        Optional<String> timestamp = req.headers().firstValue("X-AGM-Timestamp");
        Optional<String> signature = req.headers().firstValue("X-AGM-Signature");
        assertThat(timestamp).isPresent();
        assertThat(timestamp.get()).matches("\\d{13}");
        assertThat(signature).isPresent();
        assertThat(signature.get()).startsWith("sha256=").hasSize("sha256=".length() + 64);

        // Header signature must equal HMAC of (timestamp + "." + body).
        String expected = "sha256=" + AlertIntegrationService.computeHmacSha256(
                "shhh", timestamp.get() + ".{\"hello\":\"world\"}");
        assertThat(signature.get()).isEqualTo(expected);
    }

    @Test
    void buildSignedRequest_omitsHeaders_whenSecretNotSet() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration integration = new AlertIntegration();
        integration.setEndpointUrl("https://example.invalid/hook");
        integration.setSigningSecret(null);

        HttpRequest req = service.buildSignedRequest(integration, "{\"hello\":\"world\"}");

        assertThat(req.headers().firstValue("X-AGM-Timestamp")).isEmpty();
        assertThat(req.headers().firstValue("X-AGM-Signature")).isEmpty();
        assertThat(req.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(req.method()).isEqualTo("POST");
    }

    @Test
    void buildSignedRequest_omitsHeaders_whenSecretBlank() {
        AlertIntegrationService service = new AlertIntegrationService(null, 5, 2L, 300L, true);
        AlertIntegration integration = new AlertIntegration();
        integration.setEndpointUrl("https://example.invalid/hook");
        integration.setSigningSecret("   ");

        HttpRequest req = service.buildSignedRequest(integration, "{\"hello\":\"world\"}");

        assertThat(req.headers().firstValue("X-AGM-Timestamp")).isEmpty();
        assertThat(req.headers().firstValue("X-AGM-Signature")).isEmpty();
    }

    @Test
    void isSigningSecretSet_reflectsCleartextPresence() {
        AlertIntegration none = new AlertIntegration();
        assertThat(none.isSigningSecretSet()).isFalse();

        none.setSigningSecret("");
        assertThat(none.isSigningSecretSet()).isFalse();

        none.setSigningSecret("    ");
        assertThat(none.isSigningSecretSet()).isFalse();

        none.setSigningSecret("realsecret");
        assertThat(none.isSigningSecretSet()).isTrue();
    }

    private void assertThatThrownBySigning(String secret, String message) {
        try {
            AlertIntegrationService.computeHmacSha256(secret, message);
        } catch (IllegalArgumentException expected) {
            return; // Mac.init rejects empty key with IAE — good
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected exception for empty secret");
    }

    @SuppressWarnings("unused")
    private static List<String> contractDocumentation() {
        // The receiver verification recipe (paste this into integration docs):
        //   1. Read X-AGM-Timestamp header. Reject if it's older than 5 minutes (replay window).
        //   2. Read X-AGM-Signature header. Strip the "sha256=" prefix.
        //   3. Compute HMAC-SHA256 over (timestamp + "." + raw_request_body), keyed by the
        //      shared secret.
        //   4. Compare the two hex strings in constant time (e.g. hmac.compare_digest in Python).
        return List.of();
    }
}
