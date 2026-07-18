package ai.operativus.agentmanager.control.security;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.MetadataKeys;
import ai.operativus.agentmanager.core.model.enums.RoleType;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Centralized Security Filter Factory for Procurator Knowledge Domain.
 * Ensures strict multi-tenant Row-Level Access Controls (RLAC) across all RAG pipeline vectors.
 */
public class AgentSecurityFilters {

    /**
     * Constructs a FilterExpression to bound the semantic vector search.
     *
     * <p>Returns {@code null} (unbounded) only for ROOT/SUPER_ADMIN authentication. Otherwise:
     * <ul>
     *   <li>Always AND-combines the {@code orgId} equality filter.</li>
     *   <li>Adds an {@code IN(knowledge_base_id, …)} filter when
     *       {@link AgentContextHolder#getAllowedKnowledgeBaseIds()} is non-empty. The
     *       {@code allowedKnowledgeBaseIds} ScopedValue is bound by
     *       {@code AgentService.run} from {@code def.knowledgeBaseIds()}, so the agent's
     *       configured KB allowlist is honored at retrieval — previously the binding was
     *       set but never consumed, leaving an agent assigned to KB-A able to retrieve
     *       content from KB-B in the same org.</li>
     *   <li>When the ScopedValue is unbound or empty (system-background callers, agents
     *       configured with zero KBs), the org-only filter is returned. The "zero KBs"
     *       case explicitly means "any KB in the org" — opting INTO no allowlist is the
     *       same as opting OUT of the feature.</li>
     * </ul>
     *
     * @return Filter.Expression for orgId+KB bounding, or null if universal access is granted.
     */
    public static Expression buildVectorFilter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        boolean isSuperAdmin = false;
        if (auth != null && auth.getAuthorities() != null) {
            isSuperAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(RoleType.ROLE_SUPER_ADMIN.getValue()));
        }

        if (isSuperAdmin) {
            // Unbounded access for global tenant administrators
            return null;
        }

        String orgId = AgentContextHolder.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalStateException("Critical Security Exception: Active orgId is null on the AgentContextHolder during semantic vector search. Denying query.");
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op orgFilter = b.eq(MetadataKeys.ORG_ID, orgId);
        // AND storeType=KB so that knowledge-base searches don't pull memory rows out of
        // the shared vector_store. Pre-fix, memory entries written by MemoryService
        // (carrying only orgId in metadata) surfaced as same-org KB hits.
        FilterExpressionBuilder.Op storeFilter = b.eq(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_KB);

        List<String> allowedKbs = AgentContextHolder.getAllowedKnowledgeBaseIds();
        if (allowedKbs == null || allowedKbs.isEmpty()) {
            return b.and(orgFilter, storeFilter).build();
        }
        // K4 production fix: AND the agent's KB allowlist so retrieval is scoped to the
        // assigned KBs. Without this, cross-KB leakage within an org was possible.
        FilterExpressionBuilder.Op kbFilter = b.in(MetadataKeys.KNOWLEDGE_BASE_ID, allowedKbs.toArray());
        return b.and(b.and(orgFilter, storeFilter), kbFilter).build();
    }
}
