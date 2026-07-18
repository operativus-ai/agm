package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.control.repository.AgentCredentialRepository;
import ai.operativus.agentmanager.core.entity.AgentCredential;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentIdentityServiceTest {

    @Mock
    private AgentCredentialRepository credentialRepository;

    @InjectMocks
    private AgentIdentityService service;

    private AgentCredential apiKeyCredential;
    private AgentCredential oauth2Credential;

    @BeforeEach
    void setUp() {
        apiKeyCredential = new AgentCredential();
        apiKeyCredential.setId("cred-1");
        apiKeyCredential.setAgentId("agent-1");
        apiKeyCredential.setCredentialType("API_KEY");
        apiKeyCredential.setProviderName("stripe");
        apiKeyCredential.setEncryptedSecret("sk-test-123");
        apiKeyCredential.setScopes("read,write");
        apiKeyCredential.setEnabled(true);

        oauth2Credential = new AgentCredential();
        oauth2Credential.setId("cred-2");
        oauth2Credential.setAgentId("agent-1");
        oauth2Credential.setCredentialType("OAUTH2");
        oauth2Credential.setProviderName("github");
        oauth2Credential.setEncryptedSecret("client-secret");
        oauth2Credential.setClientId("client-id");
        oauth2Credential.setTokenEndpoint("https://github.com/login/oauth/access_token");
        oauth2Credential.setEnabled(true);
    }

    @Test
    void resolveCredential_ReturnsEnabledCredential() {
        when(credentialRepository.findByAgentIdAndProviderName("agent-1", "stripe"))
                .thenReturn(List.of(apiKeyCredential));

        Optional<AgentCredential> result = service.resolveCredential("agent-1", "stripe");

        assertTrue(result.isPresent());
        assertEquals("cred-1", result.get().getId());
    }

    @Test
    void resolveCredential_SkipsDisabledCredentials() {
        apiKeyCredential.setEnabled(false);
        when(credentialRepository.findByAgentIdAndProviderName("agent-1", "stripe"))
                .thenReturn(List.of(apiKeyCredential));

        Optional<AgentCredential> result = service.resolveCredential("agent-1", "stripe");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveAllCredentials_ReturnsByProvider() {
        when(credentialRepository.findByAgentIdAndEnabledTrue("agent-1"))
                .thenReturn(List.of(apiKeyCredential, oauth2Credential));

        Map<String, AgentCredential> result = service.resolveAllCredentials("agent-1");

        assertEquals(2, result.size());
        assertTrue(result.containsKey("stripe"));
        assertTrue(result.containsKey("github"));
    }

    @Test
    void mintToken_ApiKey_ReturnsSecretDirectly() {
        String token = service.mintToken(apiKeyCredential);

        assertEquals("sk-test-123", token);
    }

    @Test
    void mintToken_Bearer_ReturnsSecretDirectly() {
        apiKeyCredential.setCredentialType("BEARER");
        apiKeyCredential.setEncryptedSecret("bearer-token-xyz");

        String token = service.mintToken(apiKeyCredential);

        assertEquals("bearer-token-xyz", token);
    }

    @Test
    void mintToken_CachesToken() {
        // First call
        String token1 = service.mintToken(apiKeyCredential);
        // Second call should use cache (no additional repo calls needed)
        String token2 = service.mintToken(apiKeyCredential);

        assertEquals(token1, token2);
    }

    @Test
    void mintToken_OAuth2_FailsWithoutTokenEndpoint() {
        oauth2Credential.setTokenEndpoint(null);

        assertThrows(BusinessValidationException.class, () -> service.mintToken(oauth2Credential));
    }

    @Test
    void mintToken_OAuth2_FailsWithoutClientId() {
        oauth2Credential.setClientId(null);

        assertThrows(BusinessValidationException.class, () -> service.mintToken(oauth2Credential));
    }

    // ─── SSRF guard at mint time (runtime backstop for credentials persisted before
    //     the write-time guard, or any path that bypasses the service) ─────────────

    @Test
    void mintToken_OAuth2_RejectsCloudMetadataTokenEndpoint() {
        // AWS IMDS — highest-blast-radius SSRF target. The OAuth2 POST would send
        // client_id+client_secret in the body to the metadata IP otherwise.
        oauth2Credential.setTokenEndpoint("http://169.254.169.254/latest/meta-data/iam/security-credentials/role");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.mintToken(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"),
                "rejection message must surface SSRF guard reason; got: " + ex.getMessage());
    }

    @Test
    void mintToken_OAuth2_RejectsLoopbackTokenEndpoint() {
        oauth2Credential.setTokenEndpoint("http://127.0.0.1:8080/internal/oauth/token");

        // Assert the SSRF guard message — without that check, a vanilla connection-refused
        // on 127.0.0.1:8080 would ALSO raise BusinessValidationException and the test
        // would pass even if the guard were stripped (false negative).
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.mintToken(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"),
                "expected SSRF rejection, got: " + ex.getMessage());
    }

    @Test
    void mintToken_OAuth2_RejectsDecimalEncodedLoopbackTokenEndpoint() {
        // 2130706433 == 127.0.0.1. JDK HttpClient accepts this form; URI.getHost
        // returns the literal "2130706433"; native string checks would miss it.
        oauth2Credential.setTokenEndpoint("http://2130706433/oauth/token");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.mintToken(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"),
                "expected SSRF rejection (not a vanilla connection failure), got: " + ex.getMessage());
    }

    @Test
    void mintToken_OAuth2_RejectsRfc1918TokenEndpoint() {
        oauth2Credential.setTokenEndpoint("http://10.0.0.5:8080/oauth/token");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.mintToken(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"),
                "expected SSRF rejection, got: " + ex.getMessage());
    }

    @Test
    void mintToken_OAuth2_RejectsNonHttpScheme() {
        oauth2Credential.setTokenEndpoint("file:///etc/passwd");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.mintToken(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"),
                "expected SSRF rejection, got: " + ex.getMessage());
    }

    // ─── Write-time SSRF guard on createCredential / updateCredential ─────────────

    @Test
    void createCredential_OAuth2_RejectsSsrfTokenEndpoint() {
        oauth2Credential.setTokenEndpoint("http://169.254.169.254/latest/meta-data/");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.createCredential(oauth2Credential));
        assertTrue(ex.getMessage().contains("SSRF"));
        // Critical: the bad URL must NEVER reach the repository — admission failed.
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void createCredential_NonOauth2_NullTokenEndpoint_DoesNotInvokeSsrfGuard() {
        // API_KEY type has no token endpoint by design; guard must early-exit for null
        // rather than throw "URL is required".
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentCredential result = service.createCredential(apiKeyCredential);

        assertNotNull(result);
        verify(credentialRepository).save(any());
    }

    @Test
    void updateCredential_OAuth2_RejectsSsrfTokenEndpoint() {
        when(credentialRepository.findByIdAndAgentId("cred-2", "agent-1"))
                .thenReturn(Optional.of(oauth2Credential));

        AgentCredential update = new AgentCredential();
        update.setCredentialType("OAUTH2");
        update.setProviderName("evil");
        update.setClientId("c");
        update.setEncryptedSecret("s");
        update.setTokenEndpoint("http://localhost:8080/api/admin/internal");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.updateCredential("cred-2", "agent-1", update));
        assertTrue(ex.getMessage().contains("SSRF"));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void mintToken_UnsupportedType_ThrowsException() {
        apiKeyCredential.setCredentialType("SAML");

        assertThrows(BusinessValidationException.class, () -> service.mintToken(apiKeyCredential));
    }

    @Test
    void validateScope_AllScopesGranted() {
        assertTrue(service.validateScope(apiKeyCredential, "read"));
        assertTrue(service.validateScope(apiKeyCredential, "write"));
    }

    @Test
    void validateScope_ScopeNotGranted() {
        assertFalse(service.validateScope(apiKeyCredential, "admin"));
    }

    @Test
    void validateScope_NullScopes_AllowsEverything() {
        apiKeyCredential.setScopes(null);

        assertTrue(service.validateScope(apiKeyCredential, "anything"));
    }

    @Test
    void validateScope_EmptyScopes_AllowsEverything() {
        apiKeyCredential.setScopes("");

        assertTrue(service.validateScope(apiKeyCredential, "anything"));
    }

    @Test
    void evictCachedToken_RemovesCachedEntry() {
        // Prime the cache
        service.mintToken(apiKeyCredential);
        // Evict
        service.evictCachedToken("cred-1");
        // Next mint should work fresh (no exception = success)
        String token = service.mintToken(apiKeyCredential);
        assertEquals("sk-test-123", token);
    }

    @Test
    void createCredential_GeneratesIdIfMissing() {
        apiKeyCredential.setId(null);
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentCredential result = service.createCredential(apiKeyCredential);

        assertNotNull(result.getId());
        assertFalse(result.getId().isBlank());
        verify(credentialRepository).save(any());
    }

    @Test
    void createCredential_PreservesExistingId() {
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentCredential result = service.createCredential(apiKeyCredential);

        assertEquals("cred-1", result.getId());
    }

    @Test
    void updateCredential_UpdatesFields() {
        when(credentialRepository.findByIdAndAgentId("cred-1", "agent-1")).thenReturn(Optional.of(apiKeyCredential));
        when(credentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentCredential update = new AgentCredential();
        update.setCredentialType("BEARER");
        update.setProviderName("slack");
        update.setEncryptedSecret("new-secret");
        update.setScopes("channels:read");
        update.setEnabled(false);

        AgentCredential result = service.updateCredential("cred-1", "agent-1", update);

        assertEquals("BEARER", result.getCredentialType());
        assertEquals("slack", result.getProviderName());
        assertEquals("new-secret", result.getEncryptedSecret());
        assertFalse(result.isEnabled());
    }

    @Test
    void deleteCredential_DeletesExisting() {
        when(credentialRepository.findByIdAndAgentId("cred-1", "agent-1")).thenReturn(Optional.of(apiKeyCredential));

        service.deleteCredential("cred-1", "agent-1");

        verify(credentialRepository).deleteById("cred-1");
    }

    @Test
    void deleteCredential_ThrowsIfNotFound() {
        when(credentialRepository.findByIdAndAgentId("missing", "agent-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deleteCredential("missing", "agent-1"));
    }

    @Test
    void getCredential_ThrowsIfNotFound() {
        when(credentialRepository.findByIdAndAgentId("missing", "agent-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getCredential("missing", "agent-1"));
    }

    @Test
    void getCredentials_DelegatesToRepository() {
        when(credentialRepository.findByAgentId("agent-1")).thenReturn(List.of(apiKeyCredential));

        List<AgentCredential> result = service.getCredentials("agent-1");

        assertEquals(1, result.size());
        assertEquals("cred-1", result.get(0).getId());
    }
}
