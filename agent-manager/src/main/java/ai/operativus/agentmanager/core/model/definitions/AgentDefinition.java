package ai.operativus.agentmanager.core.model.definitions;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Domain Responsibility: Represents the universal schema for an Agent or Team, mapping directly to Requirement 3.7.1 (Agent Definition Schema). Used to populate the /config endpoint for the UI and drive orchestration logic.
 * State: Stateless (Immutable Record)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentDefinition(
    @NotBlank(message = "Agent ID is required") @JsonProperty("agentId") String id,
    @NotBlank(message = "Name is required") String name,
    @NotBlank(message = "Description is required") String description,
    @NotBlank(message = "Instructions are required") String instructions,
    @NotBlank(message = "Model ID is required") @JsonProperty("model") String modelId,
    Integer contextWindowSize,
    Boolean memoryEnabled,
    Boolean addHistoryToMessages,
    @JsonProperty("tools") List<String> tools,
    @JsonProperty("isReasoningEnabled") boolean monitoringEnabled,
    @JsonProperty("isTeam") boolean isTeam,
    String teamMode,
    List<String> members,
    List<String> allowedRoles,
    boolean requiresPiiRedaction,
    boolean approvedForProduction,
    boolean maintenanceMode,
    boolean active,
    java.util.Map<String, Object> configuration,
    String markdownDocs,
    String supportChannel,
    String primaryOwner,
    List<String> supportedLocales,
    String accessibilityCompatibility,
    List<String> trainingDatasets,
    List<String> knowledgeBaseIds,
    boolean enforceJsonOutput,
    Double temperature,
    Double topP,
    Double frequencyPenalty,
    String systemPromptMode,
    Integer maxConcurrentExecutions,
    Long finOpsTokenBudget,
    ai.operativus.agentmanager.core.entity.FinOpsRiskTier finOpsRiskTier,
    Integer securityTier,
    ai.operativus.agentmanager.core.entity.ComplianceTier complianceTier,
    Integer compressionThreshold,
    Integer summarizationThreshold,
    String optimizationModelId,
    List<String> preHooks,
    List<String> postHooks,
    /** §9 MEM-2: only meaningful for team agents. When {@code true}, orchestrators derive a
     *  per-member conversationId so each member's chat-memory advisor keeps its own bucket.
     *  When {@code false}/{@code null} (default), every member shares the team's session
     *  memory and sees the running cross-member transcript. Has no effect on single agents
     *  or on team modes that already isolate per-branch (Broadcast, Swarm). */
    Boolean isolateMemory,
    /** Ordered fallback model IDs tried when the primary model is unavailable (provider
     *  disabled at config time) or returns a rate-limit / quota error at call time.
     *  Example: {@code ["gpt-4o", "gemini-2.5-flash"]}. Null/empty means no per-agent
     *  fallback beyond the global active-provider fallback in {@code AgentClientFactory}. */
    List<String> fallbackModelIds,
    /** REQ-HR follow-up — unified human-review config attached at agent or team-member
     *  level. For a single-agent definition this surfaces the agent's own
     *  {@code human_review} JSONB. For a team-member dispatch path, orchestrators
     *  resolve per-member overrides via {@link #withHumanReview(ai.operativus.agentmanager.core.model.HumanReview)}.
     *  Null means no review is required — orchestrators must treat null as a no-op gate. */
    ai.operativus.agentmanager.core.model.HumanReview humanReview,
    /** DR-FR-7 capability vocabulary. Short skill labels (e.g. {@code ["tax-questions",
     *  "refund-disputes"]}) that feed the universal-dispatch LLM classifier prompt and the
     *  semantic scorer's embedding text. Null/empty means no capabilities advertised. */
    @JsonProperty("capabilities") List<String> capabilities
) {
    /** Returns a copy of this definition with {@code modelId} replaced — used by
     *  {@code AgentClientFactory.buildChatClientForFallback} to build a ChatClient
     *  for a specific fallback model without altering the original definition. */
    public AgentDefinition withModelId(String newModelId) {
        return new AgentDefinition(id, name, description, instructions, newModelId,
            contextWindowSize, memoryEnabled, addHistoryToMessages, tools,
            monitoringEnabled, isTeam, teamMode, members, allowedRoles,
            requiresPiiRedaction, approvedForProduction, maintenanceMode, active,
            configuration, markdownDocs, supportChannel, primaryOwner,
            supportedLocales, accessibilityCompatibility, trainingDatasets,
            knowledgeBaseIds, enforceJsonOutput, temperature, topP,
            frequencyPenalty, systemPromptMode, maxConcurrentExecutions,
            finOpsTokenBudget, finOpsRiskTier, securityTier, complianceTier,
            compressionThreshold, summarizationThreshold, optimizationModelId,
            preHooks, postHooks, isolateMemory, fallbackModelIds, humanReview,
            capabilities);
    }

    /** Returns a copy of this definition with {@code humanReview} replaced — used by
     *  team orchestrators to overlay a {@code TeamMember}-level human-review override
     *  on top of the underlying agent's definition when dispatching a member. */
    public AgentDefinition withHumanReview(ai.operativus.agentmanager.core.model.HumanReview newHumanReview) {
        return new AgentDefinition(id, name, description, instructions, modelId,
            contextWindowSize, memoryEnabled, addHistoryToMessages, tools,
            monitoringEnabled, isTeam, teamMode, members, allowedRoles,
            requiresPiiRedaction, approvedForProduction, maintenanceMode, active,
            configuration, markdownDocs, supportChannel, primaryOwner,
            supportedLocales, accessibilityCompatibility, trainingDatasets,
            knowledgeBaseIds, enforceJsonOutput, temperature, topP,
            frequencyPenalty, systemPromptMode, maxConcurrentExecutions,
            finOpsTokenBudget, finOpsRiskTier, securityTier, complianceTier,
            compressionThreshold, summarizationThreshold, optimizationModelId,
            preHooks, postHooks, isolateMemory, fallbackModelIds, newHumanReview,
            capabilities);
    }
}
