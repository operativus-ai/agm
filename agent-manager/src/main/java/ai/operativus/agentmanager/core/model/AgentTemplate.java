package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.FinOpsRiskTier;

import java.util.List;

/**
 * Pre-defined agent profile templates that auto-populate sensible defaults.
 * Administrators select a template when creating an agent, reducing the
 * form from 30+ fields to 3 (name, template, instructions).
 *
 * <p>Convention: Templates are code-defined, not DB entities. Adding a new
 * template requires a code change — this is intentional to prevent
 * configuration sprawl of templates themselves.</p>
 */
public record AgentTemplate(
        String id,
        String name,
        String description,
        String icon,
        String defaultModel,
        Double defaultTemperature,
        FinOpsRiskTier finOpsRiskTier,
        List<String> defaultToolCategories,
        List<String> defaultTools,
        boolean requiresPiiRedaction,
        boolean memoryEnabled,
        Integer securityTier,
        String systemPromptMode,
        boolean enforceJsonOutput
) {

    /**
     * Built-in templates. Add new templates here.
     */
    public static List<AgentTemplate> builtInTemplates() {
        return List.of(
                new AgentTemplate(
                        "general_assistant",
                        "General Assistant",
                        "A versatile conversational agent for general Q&A, summarization, and reasoning tasks.",
                        "chat",
                        null, // Inherits global default
                        null, // Inherits global default
                        FinOpsRiskTier.LOW_RISK,
                        List.of(),
                        List.of(),
                        false,
                        true,
                        1,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "researcher",
                        "Research Analyst",
                        "A knowledge-powered research agent with RAG, web search, and long-term memory.",
                        "search",
                        null,
                        null,
                        FinOpsRiskTier.MODERATE_RISK,
                        List.of("RESEARCHER", "WEB_AGENT"),
                        List.of("search_knowledge_base", "webSearch", "firecrawl_web_search", "save_memory"),
                        false,
                        true,
                        1,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "financial_analyst",
                        "Financial Analyst",
                        "A strict, HITL-gated agent for financial data with PII redaction and tight budget controls.",
                        "dollar-sign",
                        null,
                        0.3,
                        FinOpsRiskTier.STRICT,
                        List.of("FINANCE", "RESEARCHER"),
                        List.of("stockPrice", "search_knowledge_base"),
                        true,
                        true,
                        3,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "developer_tool",
                        "Internal Dev Tool",
                        "A fast, low-latency agent for internal developer workflows. No HITL, code execution enabled.",
                        "code",
                        null,
                        0.5,
                        FinOpsRiskTier.LOW_RISK,
                        List.of("DEVELOPER", "RESEARCHER"),
                        List.of("run_python", "search_knowledge_base"),
                        false,
                        false,
                        1,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "web_scraper",
                        "Web Scraper & Ingestion",
                        "A web crawling agent that scrapes documentation sites and ingests them into knowledge bases.",
                        "globe",
                        null,
                        null,
                        FinOpsRiskTier.MODERATE_RISK,
                        List.of("WEB_AGENT"),
                        // "web_crawl" was a phantom — no @Tool by that name exists, so it was silently
                        // dropped at resolveTools and the agent could neither bulk-ingest a docs site nor
                        // push results to the KB (despite the template's "ingests them into knowledge
                        // bases" promise). Use the real ingestion tools (the same set the web_scraper
                        // seed agent carries). Guarded by AgentTemplateToolsResolveArchTest.
                        List.of("readWebpage", "pushToKnowledgeBase", "bulkIngestDocumentationSite",
                                "firecrawl_web_search", "firecrawl_scrape_url"),
                        false,
                        false,
                        1,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "structured_output",
                        "Structured Data Extractor",
                        "A JSON-enforced agent for data extraction, classification, and structured output tasks.",
                        "braces",
                        null,
                        0.2,
                        FinOpsRiskTier.LOW_RISK,
                        List.of(),
                        List.of(),
                        false,
                        false,
                        1,
                        "APPEND",
                        true
                ),
                new AgentTemplate(
                        "customer_support",
                        "Customer Support Agent",
                        "A memory-enabled support agent with RAG knowledge retrieval, PII redaction, and HITL escalation for sensitive cases.",
                        "headphones",
                        null,
                        0.4,
                        FinOpsRiskTier.MODERATE_RISK,
                        List.of("RESEARCHER"),
                        List.of("search_knowledge_base", "save_memory"),
                        true,
                        true,
                        2,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "content_writer",
                        "Content Writer",
                        "A creative writing agent with higher temperature for brainstorming, drafting, and editorial tasks. Memory-enabled for style consistency.",
                        "pen-tool",
                        null,
                        0.8,
                        FinOpsRiskTier.LOW_RISK,
                        List.of(),
                        List.of(),
                        false,
                        true,
                        1,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "compliance_auditor",
                        "Compliance & Audit Agent",
                        "A high-security agent for regulatory review with full PII scrubbing, strict budgets, and mandatory human oversight.",
                        "shield",
                        null,
                        0.2,
                        FinOpsRiskTier.CRITICAL,
                        List.of("RESEARCHER"),
                        List.of("search_knowledge_base"),
                        true,
                        true,
                        3,
                        "APPEND",
                        false
                ),
                new AgentTemplate(
                        "data_pipeline",
                        "Data Pipeline Agent",
                        "An ETL agent that scrapes, transforms, and ingests structured data into knowledge bases. JSON-enforced output with code execution.",
                        "database",
                        null,
                        0.2,
                        FinOpsRiskTier.MODERATE_RISK,
                        List.of("WEB_AGENT", "DEVELOPER"),
                        List.of("firecrawl_scrape_url", "firecrawl_web_search", "run_python", "search_knowledge_base"),
                        false,
                        false,
                        1,
                        "APPEND",
                        true
                ),
                new AgentTemplate(
                        "custom",
                        "Custom (Blank)",
                        "Start from scratch with full control over all configuration options.",
                        "settings",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        false,
                        false,
                        null,
                        "APPEND",
                        false
                )
        );
    }
}
