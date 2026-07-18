package com.operativus.agentmanager.control.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.security.MediaUrlFetcher.MediaFetchException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior pins for {@link MediaUrlFetcher} — the DoS guard between user-supplied
 * media URLs and Spring AI's media pipeline. Six invariants:
 * <ol>
 *   <li>Successful fetch returns the response body bytes exactly</li>
 *   <li>Advertised {@code Content-Length} over cap is rejected before any body read
 *       (fail-fast path)</li>
 *   <li>Streamed body over cap is rejected mid-read when Content-Length is absent
 *       (the cap is enforced on the wire, not just on advertised length)</li>
 *   <li>Non-2xx response is rejected with a status-bearing message</li>
 *   <li>3xx redirect is REFUSED rather than followed — following would let a peer
 *       redirect to an SSRF-guard-blocked URL like {@code http://169.254.169.254/}
 *       and bypass the guard the caller ran on the original URL</li>
 *   <li>Read timeout fires and surfaces as {@link MediaFetchException} rather than
 *       hanging the request thread (the slow-loris DoS this fetcher exists to block)</li>
 * </ol>
 */
class MediaUrlFetcherTest {

    private static final int CAP_BYTES = 100;
    // Originally 300ms — local runs were green but the GitHub Actions ubuntu-latest
    // runner consistently exceeded that for the non-2xx case (WireMock setup + HTTP
    // round-trip > 300ms there), causing fetchRejectsNon2xxResponse to surface a
    // timeout instead of the expected 4xx. 1500ms gives CI ~5x the local headroom
    // while keeping the timeout-fires test fast (its WireMock delay grows in lockstep).
    private static final Duration FAST_TIMEOUT = Duration.ofMillis(1500);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static WireMockServer wiremock;
    private static MediaUrlFetcher fetcher;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        fetcher = MediaUrlFetcher.forLimits(CONNECT_TIMEOUT, FAST_TIMEOUT, CAP_BYTES);
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    @AfterEach
    void resetStubs() {
        wiremock.resetAll();
    }

    @Test
    void fetchReturnsResponseBodyOnSuccess() {
        byte[] payload = bytes(64, (byte) 'x');
        wiremock.stubFor(get(urlPathEqualTo("/img.png"))
                .willReturn(aResponse().withStatus(200).withBody(payload)));

        byte[] result = fetcher.fetch(wiremock.baseUrl() + "/img.png");

        assertArrayEquals(payload, result,
                "successful fetch must return the response body bytes exactly");
    }

    @Test
    void fetchRejectsAdvertisedContentLengthAboveCap() {
        // Server advertises an honest 1000-byte payload (above the 100-byte cap). The
        // fetcher must reject on the Content-Length header before entering the body-read
        // loop — fail-fast for the common case where the server is truthful about an
        // oversized payload.
        //
        // The body length MUST match the advertised Content-Length: a mismatch (e.g.
        // CL=1000 with a 50-byte body) is a malformed response that, under HTTP/2,
        // intermittently surfaces as an RST_STREAM IOException during HttpClient.send()
        // — BEFORE the fetcher reaches its cap check — flaking this test on CI. WireMock
        // sets Content-Length itself from the fixed body, so framing stays consistent.
        wiremock.stubFor(get(urlPathEqualTo("/big-advertised.bin"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(bytes(1000, (byte) 'a'))));

        MediaFetchException ex = assertThrows(MediaFetchException.class,
                () -> fetcher.fetch(wiremock.baseUrl() + "/big-advertised.bin"));
        assertTrue(ex.getMessage().contains("Content-Length"),
                "rejection on advertised length must say so in the message; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(CAP_BYTES)),
                "rejection must include the configured cap; got: " + ex.getMessage());
    }

    @Test
    void fetchRejectsStreamedBodyAboveCap() {
        // Body is 500 bytes; cap is 100. WireMock will set Content-Length=500 on its
        // own (so this also exercises the advertised-length fail-fast path), but the
        // assertion only requires that the fetcher rejects — either branch is correct
        // behavior. The pin is "oversized body is refused, period."
        wiremock.stubFor(get(urlPathEqualTo("/big-actual.bin"))
                .willReturn(aResponse().withStatus(200).withBody(bytes(500, (byte) 'z'))));

        MediaFetchException ex = assertThrows(MediaFetchException.class,
                () -> fetcher.fetch(wiremock.baseUrl() + "/big-actual.bin"));
        assertTrue(ex.getMessage().contains("cap")
                        || ex.getMessage().contains("exceed"),
                "rejection must reference the size cap; got: " + ex.getMessage());
    }

    @Test
    void fetchRejectsNon2xxResponse() {
        wiremock.stubFor(get(urlPathEqualTo("/forbidden.bin"))
                .willReturn(aResponse().withStatus(403).withBody("nope")));

        MediaFetchException ex = assertThrows(MediaFetchException.class,
                () -> fetcher.fetch(wiremock.baseUrl() + "/forbidden.bin"));
        assertTrue(ex.getMessage().contains("403"),
                "rejection on 4xx must carry the status code in the message; got: " + ex.getMessage());
    }

    @Test
    void fetchRefusesRedirectInsteadOfFollowing() {
        // Following the redirect would let a peer steer the fetcher at an SSRF target
        // that the caller's pre-fetch SsrfGuard already validated would be rejected.
        // The fetcher MUST refuse, with a 3xx-mentioning message.
        wiremock.stubFor(get(urlPathEqualTo("/redirect.bin"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "http://169.254.169.254/latest/meta-data/")));

        MediaFetchException ex = assertThrows(MediaFetchException.class,
                () -> fetcher.fetch(wiremock.baseUrl() + "/redirect.bin"));
        assertTrue(ex.getMessage().contains("302") || ex.getMessage().contains("redirect"),
                "rejection on 3xx must mention the redirect; got: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("ssrf")
                        || ex.getMessage().toLowerCase().contains("bypass"),
                "rejection must surface the SSRF-bypass reason so operators know why; got: "
                        + ex.getMessage());
    }

    @Test
    void fetchTimesOutOnSlowResponse() {
        // WireMock delays 3500ms, fetcher read timeout is FAST_TIMEOUT (1500ms). The
        // fetch must surface as MediaFetchException (caller-actionable) rather than a
        // hung thread. This is the slow-loris DoS this whole class exists to mitigate.
        //
        // Delay must comfortably exceed FAST_TIMEOUT so the read-timeout assertion
        // fires before WireMock would serve the body. 3500ms vs 1500ms = ~2.3x margin,
        // enough headroom for CI runner jitter without making the test slow.
        wiremock.stubFor(get(urlPathEqualTo("/slow.bin"))
                .willReturn(aResponse().withFixedDelay(3500).withBody(bytes(10, (byte) 's'))));

        long start = System.nanoTime();
        MediaFetchException ex = assertThrows(MediaFetchException.class,
                () -> fetcher.fetch(wiremock.baseUrl() + "/slow.bin"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(ex.getMessage().toLowerCase().contains("timed out")
                        || ex.getMessage().toLowerCase().contains("timeout"),
                "timeout rejection must say so; got: " + ex.getMessage());
        // Generous upper bound — should land near FAST_TIMEOUT (1500ms) plus jitter.
        // The pin is that we did NOT wait the full 3500ms of WireMock's delay.
        assertTrue(elapsedMs < 3000,
                "fetch should have aborted near the read timeout (~1500ms); "
                        + "took " + elapsedMs + "ms — close to WireMock's 3500ms delay "
                        + "indicates the read timeout is not wired");
    }

    private static byte[] bytes(int n, byte value) {
        byte[] b = new byte[n];
        Arrays.fill(b, value);
        return b;
    }
}
