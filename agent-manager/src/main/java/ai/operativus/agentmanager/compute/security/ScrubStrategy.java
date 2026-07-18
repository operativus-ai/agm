package ai.operativus.agentmanager.compute.security;

/**
 * Domain Responsibility: Enumerates the available scrubbing strategies for PII redaction.
 * State: Stateless (Enum)
 */
public enum ScrubStrategy {

    /**
     * Format-Preserving Encryption: replaces the PII value with a structurally valid
     * fake value of identical length/format (e.g., a real-looking but fake SSN).
     */
    FPE,

    /**
     * Simple text replacement: replaces the PII value with a bracketed label
     * (e.g., {@code [REDACTED_EMAIL]}).
     */
    REDACT
}
