package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.core.entity.AgentMessage;
import ai.operativus.agentmanager.core.entity.AgentSession;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain Responsibility: Intercepts AgentSession and AgentMessage records at the JPA persistence boundary 
 * to encrypt conversational blobs at rest IF the service layer has flagged them as requiring encryption.
 * State: Stateless (Injects FormatPreservingEncryptionService lazily)
 * 
 * <p>Architecture Note: This interceptor does NOT perform database queries. The encryption
 * decision is pre-computed by the service layer and communicated via the transient
 * {@code requiresEncryption} flag on each entity. This avoids the dangerous anti-pattern
 * of executing EntityManager.find() inside JPA lifecycle callbacks where the Hibernate
 * Session is in a fragile mid-flush or mid-hydration state.</p>
 */
@Component
public class EncryptedSessionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EncryptedSessionInterceptor.class);

    private static ObjectProvider<FormatPreservingEncryptionService> fpeServiceProvider;

    public EncryptedSessionInterceptor() {}

    @Autowired
    public void setFormatPreservingEncryptionService(ObjectProvider<FormatPreservingEncryptionService> provider) {
        EncryptedSessionInterceptor.fpeServiceProvider = provider;
    }

    private FormatPreservingEncryptionService getFpeService() {
        return fpeServiceProvider != null ? fpeServiceProvider.getIfAvailable() : null;
    }

    @PrePersist
    @PreUpdate
    public void encrypt(Object entity) {
        try {
            if (entity instanceof AgentSession session) {
                if (session.isRequiresEncryption()) {
                    String sessionKey = getOrCreateSessionKey(session);
                    if (session.getSummaryBlob() != null && !session.getSummaryBlob().startsWith("ENCRYPTED:")) {
                        session.setSummaryBlob("ENCRYPTED:" + getFpeService().encryptPayload(session.getSummaryBlob(), sessionKey));
                        log.debug("Encrypted AgentSession {} summary blob at rest", session.getSessionId());
                    }
                }
            } else if (entity instanceof AgentMessage message) {
                if (message.isRequiresEncryption()) {
                    // For messages, we need the session key. The message must carry its session context.
                    FormatPreservingEncryptionService fpe = getFpeService();
                    if (fpe != null && message.getContent() != null && !message.getContent().startsWith("ENCRYPTED:")) {
                        // Use a deterministic key derived from the sessionId for consistent encrypt/decrypt
                        String deterministicKey = fpe.generateDeterministicKey(message.getSessionId());
                        message.setContent("ENCRYPTED:" + fpe.encryptPayload(message.getContent(), deterministicKey));
                        log.debug("Encrypted AgentMessage {} payload at rest", message.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to encrypt JPA entity via EncryptedSessionInterceptor: {}", e.getMessage(), e);
        }
    }

    @PostLoad
    public void decrypt(Object entity) {
        try {
            if (entity instanceof AgentSession session) {
                if (session.getSummaryBlob() != null && session.getSummaryBlob().startsWith("ENCRYPTED:")) {
                    String sessionKey = extractSessionKey(session);
                    if (sessionKey != null) {
                        String cipher = session.getSummaryBlob().substring(10);
                        session.setSummaryBlob(getFpeService().decryptPayload(cipher, sessionKey));
                    }
                }
            } else if (entity instanceof AgentMessage message) {
                if (message.getContent() != null && message.getContent().startsWith("ENCRYPTED:")) {
                    FormatPreservingEncryptionService fpe = getFpeService();
                    if (fpe != null) {
                        String deterministicKey = fpe.generateDeterministicKey(message.getSessionId());
                        String cipher = message.getContent().substring(10);
                        message.setContent(fpe.decryptPayload(cipher, deterministicKey));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to decrypt JPA entity via EncryptedSessionInterceptor: {}", e.getMessage(), e);
        }
    }

    private String getOrCreateSessionKey(AgentSession session) {
        Map<String, Object> state = session.getSessionState();
        if (state == null) {
            state = new HashMap<>();
            session.setSessionState(state);
        }
        if (state.containsKey("aes_session_key")) {
            return (String) state.get("aes_session_key");
        }
        String newKey = getFpeService().generateSessionKey();
        state.put("aes_session_key", newKey);
        return newKey;
    }

    private String extractSessionKey(AgentSession session) {
        Map<String, Object> state = session.getSessionState();
        if (state != null && state.containsKey("aes_session_key")) {
            return (String) state.get("aes_session_key");
        }
        return null;
    }
}
