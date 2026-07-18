package ai.operativus.agentmanager.core.callback;

import java.util.Collections;
import java.util.Map;

/**
 * Domain Responsibility: Propagates agent identity credentials through the Virtual Thread execution
 * scope using ScopedValue. Tool callbacks read this context to attach agent-scoped authorization
 * headers to outbound requests.
 *
 * <p>Architecture: Uses ScopedValue (not ThreadLocal) to align with the Virtual Thread model.
 * The credentials map is keyed by provider name (e.g. "stripe", "github") with token values.</p>
 *
 * State: Stateful (ScopedValue context)
 */
public class AgentIdentityContext {

    /**
     * Map of provider name -> minted bearer token for the current agent execution scope.
     */
    public static final ScopedValue<Map<String, String>> agentTokens = ScopedValue.newInstance();

    /**
     * The agent ID that owns the current credential scope.
     */
    public static final ScopedValue<String> credentialAgentId = ScopedValue.newInstance();

    /**
     * Returns the minted token for the given provider, or null if not bound or not present.
     */
    public static String getToken(String providerName) {
        if (!agentTokens.isBound()) return null;
        return agentTokens.get().get(providerName);
    }

    /**
     * Returns all bound agent tokens, or an empty map if not bound.
     */
    public static Map<String, String> getAllTokens() {
        return agentTokens.isBound() ? Collections.unmodifiableMap(agentTokens.get()) : Collections.emptyMap();
    }

    /**
     * Returns whether the current execution scope has any agent identity credentials bound.
     */
    public static boolean hasIdentity() {
        return agentTokens.isBound() && !agentTokens.get().isEmpty();
    }

    /**
     * Returns the credential-owning agent ID, or null if not bound.
     */
    public static String getCredentialAgentId() {
        return credentialAgentId.isBound() ? credentialAgentId.get() : null;
    }
}
