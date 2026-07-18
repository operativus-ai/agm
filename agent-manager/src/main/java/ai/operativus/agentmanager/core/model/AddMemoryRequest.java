package ai.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for {@code POST /memories}
 *   (FE-side: {@code memoryApi.addMemory}). Carries the raw memory content the user
 *   wants persisted into their semantic Memory store. The handler forwards the content
 *   directly to {@code MemoryService.addMemory} without further parsing.
 * State: Immutable record.
 */
public record AddMemoryRequest(
        @NotBlank String content
) {
}
