package com.operativus.agentmanager.compute.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain Responsibility: High-speed, deterministic Named Entity Recognition (NER) engine
 * that identifies PII within arbitrary text using compiled Regex patterns and optional Luhn
 * checksum validation. This engine does NOT use ML models; it operates purely on structural
 * pattern matching for predictable, low-latency detection.
 *
 * <p>Compiled patterns are cached in a thread-safe {@link ConcurrentHashMap} to avoid
 * redundant recompilation across invocations.</p>
 *
 * State: Stateful (maintains pattern compilation cache)
 */
@Service
public class DeterministicNEREngine {

    private static final Logger log = LoggerFactory.getLogger(DeterministicNEREngine.class);

    private final Map<String, Pattern> compiledPatternCache = new ConcurrentHashMap<>();

    /**
     * Immutable result record representing a single PII detection within a text body.
     *
     * @param matchedValue the raw PII string that was detected
     * @param startIndex   the character offset where the match begins
     * @param endIndex     the character offset where the match ends (exclusive)
     * @param policy       the PII policy whose pattern triggered the detection
     */
    public record DetectionResult(String matchedValue, int startIndex, int endIndex, PiiPolicyEntity policy) {}

    /**
     * @summary Scans a text body against a list of active PII policies and returns all detections.
     * @logic For each policy, retrieves or compiles the regex pattern from cache, applies it
     *        against the input text, and for LUHN-type policies additionally validates each
     *        captured digit sequence using the Luhn algorithm. Only validated matches are returned.
     *
     * @param text     the text to scan for PII
     * @param policies the list of active PII policies to evaluate
     * @return an ordered list of detected PII matches with their positions and triggering policies
     */
    public List<DetectionResult> scan(String text, List<PiiPolicyEntity> policies) {
        if (text == null || text.isEmpty() || policies == null || policies.isEmpty()) {
            return List.of();
        }

        List<DetectionResult> results = new ArrayList<>();

        for (PiiPolicyEntity policy : policies) {
            Pattern compiled = compiledPatternCache.computeIfAbsent(
                    policy.getName(),
                    name -> {
                        try {
                            return Pattern.compile(policy.getPattern());
                        } catch (Exception e) {
                            log.error("Failed to compile PII pattern for policy '{}': {}", policy.getName(), e.getMessage());
                            return null;
                        }
                    }
            );

            if (compiled == null) {
                continue;
            }

            Matcher matcher = compiled.matcher(text);
            while (matcher.find()) {
                String matched = matcher.group();

                if (policy.getPatternType() == PatternType.LUHN) {
                    String digitsOnly = matched.replaceAll("[^0-9]", "");
                    if (!passesLuhnCheck(digitsOnly)) {
                        log.trace("Luhn validation failed for candidate '{}', skipping.", matched);
                        continue;
                    }
                }

                results.add(new DetectionResult(matched, matcher.start(), matcher.end(), policy));
            }
        }

        log.debug("NER scan complete: {} PII detections found across {} policies", results.size(), policies.size());
        return results;
    }

    /**
     * @summary Applies the full text scrubbing pipeline using provided policies and encryption service.
     * @logic Scans the text for detections, then iterates in reverse order to replace each match
     *        with the appropriate scrubbed value (FPE or REDACT) without corrupting character offsets.
     *
     * @param text       the text to sanitize
     * @param policies   the applicable PII policies
     * @param fpeService the Format-Preserving Encryption service for FPE replacements
     * @return the sanitized text with all detections replaced
     */
    public ScrubResult scrub(String text, List<PiiPolicyEntity> policies, FormatPreservingEncryptionService fpeService) {
        List<DetectionResult> detections = scan(text, policies);

        if (detections.isEmpty()) {
            return new ScrubResult(text, List.of());
        }

        // Sort detections by start index descending so replacements do not shift subsequent offsets
        List<DetectionResult> sorted = detections.stream()
                .sorted((a, b) -> Integer.compare(b.startIndex(), a.startIndex()))
                .toList();

        StringBuilder scrubbed = new StringBuilder(text);
        List<ScrubEvent> events = new ArrayList<>();

        for (DetectionResult detection : sorted) {
            String replacement = switch (detection.policy().getScrubStrategy()) {
                case FPE -> fpeService.encrypt(detection.matchedValue());
                case REDACT -> fpeService.redact(detection.policy().getName());
            };

            scrubbed.replace(detection.startIndex(), detection.endIndex(), replacement);
            events.add(new ScrubEvent(detection.policy().getName(), detection.policy().getScrubStrategy().name()));
        }

        return new ScrubResult(scrubbed.toString(), events);
    }

    /**
     * Immutable result record encapsulating the scrubbed text and the audit events generated.
     *
     * @param scrubbedText the sanitized text
     * @param events       the list of scrub actions performed for audit logging
     */
    public record ScrubResult(String scrubbedText, List<ScrubEvent> events) {}

    /**
     * Immutable record capturing a single scrub action for NHI audit logging.
     *
     * @param policyName    the name of the policy that triggered the scrub
     * @param scrubStrategy the strategy applied (FPE or REDACT)
     */
    public record ScrubEvent(String policyName, String scrubStrategy) {}

    /**
     * @summary Validates a digit string using the Luhn algorithm (ISO/IEC 7812-1).
     * @logic Iterates digits from right to left, doubling every second digit and subtracting 9
     *        if the result exceeds 9. A valid number produces a total divisible by 10.
     *
     * @param digits a string containing only numeric characters
     * @return {@code true} if the digit string passes the Luhn checksum
     */
    boolean passesLuhnCheck(String digits) {
        if (digits == null || digits.length() < 2) {
            return false;
        }

        int sum = 0;
        boolean alternate = false;

        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }

        return sum % 10 == 0;
    }
}
