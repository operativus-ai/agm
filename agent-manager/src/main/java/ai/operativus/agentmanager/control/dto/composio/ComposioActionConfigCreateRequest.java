package ai.operativus.agentmanager.control.dto.composio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/admin/composio/actions}. The DB id and
 * {@code llmToolName} are derived server-side from {@code actionName} so two
 * rows can never differ only by ID case.
 *
 * @param actionName canonical action identifier (e.g. {@code GMAIL_SEND_EMAIL}).
 *                   Service uppercases on receipt; UNIQUE in {@code composio_action_config}.
 * @param tier       1 (auto-execute, read-only), 2 (HITL-gated, default), or 3 (destructive).
 * @param enabled    whether the action is registered with the LLM. Default {@code true}.
 */
public record ComposioActionConfigCreateRequest(
        @NotBlank String actionName,
        @Min(1) @Max(3) int tier,
        boolean enabled) {
}
