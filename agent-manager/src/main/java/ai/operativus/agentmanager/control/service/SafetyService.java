package ai.operativus.agentmanager.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Domain Responsibility: Middle-tier security validation and PII sanitization for LLM inputs and outputs.
 * State: Stateless
 */
@Service
public class SafetyService {

    private static final Logger log = LoggerFactory.getLogger(SafetyService.class);

    // Regex for basic email and phone (Simplified for demo)
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b");
    
    // Adversarial patterns
    private static final Pattern INJECTION_PATTERN = Pattern.compile("(?i)(ignore previous instructions|system override|dan mode|jailbreak)");

    /**
     * @summary Scans and sanitizes user input for prompt injections and Personally Identifiable Information (PII).
     * @logic Evaluates input against known adversarial regex patterns, throws a SecurityException if malicious intent is detected, and replaces detected Email and Phone number patterns with redacted placeholders.
     */
    public String sanitizeInput(String input) {
        log.debug("Sanitizing input for safety.");
        if (input == null) return null;

        // 1. Prompt Injection Check
        if (INJECTION_PATTERN.matcher(input).find()) {
            log.warn("Potential Prompt Injection blocked: {}", input);
            throw new SecurityException("Safety Violation: Potential Prompt Injection detected.");
        }

        // 2. PII Sanitization
        String sanitized = input;
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("[EMAIL_REDACTED]");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        
        if (!sanitized.equals(input)) {
            log.info("PII Sanitized in input.");
        }
        
        return sanitized;
    }

    /**
     * @summary Sanitizes text being stored as Knowledge-Base reference content (scraped pages,
     *     uploaded docs). Redacts PII like {@link #sanitizeInput} but, unlike it, does NOT throw on
     *     the prompt-injection pattern — it only logs.
     * @logic Stored documents are reference material retrieved later as RAG context, not user
     *     instructions to the model, so hard-blocking a whole document because it contains a phrase
     *     like "jailbreak" or "ignore previous instructions" produces false-positive ingest failures
     *     on legitimate content (e.g. security/AI docs) while adding no real protection — indirect
     *     prompt injection is defended at use-time by {@code PromptInjectionAdvisor} on the chat path.
     *     We flag the match for visibility but let ingestion proceed.
     */
    public String sanitizeForStorage(String input) {
        if (input == null) return null;

        if (INJECTION_PATTERN.matcher(input).find()) {
            // Flag, don't block — see @logic. The runtime advisor is the real injection defense.
            log.info("Ingested content matched a prompt-injection pattern; storing anyway (reference content, not an instruction).");
        }

        String sanitized = input;
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("[EMAIL_REDACTED]");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        return sanitized;
    }

    /**
     * @summary Analyzes LLM-generated output for refusal conditions or policy violations.
     * @logic Scans the output string for known refusal phrases and logs warnings if the model refused the prompt (currently non-blocking).
     */
    public void validateOutput(String output) {
        if (output == null) return;
        
        // Refusal Check
        String lower = output.toLowerCase();
        if (lower.contains("i cannot answer") || lower.contains("unable to fulfill")) {
            log.warn("Model refusal detected: {}", output);
            // We could throw or flag. For now, we log.
        }
    }
}
