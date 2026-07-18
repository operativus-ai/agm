package com.operativus.agentmanager.compute.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import com.operativus.agentmanager.core.callback.AgentContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Gang of Four Decorator wrapping the primary {@link VectorStore} bean.
 * Intercepts all {@code add} operations to scan and scrub document content through the
 * {@link DeterministicNEREngine} before delegating to the inner store, ensuring zero raw PII
 * reaches persistent vector storage regardless of the calling context (controller, background
 * job, or inter-agent message handler).
 *
 * <p>Read operations ({@code similaritySearch}, {@code delete}) delegate directly to the inner
 * store without modification.</p>
 *
 * State: Stateless (delegates all state to the wrapped VectorStore and detection services)
 */
public class PiiRedactingVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactingVectorStore.class);

    private final VectorStore delegate;
    private final DeterministicNEREngine nerEngine;
    private final FormatPreservingEncryptionService fpeService;
    private final PiiPolicyService policyService;

    public PiiRedactingVectorStore(VectorStore delegate,
                                   DeterministicNEREngine nerEngine,
                                   FormatPreservingEncryptionService fpeService,
                                   PiiPolicyService policyService) {
        this.delegate = delegate;
        this.nerEngine = nerEngine;
        this.fpeService = fpeService;
        this.policyService = policyService;
    }

    /**
     * @summary Intercepts document additions to scrub PII before persistence.
     * @logic For each document, resolves the applicable PII policies (using the document's
     *        {@code agent_id} metadata if present, otherwise global policies), runs the NER
     *        scanner against the document content, and replaces the original content with
     *        the scrubbed version before delegating to the inner store.
     */
    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            delegate.add(documents);
            return;
        }

        // Tenant scoping: PII policies are per-tenant (changeset 101). Resolve from the
        // current execution context; if there's no tenant context (e.g. an ungated system
        // path), bypass scrubbing rather than guess which tenant's policies to apply.
        String orgId = AgentContextHolder.getOrgId();
        List<PiiPolicyEntity> tenantPolicies = (orgId != null && !orgId.isBlank())
                ? policyService.findPoliciesForAgent("__no_agent__", orgId)
                : List.of();
        List<Document> scrubbedDocuments = new ArrayList<>(documents.size());

        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isBlank()) {
                scrubbedDocuments.add(doc);
                continue;
            }

            // Resolve agent-specific policies if the document carries agent_id metadata
            List<PiiPolicyEntity> policies = tenantPolicies;
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata != null && metadata.containsKey("agent_id")
                    && orgId != null && !orgId.isBlank()) {
                String agentId = String.valueOf(metadata.get("agent_id"));
                policies = policyService.findPoliciesForAgent(agentId, orgId);
            }

            DeterministicNEREngine.ScrubResult result = nerEngine.scrub(content, policies, fpeService);

            if (!result.events().isEmpty()) {
                log.info("PiiRedactingVectorStore: scrubbed {} PII detections from document '{}'",
                        result.events().size(), doc.getId());

                // Create a new Document with scrubbed content preserving metadata and ID
                Document scrubbedDoc = new Document(doc.getId(), result.scrubbedText(), doc.getMetadata());
                scrubbedDocuments.add(scrubbedDoc);
            } else {
                scrubbedDocuments.add(doc);
            }
        }

        delegate.add(scrubbedDocuments);
    }

    /**
     * @summary Delegates delete operations directly to the inner store.
     */
    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    /**
     * @summary Delegates delete operations with filter directly to the inner store.
     */
    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    /**
     * @summary Delegates similarity search directly to the inner store.
     */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate.similaritySearch(request);
    }

    /**
     * @summary Returns the name of this VectorStore implementation.
     */
    @Override
    public String getName() {
        return "PiiRedactingVectorStore";
    }
}
