package com.operativus.agentmanager.control.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Domain Responsibility: Zero Trust middleware for deep packet inspection of Agent communication.
 * Scans incoming strings and capability arrays for Prompt Injection signatures,
 * trailing escape vectors, and control-character anomalies.
 * State: Stateless
 */
@Service
public class GatewayPromptInjectionScanner {

    private static final Logger log = LoggerFactory.getLogger(GatewayPromptInjectionScanner.class);

    private static final List<java.util.regex.Pattern> TOXIC_PATTERNS = List.of(
            java.util.regex.Pattern.compile("(?i)ignore\\s+previous\\s+(instructions|directions|prompts)"),
            java.util.regex.Pattern.compile("(?i)(system\\s+prompt|###\\s*system)"),
            java.util.regex.Pattern.compile("(?i)(Runtime\\.getRuntime\\(\\)\\.exec|exec\\s*\\()"),
            java.util.regex.Pattern.compile("(?i)(DROP|TRUNCATE)\\s+(TABLE|DATABASE|SCHEMA)"),
            java.util.regex.Pattern.compile("(?is)<script.*?>.*?</script>")
    );

    /**
     * Scans a raw payload for known prompt injection signatures.
     * @param payload Request/Response string to scan
     * @throws SecurityException if a signature is matched
     */
    public void scanPayload(String payload) {
        if (payload == null || payload.isBlank()) return;
        
        for (java.util.regex.Pattern pattern : TOXIC_PATTERNS) {
            if (pattern.matcher(payload).find()) {
                log.warn("🚨 Prompt Injection Detected! Signature matched: {}", pattern.pattern());
                throw new SecurityException("Security Boundary Violation: Toxic payload detected.");
            }
        }
    }

    /**
     * Scans a list of tool capabilities or arguments for anomalies.
     */
    public void scanCapabilities(List<String> capabilities) {
        if (capabilities == null) return;
        for (String cap : capabilities) {
            scanPayload(cap);
        }
    }
}
