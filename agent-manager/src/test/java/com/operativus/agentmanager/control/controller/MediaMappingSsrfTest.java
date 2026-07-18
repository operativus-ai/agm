package com.operativus.agentmanager.control.controller;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused SSRF coverage for the {@code mapMedia} helper on {@link AgentsController}.
 * (The sibling helper on the enterprise UniversalDispatchController carries the same
 * contract, tested in the agm-enterprise repo.)
 *
 * <p>The controller accepts a list of media inputs on its run endpoints. Each input is
 * either base64-encoded inline bytes or a URL. The URL branch was gated only by a
 * {@code startsWith("http")} check — sufficient to block {@code file:} but not
 * loopback / RFC-1918 / 169.254 cloud-metadata.
 *
 * <p>Threat shape: an authenticated tenant user POSTs:
 * <pre>{
 *   "media": [{"type": "image/jpeg",
 *              "data": "http://169.254.169.254/latest/meta-data/iam/security-credentials/role"}]
 * }</pre>
 * The controller hands {@code new UrlResource("http://169.254...")} to Spring AI's
 * media pipeline, which eagerly fetches the URL bytes to ship into the LLM payload.
 * The IMDS response body lands in the LLM prompt and is likely echoed back in the
 * model's output. Blind SSRF + LLM-mediated exfiltration.
 *
 * <p>The fix wires {@code SsrfGuard.validate(url, allowLoopback=false)} into the
 * helper before the {@code UrlResource} is constructed.
 *
 * <p>The helper is package-private static specifically so this test can invoke it
 * without spinning up the controller with its full service-dependency graph — a pure
 * mapping function, no state needed.
 */
class MediaMappingSsrfTest {

    @Test
    void agents_RejectsCloudMetadataMediaUrl() {
        AgentsController.MediaInput input = new AgentsController.MediaInput(
                "image/jpeg",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/role");

        // AgentsController.mapMedia throws IllegalArgumentException on SSRF (matches
        // its existing error-wrapping pattern — RuntimeException for everything else,
        // pass-through for IllegalArgumentException so the SSRF reason reaches the user).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentsController.mapMedia(List.of(input), null));
        assertTrue(ex.getMessage().contains("SSRF"),
                "expected SSRF rejection in message; got: " + ex.getMessage());
    }

    @Test
    void agents_RejectsLoopbackMediaUrl() {
        AgentsController.MediaInput input = new AgentsController.MediaInput(
                "image/jpeg", "http://127.0.0.1:8080/internal");

        assertThrows(IllegalArgumentException.class,
                () -> AgentsController.mapMedia(List.of(input), null));
    }

    @Test
    void agents_RejectsRfc1918MediaUrl() {
        AgentsController.MediaInput input = new AgentsController.MediaInput(
                "image/jpeg", "http://10.0.0.5/image.jpg");

        assertThrows(IllegalArgumentException.class,
                () -> AgentsController.mapMedia(List.of(input), null));
    }

    @Test
    void agents_AcceptsBase64InlineMedia_NoSsrfCheck() {
        // Base64 path bypasses the URL/SSRF branch entirely — no http prefix.
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        AgentsController.MediaInput input = new AgentsController.MediaInput("image/png", b64);

        var media = AgentsController.mapMedia(List.of(input), null);
        assertEquals(1, media.size());
    }

    // Note: there's no positive-flow URL test because Spring AI's
    // {@code new Media(MimeType, UrlResource)} constructor does an eager fetch — even
    // a TEST-NET-1 IP literal (192.0.2.1) timed out the test at 60+ seconds in CI.
    // The contract under test (SsrfGuard fires before UrlResource construction) is
    // adequately covered by the rejection cases above + the base64-inline path that
    // bypasses the URL branch entirely.
}
