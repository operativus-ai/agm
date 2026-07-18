package com.operativus.agentmanager.core.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Domain Responsibility: Acts as an immutable data transfer object representing a multi-agent team configuration including its routing mode and leader designation.
 * State: Stateless (Immutable Record carrier)
 */
public record TeamDTO(
        String id,
        String name,
        String description,
        String teamMode,
        String leaderId,
        String modelId,
        String instructions,
        Integer contextWindowSize,
        Boolean memoryEnabled,
        Boolean addHistoryToMessages,
        /** §9 MEM-2: when true, orchestrators derive a per-member conversationId so each member's
         *  chat-memory advisor keeps its own bucket. False/null = members share the team session
         *  memory (default behaviour). Field added by `docs/plans/agm-clear-out.md` T006 to close
         *  the controller-side wire shape gap. */
        Boolean isolateMemory,
        List<String> tools,
        String humanLead,
        Double maxDailySpend,
        Double minSpendingAuthority,
        Boolean archived,
        // Enriched list fields (populated by list queries, null on single-entity fetches)
        Integer memberCount,
        Integer activeMemberCount,
        String leaderAgentName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
