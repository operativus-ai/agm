package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Pre-defined workflow templates that auto-populate a multi-step agent pipeline.
 * Administrators select a template when creating a workflow, reducing setup
 * from manual step-by-step configuration to picking a profile and assigning agents.
 *
 * <p>Convention: Templates are code-defined, not DB entities. Adding a new
 * template requires a code change — this is intentional to prevent
 * configuration sprawl of templates themselves.</p>
 */
public record WorkflowTemplate(
        String id,
        String name,
        String description,
        String icon,
        List<WorkflowTemplateStep> steps
) {

    /**
     * A single step within a workflow template. The agentId is null (placeholder)
     * so the admin assigns their own agent to each slot.
     */
    public record WorkflowTemplateStep(
            int stepOrder,
            String label,
            String action,
            String stepType
    ) {}

    /**
     * Built-in workflow templates. Add new templates here.
     */
    public static List<WorkflowTemplate> builtInTemplates() {
        return List.of(
                new WorkflowTemplate(
                        "research_pipeline",
                        "Research Pipeline",
                        "A two-stage pipeline: research a topic, then summarize findings into a concise report.",
                        "search",
                        List.of(
                                new WorkflowTemplateStep(1, "Research", "Research the given topic thoroughly using available tools and knowledge bases.", "AGENT"),
                                new WorkflowTemplateStep(2, "Summarize", "Synthesize the research output into a concise executive summary.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "content_review",
                        "Content Review Pipeline",
                        "A three-stage content pipeline: draft, review for quality and accuracy, then publish.",
                        "file-text",
                        List.of(
                                new WorkflowTemplateStep(1, "Draft", "Generate initial content based on the provided brief.", "AGENT"),
                                new WorkflowTemplateStep(2, "Review", "Review the draft for accuracy, tone, and completeness. Suggest improvements.", "AGENT"),
                                new WorkflowTemplateStep(3, "Finalize", "Apply review feedback and produce the final polished output.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "data_processing",
                        "Data Processing Pipeline",
                        "A three-stage ETL-style pipeline: extract/scrape data, transform it, then validate the output.",
                        "database",
                        List.of(
                                new WorkflowTemplateStep(1, "Extract", "Extract or scrape the requested data from the source.", "AGENT"),
                                new WorkflowTemplateStep(2, "Transform", "Clean, normalize, and structure the extracted data.", "AGENT"),
                                new WorkflowTemplateStep(3, "Validate", "Validate the transformed data for correctness and completeness.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "support_triage",
                        "Customer Support Triage",
                        "Classify an incoming request, check if it meets escalation criteria, then route to a specialist.",
                        "headphones",
                        List.of(
                                new WorkflowTemplateStep(1, "Classify", "Classify the incoming request by category and urgency.", "AGENT"),
                                new WorkflowTemplateStep(2, "Escalation Check", "contains:urgent", "CONDITION"),
                                new WorkflowTemplateStep(3, "Handle", "Address the request using domain knowledge and available tools.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "analysis_with_review",
                        "Analysis with Human Review",
                        "Analyze data, pause for human approval, then generate the final deliverable.",
                        "shield-check",
                        List.of(
                                new WorkflowTemplateStep(1, "Analyze", "Perform detailed analysis on the provided data.", "AGENT"),
                                new WorkflowTemplateStep(2, "Human Review", "Pause for human review and approval before proceeding.", "AGENT"),
                                new WorkflowTemplateStep(3, "Deliver", "Generate the final deliverable based on the approved analysis.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "compliance_audit",
                        "Compliance Audit",
                        "Collect evidence against a policy framework, evaluate compliance, and generate an audit report.",
                        "clipboard-check",
                        List.of(
                                new WorkflowTemplateStep(1, "Collect Evidence", "Gather relevant data, logs, and configuration artifacts for the audit scope.", "AGENT"),
                                new WorkflowTemplateStep(2, "Evaluate Against Policy", "Compare collected evidence against the applicable compliance framework and flag violations.", "AGENT"),
                                new WorkflowTemplateStep(3, "Generate Report", "Produce a structured audit report with findings, risk ratings, and remediation recommendations.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "rag_ingestion",
                        "RAG Ingestion Pipeline",
                        "Crawl a documentation source, chunk and embed the content, then verify index quality.",
                        "book-open",
                        List.of(
                                new WorkflowTemplateStep(1, "Crawl Source", "Crawl the target documentation site or repository and extract raw content.", "AGENT"),
                                new WorkflowTemplateStep(2, "Chunk & Embed", "Split content into semantic chunks and generate vector embeddings for the knowledge base.", "AGENT"),
                                new WorkflowTemplateStep(3, "Verify Index", "Run sample queries against the new index to validate retrieval quality and coverage.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "health_check",
                        "Scheduled Health Check",
                        "Run system diagnostics, evaluate results against thresholds, and alert or report.",
                        "activity",
                        List.of(
                                new WorkflowTemplateStep(1, "Run Diagnostics", "Execute health checks across target services and collect metrics.", "AGENT"),
                                new WorkflowTemplateStep(2, "Check Thresholds", "exceeds:threshold", "CONDITION"),
                                new WorkflowTemplateStep(3, "Alert / Report", "Generate a health report and trigger alerts for any violations found.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "multi_source_aggregation",
                        "Multi-Source Aggregation",
                        "Fetch data from multiple sources in parallel, merge results, then produce a unified summary.",
                        "git-merge",
                        List.of(
                                new WorkflowTemplateStep(1, "Parallel Fetch", "Fetch data concurrently from all configured sources.", "PARALLEL"),
                                new WorkflowTemplateStep(2, "Merge & Deduplicate", "Combine parallel outputs, resolve conflicts, and remove duplicates.", "AGENT"),
                                new WorkflowTemplateStep(3, "Summarize", "Produce a unified summary from the merged dataset.", "AGENT")
                        )
                ),
                new WorkflowTemplate(
                        "approval_gated_deployment",
                        "Approval-Gated Deployment",
                        "Prepare a deployment payload, wait for human approval, then fire a webhook to the target system.",
                        "rocket",
                        List.of(
                                new WorkflowTemplateStep(1, "Prepare Payload", "Build and validate the deployment artifact or API payload.", "AGENT"),
                                new WorkflowTemplateStep(2, "Human Approval", "Pause for human review and explicit approval before proceeding.", "AGENT"),
                                new WorkflowTemplateStep(3, "Fire Webhook", "POST the approved payload to the target deployment endpoint.", "WEBHOOK")
                        )
                ),
                new WorkflowTemplate(
                        "custom",
                        "Custom (Blank)",
                        "Start from scratch and define your own workflow steps.",
                        "settings",
                        List.of()
                )
        );
    }
}
