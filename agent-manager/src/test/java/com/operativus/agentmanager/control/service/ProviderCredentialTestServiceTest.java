package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.dto.ProviderCredentialTestResponse;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.ModelRequest;
import com.operativus.agentmanager.core.registry.ModelOperations;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pin the key-resolution + result-mapping contract of
 * {@link ProviderCredentialTestService}. Verifies the supplied key wins over the stored
 * key, that a blank key falls back to the stored one, that a missing key fails soft
 * (never probes), that Ollama probes without a key, and that a provider exception maps to
 * a {@code success=false} result rather than propagating.
 *
 * State: Stateless — pure Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ProviderCredentialTestServiceTest {

    private static final String ORG = "org-1";

    @Mock private ModelOperations modelOps;
    @Mock private ProviderCredentialOperations credentials;

    private ProviderCredentialTestService service() {
        return new ProviderCredentialTestService(modelOps, credentials);
    }

    @Test
    void suppliedKeyWinsAndProbesThatKey() {
        ProviderCredentialTestResponse res =
                service().test(ORG, "OPENAI", "sk-typed-key", "gpt-4o-mini");

        assertTrue(res.success(), "a clean probe must report success");
        assertEquals("OPENAI", res.provider());
        assertEquals("gpt-4o-mini", res.model());

        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelOps).testConnection(captor.capture());
        assertEquals("sk-typed-key", captor.getValue().apiKey(), "typed key must be the one probed");
        assertEquals("gpt-4o-mini", captor.getValue().modelName());
        // Typed key present => the stored key must never be looked up.
        verifyNoInteractions(credentials);
    }

    @Test
    void blankKeyFallsBackToStoredKey() {
        when(credentials.resolveDefaultKey(ORG, "ANTHROPIC")).thenReturn(Optional.of("sk-stored-key"));

        ProviderCredentialTestResponse res =
                service().test(ORG, "ANTHROPIC", "  ", "claude-opus-4-8");

        assertTrue(res.success());
        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelOps).testConnection(captor.capture());
        assertEquals("sk-stored-key", captor.getValue().apiKey(), "blank typed key must fall back to the stored key");
    }

    @Test
    void noKeyAnywhereFailsSoftWithoutProbing() {
        when(credentials.resolveDefaultKey(ORG, "OPENAI")).thenReturn(Optional.empty());

        ProviderCredentialTestResponse res =
                service().test(ORG, "OPENAI", null, "gpt-4o-mini");

        assertFalse(res.success());
        assertTrue(res.message().toLowerCase().contains("no api key"),
                "message must explain the missing key; got: " + res.message());
        verify(modelOps, never()).testConnection(any());
    }

    @Test
    void ollamaProbesEvenWithoutAKey() {
        when(credentials.resolveDefaultKey(ORG, "OLLAMA")).thenReturn(Optional.empty());

        ProviderCredentialTestResponse res =
                service().test(ORG, "OLLAMA", null, "llama3.1");

        assertTrue(res.success(), "keyless provider must probe without failing on a missing key");
        verify(modelOps).testConnection(any());
    }

    @Test
    void providerFailureMapsToUnsuccessfulResult() {
        doThrow(new BusinessValidationException("Connection test failed: 401 Unauthorized"))
                .when(modelOps).testConnection(any());

        ProviderCredentialTestResponse res =
                service().test(ORG, "OPENAI", "sk-bad", "gpt-4o-mini");

        assertFalse(res.success(), "a provider failure must map to success=false, not propagate");
        assertTrue(res.message().contains("401"), "provider message must be surfaced; got: " + res.message());
    }
}
