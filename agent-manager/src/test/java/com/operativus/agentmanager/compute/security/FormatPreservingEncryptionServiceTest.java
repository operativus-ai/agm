package com.operativus.agentmanager.compute.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain Responsibility: Validates the FormatPreservingEncryptionService correctly preserves
 * structural format while producing deterministic fake replacements.
 */
class FormatPreservingEncryptionServiceTest {

    private FormatPreservingEncryptionService fpeService;

    @BeforeEach
    void setUp() {
        fpeService = new FormatPreservingEncryptionService();
    }

    @Nested
    @DisplayName("encrypt()")
    class EncryptTests {

        @Test
        @DisplayName("should preserve exact length of input")
        void preservesLength() {
            String ssn = "123-45-6789";
            String result = fpeService.encrypt(ssn);
            assertEquals(ssn.length(), result.length(), "Encrypted value must have same length");
        }

        @Test
        @DisplayName("should preserve structural characters (hyphens, dots, @)")
        void preservesStructuralCharacters() {
            String email = "john.doe@example.com";
            String result = fpeService.encrypt(email);

            // Structural characters must remain at exact positions
            assertEquals('.', result.charAt(email.indexOf('.')),
                    "Period before 'doe' must be preserved");
            assertEquals('@', result.charAt(email.indexOf('@')),
                    "@ symbol must be preserved");
            assertTrue(result.contains("."),
                    "At least one dot must be present in the encrypted email");
        }

        @Test
        @DisplayName("should not return original value")
        void doesNotReturnOriginal() {
            String creditCard = "4532-1234-5678-9012";
            String result = fpeService.encrypt(creditCard);
            assertNotEquals(creditCard, result,
                    "Encrypted value must differ from original");
        }

        @Test
        @DisplayName("should be deterministic for same input")
        void isDeterministic() {
            String input = "jane.smith@corp.org";
            String first = fpeService.encrypt(input);
            String second = fpeService.encrypt(input);
            assertEquals(first, second,
                    "Same input must always produce the same encrypted output");
        }

        @Test
        @DisplayName("should preserve digit class for digits")
        void preservesDigitClass() {
            String ssn = "123-45-6789";
            String result = fpeService.encrypt(ssn);

            for (int i = 0; i < ssn.length(); i++) {
                char original = ssn.charAt(i);
                char encrypted = result.charAt(i);
                if (Character.isDigit(original)) {
                    assertTrue(Character.isDigit(encrypted),
                            "Position " + i + " should remain a digit, got: " + encrypted);
                } else {
                    assertEquals(original, encrypted,
                            "Structural char at position " + i + " should be preserved");
                }
            }
        }

        @Test
        @DisplayName("should preserve letter case")
        void preservesLetterCase() {
            String input = "John DOE";
            String result = fpeService.encrypt(input);

            for (int i = 0; i < input.length(); i++) {
                char original = input.charAt(i);
                char encrypted = result.charAt(i);
                if (Character.isUpperCase(original)) {
                    assertTrue(Character.isUpperCase(encrypted),
                            "Position " + i + " should remain uppercase");
                } else if (Character.isLowerCase(original)) {
                    assertTrue(Character.isLowerCase(encrypted),
                            "Position " + i + " should remain lowercase");
                }
            }
        }

        @Test
        @DisplayName("should handle null input gracefully")
        void handlesNull() {
            assertNull(fpeService.encrypt(null));
        }

        @Test
        @DisplayName("should handle empty string")
        void handlesEmpty() {
            assertEquals("", fpeService.encrypt(""));
        }
    }

    @Nested
    @DisplayName("redact()")
    class RedactTests {

        @Test
        @DisplayName("should produce bracketed label")
        void producesBracketedLabel() {
            String result = fpeService.redact("EMAIL_ADDRESS");
            assertEquals("[REDACTED_EMAIL_ADDRESS]", result);
        }

        @Test
        @DisplayName("should produce label for SSN")
        void producesLabelForSsn() {
            assertEquals("[REDACTED_US_SSN]", fpeService.redact("US_SSN"));
        }
    }
}
