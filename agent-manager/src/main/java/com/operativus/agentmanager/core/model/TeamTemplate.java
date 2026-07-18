package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Pre-defined team templates that auto-populate orchestration mode, member slots,
 * and sensible defaults. Administrators select a template when creating a team,
 * then assign their own agents to each member slot.
 *
 * <p>Convention: Templates are code-defined, not DB entities. Adding a new
 * template requires a code change — this is intentional to prevent
 * configuration sprawl of templates themselves.</p>
 */
public record TeamTemplate(
        String id,
        String name,
        String description,
        String icon,
        String teamMode,
        String instructions,
        boolean memoryEnabled,
        boolean addHistoryToMessages,
        Integer contextWindowSize,
        List<TeamTemplateMember> members
) {

    /**
     * A placeholder member slot within a team template. The agentId is null —
     * the admin assigns their own agent to each slot after selecting the template.
     */
    public record TeamTemplateMember(
            String role,
            String label,
            String description
    ) {}

    /**
     * Built-in team templates. Add new templates here.
     */
    public static List<TeamTemplate> builtInTemplates() {
        return List.of(
                new TeamTemplate(
                        "support_router",
                        "Customer Support Router",
                        "Route incoming requests to the best-fit specialist agent based on intent classification.",
                        "headphones",
                        "ROUTER",
                        "You are a support routing orchestrator. Analyze each incoming request and route it to the most appropriate specialist based on the topic, urgency, and required expertise. Always explain your routing decision briefly.",
                        false,
                        true,
                        null,
                        List.of(
                                new TeamTemplateMember("MEMBER", "Billing Specialist", "Handles payment, invoicing, and subscription issues"),
                                new TeamTemplateMember("MEMBER", "Technical Support", "Handles product bugs, errors, and technical troubleshooting"),
                                new TeamTemplateMember("MEMBER", "Sales & Onboarding", "Handles new customer inquiries, demos, and upgrades"),
                                new TeamTemplateMember("MEMBER", "General Inquiries", "Handles everything that doesn't fit a specialist category")
                        )
                ),
                new TeamTemplate(
                        "content_pipeline",
                        "Content Pipeline",
                        "A sequential chain where each agent refines the output of the previous one: draft, review, then finalize.",
                        "file-text",
                        "SEQUENTIAL",
                        "Process the user's content request through each stage in order. The first agent drafts, the second reviews for quality and accuracy, and the third applies edits to produce the final version.",
                        false,
                        true,
                        null,
                        List.of(
                                new TeamTemplateMember("MEMBER", "Drafter", "Generates the initial content based on the brief"),
                                new TeamTemplateMember("MEMBER", "Reviewer", "Reviews for accuracy, tone, completeness, and suggests improvements"),
                                new TeamTemplateMember("MEMBER", "Editor", "Applies review feedback and produces the polished final output")
                        )
                ),
                new TeamTemplate(
                        "task_force",
                        "Task Force",
                        "An autonomous swarm that decomposes complex problems into subtasks and distributes them to specialized workers.",
                        "zap",
                        "SWARM",
                        "You are a task force orchestrator. Break down the user's complex request into discrete subtasks. Assign each subtask to the most capable worker based on their specialization. Synthesize all worker outputs into a cohesive final response.",
                        true,
                        true,
                        null,
                        List.of(
                                new TeamTemplateMember("MEMBER", "Research Worker", "Gathers information, searches knowledge bases, and finds supporting data"),
                                new TeamTemplateMember("MEMBER", "Analysis Worker", "Processes data, identifies patterns, and draws conclusions"),
                                new TeamTemplateMember("MEMBER", "Writing Worker", "Drafts clear, structured prose from raw findings"),
                                new TeamTemplateMember("MEMBER", "Validation Worker", "Fact-checks outputs and verifies logical consistency")
                        )
                ),
                new TeamTemplate(
                        "project_manager",
                        "Project Manager",
                        "An LLM planner decomposes the request into an execution plan, assigns steps to specialists, then synthesizes the results.",
                        "clipboard-list",
                        "PLANNER",
                        "Decompose the user's request into a clear execution plan. Each step should be assigned to the most appropriate specialist. After all steps complete, synthesize the outputs into a cohesive final deliverable.",
                        true,
                        true,
                        null,
                        List.of(
                                new TeamTemplateMember("MEMBER", "Researcher", "Gathers background information and context for the task"),
                                new TeamTemplateMember("MEMBER", "Analyst", "Processes data and produces structured analysis"),
                                new TeamTemplateMember("MEMBER", "Executor", "Performs concrete actions like writing, coding, or formatting"),
                                new TeamTemplateMember("MEMBER", "QA Reviewer", "Validates outputs for correctness and completeness")
                        )
                ),
                new TeamTemplate(
                        "executive_team",
                        "Executive Team",
                        "A coordinator agent autonomously delegates tasks to department heads using the delegate_to_agent tool.",
                        "crown",
                        "COORDINATOR",
                        "You are the team coordinator. Analyze the user's request and delegate tasks to your team members using the delegate_to_agent tool. Each member has a specific domain of expertise. Synthesize their responses into a unified executive summary.",
                        true,
                        true,
                        null,
                        List.of(
                                new TeamTemplateMember("LEADER", "Coordinator", "Oversees the team, delegates tasks, and synthesizes final output"),
                                new TeamTemplateMember("MEMBER", "Finance Lead", "Handles financial analysis, budgeting, and cost projections"),
                                new TeamTemplateMember("MEMBER", "Operations Lead", "Handles logistics, process optimization, and resource planning"),
                                new TeamTemplateMember("MEMBER", "Engineering Lead", "Handles technical design, implementation strategy, and architecture")
                        )
                ),
                new TeamTemplate(
                        "custom",
                        "Custom (Blank)",
                        "Start from scratch with full control over team configuration and member assignments.",
                        "settings",
                        null,
                        null,
                        false,
                        false,
                        null,
                        List.of()
                )
        );
    }
}
