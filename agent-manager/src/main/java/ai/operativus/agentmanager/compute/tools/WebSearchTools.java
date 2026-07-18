package ai.operativus.agentmanager.compute.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain Responsibility: Provides Spring AI tools for executing public web searches (e.g., DuckDuckGo HTML parser) to retrieve real-time facts not present in the internal knowledge base.
 * State: Stateless
 */
@AgentToolComponent
public class WebSearchTools {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTools.class);

    @Value("${agent.tools.duckduckgo.base-url:https://html.duckduckgo.com/html/}")
    private String ddgUrl;

    /**
     * @summary Conducts a DuckDuckGo HTML web search for a given query.
     * @logic Retrieves raw HTML from DDG via JSoup, parses the results array, and formats the top 5 links and snippets into a readable string.
     */
    @RequiresCapability("web_access")
    @Tool(name = "webSearch", description = "Search the web for information using DuckDuckGo. Use this tool when you need current information or facts not in your knowledge base.")
    public String search(String query) {
        logger.info("Performing DuckDuckGo search for: {}", query);
        try {
            Document doc = Jsoup.connect(ddgUrl)
                    .data("q", query)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36")
                    .get();

            Elements results = doc.select(".result");
            List<String> formattedResults = new ArrayList<>();

            for (Element result : results) {
                Element titleEl = result.selectFirst(".result__a");
                Element snippetEl = result.selectFirst(".result__snippet");

                if (titleEl != null && snippetEl != null) {
                    String title = titleEl.text();
                    String url = titleEl.attr("href");
                    String snippet = snippetEl.text();
                    formattedResults.add(String.format("Title: %s\nURL: %s\nSnippet: %s\n", title, url, snippet));
                }
                if (formattedResults.size() >= 5) break; 
            }

            if (formattedResults.isEmpty()) {
                return "No results found for query: " + query;
            }

            return String.join("\n---\n", formattedResults);

        } catch (IOException e) {
            logger.error("Error performing web search", e);
            return "Error performing web search: " + e.getMessage();
        }
    }
}
