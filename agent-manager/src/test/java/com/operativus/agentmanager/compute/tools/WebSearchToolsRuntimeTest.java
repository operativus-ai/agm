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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime test for {@link WebSearchTools#search(String)} —
 * verifies the parity §2.5 DuckDuckGo ✅ claim. Asserts the 4 vectors per
 * docs/plans/agm-tools-impl.md §3:
 *   (a) DDG HTML with 5 result blocks → 5 entries with title/URL/snippet, joined by "\n---\n"
 *   (b) DDG HTML with zero `.result` blocks → "No results found for query: <q>"
 *   (c) HTTP 500 / IOException → "Error performing web search:"
 *   (d) DDG HTML with > 5 results → caps at 5 entries
 *
 * State: Stateless. Per-class WireMockServer with dynamicPort(). Constructed
 * WebSearchTools post-Spring-injection via reflection on the non-final {@code ddgUrl}
 * instance field — substitutes Spring's @Value mechanism in this pure-unit-test context.
 *
 * <p>Independent ground truth (A18): WireMock returns spec-fixed HTML; production parses
 * via Jsoup. Assertions read the production-formatted output, not the source HTML.
 *
 * <p>A21: fixture HTML modeled 2026-04-29 against production
 * {@code https://html.duckduckgo.com/html/} response shape (`.result` containers with
 * `.result__a` and `.result__snippet` children). Live validation deferred per existing
 * precedent (no DDG-throttling-tolerant CI access).
 */
class WebSearchToolsRuntimeTest {

    private static WireMockServer wireMock;
    private static String testBaseUrl;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        testBaseUrl = "http://localhost:" + wireMock.port() + "/html/";
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetMock() {
        wireMock.resetAll();
    }

    private WebSearchTools toolWithMockedUrl() {
        WebSearchTools tool = new WebSearchTools();
        try {
            Field f = WebSearchTools.class.getDeclaredField("ddgUrl");
            f.setAccessible(true);
            f.set(tool, testBaseUrl);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("test setup: cannot inject ddgUrl", e);
        }
        return tool;
    }

    /** DDG sends queries via POST in the html.duckduckgo.com/html/ form. We accept either GET or POST. */
    private static void stubDdg(String body) {
        stubFor(post(urlPathEqualTo("/html/"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body)));
        stubFor(get(urlPathMatching("/html/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(body)));
    }

    private static String resultBlock(int i) {
        return "<div class=\"result\">" +
                "<a class=\"result__a\" href=\"https://r" + i + ".example.com\">Title " + i + "</a>" +
                "<div class=\"result__snippet\">Snippet " + i + " body</div>" +
                "</div>";
    }

    private static String htmlWithResults(int n) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 1; i <= n; i++) {
            sb.append(resultBlock(i));
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    // (a) 5 results → 5 entries with separator
    @Test
    void search_fiveResults_returnsFiveFormattedEntries() {
        stubDdg(htmlWithResults(5));

        String result = toolWithMockedUrl().search("kubernetes");

        // Each block contributes one Title:/URL:/Snippet: triplet, separated by "\n---\n".
        long blocks = result.split("\n---\n").length;
        assertEquals(5L, blocks, "expected 5 result blocks, got: " + result);
        assertTrue(result.contains("Title: Title 1"), "expected first title, got: " + result);
        assertTrue(result.contains("URL: https://r1.example.com"), "expected first URL, got: " + result);
        assertTrue(result.contains("Snippet: Snippet 1 body"), "expected first snippet, got: " + result);
    }

    // (b) zero .result blocks → canonical "No results found"
    @Test
    void search_zeroResults_returnsNoResultsFound() {
        stubDdg("<html><body><div class=\"unrelated\">nothing here</div></body></html>");

        String result = toolWithMockedUrl().search("oddly specific");

        assertEquals("No results found for query: oddly specific", result);
    }

    // (c) HTTP 500 → "Error performing web search:"
    @Test
    void search_httpError_returnsErrorPrefix() {
        stubFor(post(urlPathEqualTo("/html/"))
                .willReturn(aResponse().withStatus(500).withBody("server down")));
        stubFor(get(urlPathMatching("/html/.*"))
                .willReturn(aResponse().withStatus(500).withBody("server down")));

        String result = toolWithMockedUrl().search("anything");

        assertTrue(result.startsWith("Error performing web search:"),
                "expected error prefix, got: " + result);
    }

    // (d) > 5 results → caps at 5
    @Test
    void search_moreThanFive_capsAtFive() {
        stubDdg(htmlWithResults(8));

        String result = toolWithMockedUrl().search("popular");

        long blocks = result.split("\n---\n").length;
        assertEquals(5L, blocks, "expected to cap at 5 results, got: " + blocks + " blocks");
        assertTrue(result.contains("Title 1"), "first should be present");
        assertTrue(result.contains("Title 5"), "fifth should be present");
        assertTrue(!result.contains("Title 6"), "sixth must NOT be present");
    }
}
