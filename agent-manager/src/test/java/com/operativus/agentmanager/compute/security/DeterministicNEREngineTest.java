package com.operativus.agentmanager.compute.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain Responsibility: Validates the DeterministicNEREngine correctly detects PII patterns,
 * enforces Luhn validation, and produces correctly scrubbed output.
 */
class DeterministicNEREngineTest {

    private DeterministicNEREngine nerEngine;
    private FormatPreservingEncryptionService fpeService;

    @BeforeEach
    void setUp() {
        nerEngine = new DeterministicNEREngine();
        fpeService = new FormatPreservingEncryptionService();
    }

    private PiiPolicyEntity createPolicy(String name, PatternType type, String pattern, ScrubStrategy strategy) {
        return new PiiPolicyEntity(UUID.randomUUID(), "test-org", name, "Test policy", type, pattern, strategy, true, null, null);
    }

    @Nested
    @DisplayName("scan()")
    class ScanTests {

        @Test
        @DisplayName("should detect email addresses with REGEX pattern")
        void detectsEmails() {
            PiiPolicyEntity emailPolicy = createPolicy("EMAIL",
                    PatternType.REGEX, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}", ScrubStrategy.FPE);

            String text = "Contact me at john@example.com for details.";
            List<DeterministicNEREngine.DetectionResult> results = nerEngine.scan(text, List.of(emailPolicy));

            assertEquals(1, results.size());
            assertEquals("john@example.com", results.getFirst().matchedValue());
        }

        @Test
        @DisplayName("should detect SSN patterns")
        void detectsSsn() {
            PiiPolicyEntity ssnPolicy = createPolicy("US_SSN",
                    PatternType.REGEX, "\\b\\d{3}-\\d{2}-\\d{4}\\b", ScrubStrategy.FPE);

            String text = "My SSN is 123-45-6789 and yours is 987-65-4321.";
            List<DeterministicNEREngine.DetectionResult> results = nerEngine.scan(text, List.of(ssnPolicy));

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("should validate credit cards with Luhn algorithm")
        void validatesLuhn() {
            PiiPolicyEntity ccPolicy = createPolicy("CREDIT_CARD",
                    PatternType.LUHN, "\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b", ScrubStrategy.FPE);

            // 4532015112830366 passes Luhn
            String text = "Card: 4532015112830366";
            List<DeterministicNEREngine.DetectionResult> results = nerEngine.scan(text, List.of(ccPolicy));
            assertEquals(1, results.size(), "Valid Luhn number should be detected");
        }

        @Test
        @DisplayName("should reject invalid Luhn sequences")
        void rejectsInvalidLuhn() {
            PiiPolicyEntity ccPolicy = createPolicy("CREDIT_CARD",
                    PatternType.LUHN, "\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b", ScrubStrategy.FPE);

            // 1234567890123456 fails Luhn
            String text = "Number: 1234567890123456";
            List<DeterministicNEREngine.DetectionResult> results = nerEngine.scan(text, List.of(ccPolicy));
            assertEquals(0, results.size(), "Invalid Luhn number should be rejected");
        }

        @Test
        @DisplayName("should return empty list for null or empty text")
        void handlesNullText() {
            PiiPolicyEntity policy = createPolicy("TEST", PatternType.REGEX, ".*", ScrubStrategy.REDACT);
            assertTrue(nerEngine.scan(null, List.of(policy)).isEmpty());
            assertTrue(nerEngine.scan("", List.of(policy)).isEmpty());
        }

        @Test
        @DisplayName("should return empty list for null or empty policies")
        void handlesNullPolicies() {
            assertTrue(nerEngine.scan("some text", null).isEmpty());
            assertTrue(nerEngine.scan("some text", List.of()).isEmpty());
        }

        @Test
        @DisplayName("should handle invalid regex pattern gracefully")
        void handlesInvalidRegex() {
            PiiPolicyEntity badPolicy = createPolicy("BAD", PatternType.REGEX, "[invalid(", ScrubStrategy.REDACT);
            List<DeterministicNEREngine.DetectionResult> results = nerEngine.scan("test text", List.of(badPolicy));
            assertTrue(results.isEmpty(), "Invalid regex should be silently skipped");
        }
    }

