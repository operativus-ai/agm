package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Domain Responsibility: Validates the PiiRedactingVectorStore Decorator correctly intercepts
 * write operations and delegates scrubbed content to the inner VectorStore.
 *
 * <p>The store reads {@code AgentContextHolder.getOrgId()} (core.callback) to scope policy
 * lookups per tenant. Tests bind orgId via {@code SecurityContextHolder} → {@code UserDetailsImpl}
 * fallback (PR #927), which the no-arg holder reads as a fallback when no ScopedValue is
 * bound on the test thread.
 */
@ExtendWith(MockitoExtension.class)
class PiiRedactingVectorStoreTest {

    private static final String TEST_ORG = "test-org";

    @Mock
    private VectorStore mockDelegate;

    @Mock
    private PiiPolicyService mockPolicyService;

    private DeterministicNEREngine nerEngine;
    private FormatPreservingEncryptionService fpeService;
    private PiiRedactingVectorStore store;

    @BeforeEach
    void setUp() {
        nerEngine = new DeterministicNEREngine();
        fpeService = new FormatPreservingEncryptionService();
        store = new PiiRedactingVectorStore(mockDelegate, nerEngine, fpeService, mockPolicyService);
        UserDetailsImpl ud = new UserDetailsImpl(UUID.randomUUID(), "test-user",
                "test@test.local", TEST_ORG, false, "pwd", List.of());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(ud, null, List.of())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PiiPolicyEntity createPolicy(String name, PatternType type, String pattern, ScrubStrategy strategy) {
        return new PiiPolicyEntity(UUID.randomUUID(), TEST_ORG, name, "Test", type, pattern, strategy, true, null, null);
    }

    @Nested
    @DisplayName("add()")
    class AddTests {

        @Test
        @DisplayName("should scrub PII from document content before delegating to inner store")
        void scrubsPiiBeforeDelegation() {
            PiiPolicyEntity ssnPolicy = createPolicy("US_SSN",
                    PatternType.REGEX, "\\b\\d{3}-\\d{2}-\\d{4}\\b", ScrubStrategy.FPE);
            when(mockPolicyService.findPoliciesForAgent(any(), eq(TEST_ORG))).thenReturn(List.of(ssnPolicy));

            Document doc = new Document("doc-1", "User SSN is 123-45-6789", Map.of());

            store.add(List.of(doc));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockDelegate).add(captor.capture());

            List<Document> captured = captor.getValue();
            assertEquals(1, captured.size());
            assertFalse(captured.getFirst().getText().contains("123-45-6789"),
                    "SSN should be scrubbed from content passed to the inner store");
            assertEquals("doc-1", captured.getFirst().getId(),
                    "Document ID must be preserved");
        }

        @Test
        @DisplayName("should preserve documents with no PII detections")
        void preservesCleanDocuments() {
            when(mockPolicyService.findPoliciesForAgent(any(), eq(TEST_ORG))).thenReturn(List.of());

            Document doc = new Document("clean-1", "No sensitive data here", Map.of());

            store.add(List.of(doc));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockDelegate).add(captor.capture());

            assertEquals("No sensitive data here", captor.getValue().getFirst().getText());
        }

        @Test
        @DisplayName("should use agent-specific policies when agent_id metadata is present")
        void usesAgentSpecificPolicies() {
            PiiPolicyEntity emailPolicy = createPolicy("EMAIL",
                    PatternType.REGEX, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}", ScrubStrategy.REDACT);
            when(mockPolicyService.findPoliciesForAgent("test-agent", TEST_ORG)).thenReturn(List.of(emailPolicy));
            when(mockPolicyService.findPoliciesForAgent("__no_agent__", TEST_ORG)).thenReturn(List.of());

            Document doc = new Document("agent-doc", "Email: user@test.com",
                    Map.of("agent_id", "test-agent"));

            store.add(List.of(doc));

            verify(mockPolicyService).findPoliciesForAgent("test-agent", TEST_ORG);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockDelegate).add(captor.capture());

            assertFalse(captor.getValue().getFirst().getText().contains("user@test.com"),
                    "Email should be scrubbed using agent-specific policy");
        }

        @Test
        @DisplayName("should handle null document list gracefully")
        void handlesNullDocuments() {
            store.add(null);
            verify(mockDelegate).add(null);
        }

        @Test
        @DisplayName("should handle empty document list")
        void handlesEmptyDocuments() {
            store.add(List.of());
            verify(mockDelegate).add(List.of());
        }

        @Test
        @DisplayName("should skip documents with null or blank content")
        void skipsBlankContent() {
            when(mockPolicyService.findPoliciesForAgent(any(), eq(TEST_ORG))).thenReturn(List.of());
            Document blankDoc = new Document("blank-1", "", Map.of());

            store.add(List.of(blankDoc));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockDelegate).add(captor.capture());

            assertEquals("", captor.getValue().getFirst().getText());
        }

        @Test
        @DisplayName("when no orgId is resolvable, bypass scrubbing entirely (no policy lookup)")
        void bypassesScrubWhenNoTenantContext() {
            SecurityContextHolder.clearContext();
            Document doc = new Document("doc-no-org", "User SSN is 123-45-6789", Map.of());

            store.add(List.of(doc));

            verify(mockPolicyService, never()).findPoliciesForAgent(any(), any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(mockDelegate).add(captor.capture());
            assertEquals("User SSN is 123-45-6789", captor.getValue().getFirst().getText(),
                    "no tenant context → no scrub → original text reaches the inner store");
        }
    }

    @Nested
    @DisplayName("Delegation methods")
    class DelegationTests {

        @Test
        @DisplayName("delete should delegate directly to inner store")
        void deleteByIdsDelegates() {
            store.delete(List.of("id-1", "id-2"));
            verify(mockDelegate).delete(List.of("id-1", "id-2"));
        }

        @Test
        @DisplayName("similaritySearch should delegate directly to inner store")
        void similaritySearchDelegates() {
            SearchRequest request = SearchRequest.builder().build();
            store.similaritySearch(request);
            verify(mockDelegate).similaritySearch(request);
        }

        @Test
        @DisplayName("getName should return PiiRedactingVectorStore")
        void nameReturnsCorrectValue() {
            assertEquals("PiiRedactingVectorStore", store.getName());
        }
    }
}
