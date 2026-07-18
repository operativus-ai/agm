package ai.operativus.agentmanager.compute.advisor;

/**
 * Domain Responsibility: Generic interface for validating LLM inputs/outputs against harmful or restricted content policies.
 * State: Stateless
 */
public interface ModerationService {
    
    /**
     * @summary Inspects the provided content string for violations.
     * @logic Returns a structured {@link ModerationResult} with a 0.0–1.0 risk score and any
     *     matched signals. Hard policy violations throw {@link SecurityException} — that contract
     *     is preserved (a flagged response MUST NOT exit the LLM chain). Soft signals
     *     (low-risk, suspicious-but-allowed) flow through the result so callers can publish
     *     observability without re-running the policy logic.
     */
    ModerationResult checkContent(String content) throws SecurityException;

}