    @Nested
    @DisplayName("scrub()")
    class ScrubTests {

        @Test
        @DisplayName("should replace detected email with FPE value of same length")
        void scrubsEmailWithFpe() {
            PiiPolicyEntity emailPolicy = createPolicy("EMAIL",
                    PatternType.REGEX, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}", ScrubStrategy.FPE);

            String text = "Contact john@example.com please.";
            DeterministicNEREngine.ScrubResult result = nerEngine.scrub(text, List.of(emailPolicy), fpeService);

            assertFalse(result.scrubbedText().contains("john@example.com"),
                    "Original email should be removed");
            assertEquals(text.length(), result.scrubbedText().length(),
                    "FPE replacement should preserve total text length");
            assertEquals(1, result.events().size());
            assertEquals("EMAIL", result.events().getFirst().policyName());
        }

        @Test
        @DisplayName("should replace detected value with redaction label")
        void scrubsWithRedaction() {
            PiiPolicyEntity ssnPolicy = createPolicy("US_SSN",
                    PatternType.REGEX, "\\b\\d{3}-\\d{2}-\\d{4}\\b", ScrubStrategy.REDACT);

            String text = "SSN: 123-45-6789";
            DeterministicNEREngine.ScrubResult result = nerEngine.scrub(text, List.of(ssnPolicy), fpeService);

            assertTrue(result.scrubbedText().contains("[REDACTED_US_SSN]"),
                    "Redacted text should contain the policy label");
            assertFalse(result.scrubbedText().contains("123-45-6789"),
                    "Original SSN should be removed");
        }

        @Test
        @DisplayName("should return original text when no PII detected")
        void noDetection() {
            PiiPolicyEntity emailPolicy = createPolicy("EMAIL",
                    PatternType.REGEX, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}", ScrubStrategy.FPE);

            String text = "No PII here at all.";
            DeterministicNEREngine.ScrubResult result = nerEngine.scrub(text, List.of(emailPolicy), fpeService);

            assertEquals(text, result.scrubbedText());
            assertTrue(result.events().isEmpty());
        }

        @Test
        @DisplayName("should scrub multiple detections in single text")
        void scrubsMultipleDetections() {
            PiiPolicyEntity emailPolicy = createPolicy("EMAIL",
                    PatternType.REGEX, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}", ScrubStrategy.REDACT);
            PiiPolicyEntity ssnPolicy = createPolicy("US_SSN",
                    PatternType.REGEX, "\\b\\d{3}-\\d{2}-\\d{4}\\b", ScrubStrategy.REDACT);

            String text = "Email: user@test.com SSN: 123-45-6789";
            DeterministicNEREngine.ScrubResult result = nerEngine.scrub(text,
                    List.of(emailPolicy, ssnPolicy), fpeService);

            assertFalse(result.scrubbedText().contains("user@test.com"));
            assertFalse(result.scrubbedText().contains("123-45-6789"));
            assertEquals(2, result.events().size());
        }
    }

    @Nested
    @DisplayName("passesLuhnCheck()")
    class LuhnTests {

        @Test
        @DisplayName("should pass valid Visa card number")
        void validVisa() {
            assertTrue(nerEngine.passesLuhnCheck("4532015112830366"));
        }

        @Test
        @DisplayName("should pass valid Mastercard number")
        void validMastercard() {
            assertTrue(nerEngine.passesLuhnCheck("5425233430109903"));
        }

        @Test
        @DisplayName("should fail arbitrary digit sequence")
        void failsArbitraryDigits() {
            assertFalse(nerEngine.passesLuhnCheck("1234567890123456"));
        }

        @Test
        @DisplayName("should fail null")
        void failsNull() {
            assertFalse(nerEngine.passesLuhnCheck(null));
        }

        @Test
        @DisplayName("should fail single digit")
        void failsSingleDigit() {
            assertFalse(nerEngine.passesLuhnCheck("5"));
        }
    }
}
