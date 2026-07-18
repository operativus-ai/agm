package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.core.registry.ConfigurationProvider;
import ai.operativus.agentmanager.core.registry.KnowledgeOperations;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Domain Responsibility: Pins the SSRF guard on {@link WebsiteCrawlerService}'s agent-facing
 * scrape/crawl entry points. Firecrawl shares AGM's internal network, so an unguarded
 * agent-supplied URL would let an agent make Firecrawl fetch internal services / cloud metadata.
 * The guard short-circuits BEFORE any network call, so a real RestClient is safe here.
 * State: Stateless.
 */
class WebsiteCrawlerServiceSsrfTest {

    private WebsiteCrawlerService service(boolean allowLoopback) {
        return new WebsiteCrawlerService(
                mock(KnowledgeOperations.class),
                mock(ConfigurationProvider.class),
                "http://localhost:3002",
                5000L, 900L, allowLoopback,
                RestClient.builder());
    }

    // Cloud-metadata is rejected even when loopback is permitted (always-on rejection).
    @Test
    void scrape_cloudMetadata_refusedEvenWithLoopbackAllowed() {
        String result = service(true).scrapeWebpage("http://169.254.169.254/latest/meta-data/");
        assertTrue(result.startsWith("Refused to scrape URL"), result);
    }

    // Loopback/internal targets refused in the prod-default posture.
    @Test
    void scrape_internalHost_refusedWhenLoopbackDisallowed() {
        String result = service(false).scrapeWebpage("http://127.0.0.1:8080/actuator/env");
        assertTrue(result.startsWith("Refused to scrape URL"), result);
    }

    @Test
    void scrape_nonHttpScheme_refused() {
        String result = service(false).scrapeWebpage("file:///etc/passwd");
        assertTrue(result.startsWith("Refused to scrape URL"), result);
    }

    // Crawl funnel throws (its contract) rather than returning a message.
    @Test
    void crawl_internalHost_throwsWhenLoopbackDisallowed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service(false).crawlSiteBlocking("http://10.0.0.5/internal", "cat"));
        assertTrue(ex.getMessage().startsWith("Refused to crawl URL"), ex.getMessage());
    }
}
