package com.operativus.agentmanager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import com.operativus.agentmanager.compute.service.AgentService;
import com.operativus.agentmanager.core.model.RunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestConstructor;

// Exploratory @SpringBootTest (0 assertions). Requires host Postgres + external web.
// Not CI-compatible. Re-enable locally by removing @Disabled.
@Disabled("Exploratory scratch; requires host Postgres + live external site. Not CI-compatible.")
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Tag("integration")
public class WebScraperIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebScraperIntegrationTest.class);

    private final AgentService agentService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.operativus.agentmanager.core.model.definitions.AgentRegistry agentRegistry;

    public WebScraperIntegrationTest(AgentService agentService) {
        this.agentService = agentService;
    }

    @Test
    public void testWebScraperToolExecution() throws Exception {
        log.info("Starting WebScraper test...");
        
        com.operativus.agentmanager.core.model.definitions.AgentDefinition mockDef = new com.operativus.agentmanager.core.model.definitions.AgentDefinition(
            "web_scraper", "Web Scraper", "Scrapes web", "You are a web scraper.",
            "gemini-2.5-pro", null, false, true, java.util.List.of("firecrawl_web_search", "readWebpage"), false, false, null, null, null, false, false, false,
            true, null, null, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 1, null, null, null, null, null, null, null, null, null, null
        );
        org.mockito.Mockito.when(agentRegistry.findById(eq("web_scraper"), any())).thenReturn(mockDef);

        RunResponse response = agentService.run("web_scraper", "Use your firecrawl_web_search tool to find 'Spring Boot 3.4 new features'. Do not hallucinate.", "test-session-123");
        String output = "--- TEST RESPONSE ---\n" + response.content() + "\n\n--- TOOLS USED ---\n" + response.tools();
        System.err.println(output);
        log.info("Finished WebScraper test.");
    }
}
