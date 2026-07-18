package ai.operativus.agentmanager;

import ai.operativus.agentmanager.compute.service.WebsiteCrawlerService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

// Exploratory scratch: boots full context, hits live spring.io, sleeps 15s, no assertions.
// Re-enable locally for one-off crawler smoke checks; not CI-safe.
@Disabled("Exploratory test — hits live external site and requires a host Postgres; not CI-compatible")
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Tag("integration")
public class CrawlerTest {

    private final WebsiteCrawlerService websiteCrawlerService;

    public CrawlerTest(WebsiteCrawlerService websiteCrawlerService) {
        this.websiteCrawlerService = websiteCrawlerService;
    }

    @Test
    public void testCrawl() throws Exception {
        System.out.println("Starting test crawl...");
        // Synchronous call or waiting for async to finish
        websiteCrawlerService.crawlAndIngestSiteAsync("https://spring.io/projects/spring-boot");
        // wait to allow background thread to do some work
        Thread.sleep(15000);
        System.out.println("Finished test crawl sleeping.");
    }
}
