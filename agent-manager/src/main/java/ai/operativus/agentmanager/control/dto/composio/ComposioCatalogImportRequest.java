package ai.operativus.agentmanager.control.dto.composio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for
 *   {@code POST /api/admin/composio/catalog/import}. Bulk-imports every action
 *   under {@code app} from the upstream Composio catalog into the
 *   {@code composio_action_config} allowlist. The {@code app} is the only
 *   required field — pass e.g. {@code "github"}, {@code "slack"}, {@code "notion"}.
 *
 *   <p>{@code overwriteExisting} controls collision handling:
 *   <ul>
 *     <li>{@code false} (default) — rows that already exist are SKIPPED, even
 *         if their {@code enabled} flag differs from the import default. Safe
 *         re-runs.</li>
 *     <li>{@code true} — existing rows are FLIPPED to {@code enabled=true} so
 *         the import re-establishes the upstream catalog as the source of truth.
 *         Operator-disabled rows lose their disable state.</li>
 *   </ul>
 *
 *   <p>{@code defaultTier} matches {@code ComposioActionConfig.tier}:
 *   {@code 1} (auto-execute, read-only), {@code 2} (HITL-gated, default),
 *   {@code 3} (destructive). Composio's catalog has no notion of AGM's HITL
 *   tiers; the operator picks a sensible default for the bulk import (typically
 *   {@code 2}). Per-row tier can be retuned afterwards through the existing
 *   {@code PUT /api/admin/composio/actions/{id}} surface. Null defaults to
 *   tier 2 (HITL-gated) — the safe-by-default position.
 *
 * State: Stateless (Immutable Record carrier)
 */
public record ComposioCatalogImportRequest(
        @NotBlank String app,
        Boolean overwriteExisting,
        @Min(1) @Max(3) Integer defaultTier
) {}
