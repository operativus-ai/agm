package ai.operativus.agentmanager.compute.security;

/**
 * Domain Responsibility: Enumerates the pattern matching techniques used to detect PII.
 * State: Stateless (Enum)
 */
public enum PatternType {

    /**
     * Standard regular expression matching.
     */
    REGEX,

    /**
     * Regular expression matching followed by a Luhn checksum validation
     * to confirm the captured digits form a valid financial instrument number.
     */
    LUHN
}
