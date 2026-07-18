package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Domain Responsibility: Wire-format DTO for a single Composio catalog entry,
 *     fetched from the upstream Composio API. Carries the minimal fields the
 *     admin UI / bulk-import path needs (name, app, display name, description,
 *     deprecated flag) — Composio returns far more per action (schemas, examples,
 *     etc.), but those are not needed at discovery time and would bloat the
 *     in-memory response.
 *
 *     <p><strong>Tolerant parsing:</strong> Composio's API has historically used
 *     several field-name conventions across v2/v3 and across docs/SDK output —
 *     for example {@code name} vs {@code action} vs {@code key}, and
 *     {@code displayName} vs {@code display_name}. {@link JsonAlias} accepts
 *     each so a Composio API tweak doesn't immediately break parsing. Operators
 *     who upgrade their Composio plan and find an unexpected shape can override
 *     the catalog path via {@code agent.tools.composio.catalog-list-path}.
 *
 *     <p>Unknown fields are ignored ({@link JsonIgnoreProperties}) so future
 *     additions to the upstream payload don't fail deserialization.
 *
 * State: Stateless (Immutable Record carrier)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComposioCatalogAction(
        @JsonAlias({"name", "action", "key", "actionName"}) String name,
        @JsonAlias({"app", "appName", "appKey", "toolkit"}) String app,
        @JsonAlias({"displayName", "display_name", "display"}) String displayName,
        @JsonAlias({"description", "desc"}) String description,
        @JsonAlias({"deprecated", "isDeprecated"}) Boolean deprecated
) {
    /**
     * Convenience accessor — treats a missing {@code deprecated} flag as false.
     * The wire field is nullable so the record carries Composio's intent
     * literally; callers that don't care about the difference between "not
     * deprecated" and "no opinion" use this helper.
     */
    public boolean isDeprecated() {
        return Boolean.TRUE.equals(deprecated);
    }
}
