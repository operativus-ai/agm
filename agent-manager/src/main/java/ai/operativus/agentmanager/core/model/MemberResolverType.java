package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Strategy discriminator for resolving a team's member roster
 *     at run time. Stored as a string in {@code agents.member_resolver_type} so a row
 *     opts a team into a non-static resolution strategy without changing the static
 *     {@code members} JSON list shape.
 *
 *     <ul>
 *       <li>{@link #STATIC} — production default. Returns the {@code agents.members}
 *           JSON list verbatim. Backwards-compatible with every pre-REQ-DR-2 row.</li>
 *       <li>{@link #ORG_TIER} — caller's org tier filters the static list. Stub in
 *           v1; real filter lives behind a follow-up PR once org-tier metadata is
 *           wired through the request context.</li>
 *       <li>{@link #FEATURE_FLAG} — feature flags toggle individual members in/out.
 *           Stub in v1 for the same reason.</li>
 *     </ul>
 * State: Stateless (Enum)
 */
public enum MemberResolverType {
    STATIC,
    ORG_TIER,
    FEATURE_FLAG
}
