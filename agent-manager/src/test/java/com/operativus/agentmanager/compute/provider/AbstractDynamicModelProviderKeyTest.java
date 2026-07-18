package com.operativus.agentmanager.compute.provider;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.MissingProviderKeyException;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pin the key-resolution contract of {@link AbstractDynamicModelProvider}.
 * A per-model override wins; otherwise the (org, provider) ProviderCredential; and when neither
 * yields a key the provider fails fast with a typed {@link MissingProviderKeyException} carrying
 * an actionable message — so orchestration can distinguish it and avoid silently substituting a
 * different model.
 *
 * State: Stateless — pure Mockito, no Spring context.
 */
class AbstractDynamicModelProviderKeyTest {

    /** Minimal concrete provider exposing the protected resolveApiKey for testing. */
    private static final class TestProvider extends AbstractDynamicModelProvider {
        TestProvider(ProviderCredentialOperations creds) { super(creds); }
        String resolve(ModelEntity m) { return resolveApiKey(m); }
        @Override public java.util.List<String> getProviderKeys() { return java.util.List.of("TEST"); }
        @Override public org.springframework.ai.chat.model.ChatModel buildChatModel(
                ModelEntity me, com.operativus.agentmanager.core.model.definitions.AgentDefinition def) { return null; }
    }

    private ModelEntity model(String provider, String override) {
        ModelEntity me = new ModelEntity();
        me.setName("m");
        me.setProvider(provider);
        me.setApiKey(override);
        return me;
    }

    @Test
    void missingKeyThrowsTypedActionableException() {
        ProviderCredentialOperations creds = mock(ProviderCredentialOperations.class);
        // No caller org bound in a unit test → provider falls back to DEFAULT_SYSTEM_ORG.
        when(creds.resolveDefaultKey(eq("DEFAULT_SYSTEM_ORG"), eq("GOOGLE"))).thenReturn(Optional.empty());

        TestProvider provider = new TestProvider(creds);
        MissingProviderKeyException ex = assertThrows(MissingProviderKeyException.class,
                () -> provider.resolve(model("GOOGLE", null)));

        assertEquals("GOOGLE", ex.getProvider());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("GOOGLE"),
                "message must name the provider so the operator knows which key to configure");
    }

    @Test
    void perModelKeyOverrideWinsWithoutHittingCredentials() {
        ProviderCredentialOperations creds = mock(ProviderCredentialOperations.class);
        TestProvider provider = new TestProvider(creds);

        assertEquals("sk-override", provider.resolve(model("OPENAI", "sk-override")));
    }

    @Test
    void storedCredentialResolvesWhenNoOverride() {
        ProviderCredentialOperations creds = mock(ProviderCredentialOperations.class);
        when(creds.resolveDefaultKey(eq("DEFAULT_SYSTEM_ORG"), eq("OPENAI"))).thenReturn(Optional.of("sk-stored"));

        TestProvider provider = new TestProvider(creds);
        assertEquals("sk-stored", provider.resolve(model("OPENAI", null)));
    }
}
