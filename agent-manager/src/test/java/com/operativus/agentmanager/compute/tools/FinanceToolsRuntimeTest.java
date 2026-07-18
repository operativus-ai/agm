package com.operativus.agentmanager.compute.tools;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime test for {@link FinanceTools#stockPrice(String)} —
 * verifies the parity §2.7 yfinance ❌ partial claim from
 * agm-agno-tool-parity-analysis.md (acknowledged 1-method placeholder). Asserts the 3
 * vectors per docs/plans/agm-tools-impl.md §3:
 *   (a) Yahoo HTML with <fin-streamer data-field="regularMarketPrice" data-value="187.42"> → "187.42"
 *   (b) Yahoo HTML missing fin-streamer → canonical "Could not retrieve price..." string
 *   (c) HTTP error / IOException → "Error fetching stock price:"
 *
 * State: Stateless. Per-class WireMockServer with dynamicPort(). Constructed FinanceTools
 * post-Spring-injection via reflection on the non-final {@code yahooUrl} instance field.
 *
 * <p>A21: fixture HTML modeled 2026-04-29 against production
 * {@code https://finance.yahoo.com/quote/AAPL} response shape (`<fin-streamer
 * data-field="regularMarketPrice" data-value="...">`). Yahoo Finance HTML is volatile;
 * production code already comments on this brittleness. Live validation deferred per
 * existing precedent.
 */
class FinanceToolsRuntimeTest {

    private static WireMockServer wireMock;
    private static String testBaseUrl;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        testBaseUrl = "http://localhost:" + wireMock.port() + "/quote/";
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetMock() {
        wireMock.resetAll();
    }

    private FinanceTools toolWithMockedUrl() {
        FinanceTools tool = new FinanceTools();
        try {
            Field f = FinanceTools.class.getDeclaredField("yahooUrl");
            f.setAccessible(true);
            f.set(tool, testBaseUrl);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("test setup: cannot inject yahooUrl", e);
        }
        return tool;
    }

    private static void stubYahoo(String body, int status) {
        stubFor(get(urlPathMatching("/quote/.*"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "text/html").withBody(body)));
    }

    // (a) fin-streamer present → numeric data-value
    @Test
    void stockPrice_finStreamerPresent_returnsDataValue() {
        String html = "<html><body>" +
                "<fin-streamer data-field=\"regularMarketPrice\" data-value=\"187.42\">187.42</fin-streamer>" +
                "</body></html>";
        stubYahoo(html, 200);

        String result = toolWithMockedUrl().stockPrice("AAPL");

        assertEquals("187.42", result);
    }

    // (b) fin-streamer missing → canonical fallback
    @Test
    void stockPrice_finStreamerMissing_returnsCanonicalFallback() {
        String html = "<html><body><div>page changed structure</div></body></html>";
        stubYahoo(html, 200);

        String result = toolWithMockedUrl().stockPrice("UNKNOWN");

        assertEquals("Could not retrieve price. Ticker might be invalid or page structure changed.", result);
    }

    // (c) HTTP 500 → "Error fetching stock price:"
    @Test
    void stockPrice_httpError_returnsErrorPrefix() {
        stubYahoo("server down", 500);

        String result = toolWithMockedUrl().stockPrice("AAPL");

        assertTrue(result.startsWith("Error fetching stock price:"),
                "expected error prefix, got: " + result);
    }
}
