package com.operativus.agentmanager.compute.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;


import java.io.IOException;

/**
 * Domain Responsibility: Provides Spring AI tools for executing financial data retrieval tasks (e.g., scraping near real-time stock prices).
 * State: Stateless
 */
@AgentToolComponent
public class FinanceTools {

    private static final Logger logger = LoggerFactory.getLogger(FinanceTools.class);

    @Value("${agent.tools.yahoo-finance.base-url:https://finance.yahoo.com/quote/}")
    private String yahooUrl;

    /**
     * @summary Fetches the current stock price for a given ticker symbol.
     * @logic Connects to Yahoo Finance via Jsoup, searches for the 'fin-streamer' element with the regularMarketPrice data field, and extracts its value.
     */
    @RequiresCapability("finance_access")
    @Tool(name = "stockPrice", description = "Get the current stock price for a given ticker symbol (e.g., AAPL, NVDA).")
    public String stockPrice(String ticker) {
        logger.info("Fetching stock price for: {}", ticker);
        try {
            // Basic scraping structure for Yahoo Finance - Note: This class names change frequently, 
            // relying on standard meta tags or specific data attributes is safer, 
            // but for a demo/parity implementation, text matching or 'fin-streamer' is common.
            Document doc = Jsoup.connect(yahooUrl + ticker)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36")
                    .get();

            // Trying to find the price based on data-test or common classes. 
            // Yahoo Finance typically uses <fin-streamer data-field="regularMarketPrice">
            Element priceElement = doc.selectFirst("fin-streamer[data-field='regularMarketPrice']");
            
            if (priceElement != null) {
                return priceElement.attr("data-value"); // Often reliable attribute
            } else {
                 // Fallback to text search if fin-streamer fails (simplified)
                 // This is brittle but sufficient for "MVP" if fin-streamer is present.
                 return "Could not retrieve price. Ticker might be invalid or page structure changed.";
            }

        } catch (IOException e) {
             logger.error("Error fetching stock price", e);
             return "Error fetching stock price: " + e.getMessage();
        }
    }
}
