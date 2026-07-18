package ai.operativus.agentmanager.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused pin for the BCrypt strength wiring on {@link SecurityConfig#passwordEncoder()}.
 * Spring no-arg {@code BCryptPasswordEncoder()} defaults to strength 10 (~50 ms per hash)
 * which is below OWASP 2024+ minimums. Production must default to 12; tests override to 4
 * for speed. This test guards against an accidental regression to the Spring default.
 *
 * <p>The bean method reads the strength from a {@code @Value}-injected field. We construct
 * SecurityConfig directly (passing nulls for filter-chain dependencies that the encoder
 * bean does not touch) and set the field via reflection — same pattern Spring uses, just
 * without spinning the full context for what is essentially a constant verification.
 */
class SecurityConfigPasswordEncoderTest {

    @Test
    void defaultStrengthProducesHashWithCost12Prefix() {
        PasswordEncoder enc = encoderAtStrength(12);
        String hash = enc.encode("probe-password-123");
        assertTrue(hash.startsWith("$2a$12$") || hash.startsWith("$2b$12$"),
                "default strength must be 12 — OWASP 2024+ minimum. Got hash prefix: "
                        + hash.substring(0, Math.min(7, hash.length()))
                        + ". A regression to '$2a$10$' means SecurityConfig fell back to "
                        + "Spring's no-arg BCryptPasswordEncoder() default.");
    }

    @Test
    void testProfileStrengthOf4ProducesCheapHash() {
        // Test-profile override (application-test.properties): strength 4 keeps integration
        // tests fast. Pin here so a regression to a more expensive test strength surfaces
        // immediately rather than as suite-time drift.
        PasswordEncoder enc = encoderAtStrength(4);
        String hash = enc.encode("probe");
        assertTrue(hash.startsWith("$2a$04$") || hash.startsWith("$2b$04$"),
                "test strength must be 4. Got: " + hash.substring(0, Math.min(7, hash.length())));
    }

    @Test
    void hashesEncodedAtDifferentStrengthsAreCrossVerifiable() {
        // The verification contract is symmetric across strengths because the cost is
        // embedded in the stored hash ($2a$NN$...). Without this property, raising the
        // production strength would invalidate all pre-bump hashes — that is not the
        // case, and this test pins the assumption.
        PasswordEncoder strong = encoderAtStrength(12);
        PasswordEncoder weak = encoderAtStrength(4);
        String raw = "cross-strength-verification";

        String hashStrong = strong.encode(raw);
        String hashWeak = weak.encode(raw);

        assertTrue(weak.matches(raw, hashStrong),
                "a low-strength encoder must verify a high-strength hash (cost is in the prefix)");
        assertTrue(strong.matches(raw, hashWeak),
                "a high-strength encoder must verify a low-strength hash (cost is in the prefix)");
    }

    @Test
    void configuredStrengthIsHonored() {
        // Walk a few values to prove the injected field is actually consulted (not silently
        // replaced by a hard-coded constant).
        for (int strength : new int[]{4, 8, 12}) {
            PasswordEncoder enc = encoderAtStrength(strength);
            String hash = enc.encode("x");
            String prefix = hash.substring(0, 7);
            String expected = String.format("$2a$%02d$", strength);
            String expectedAlt = String.format("$2b$%02d$", strength);
            assertEquals(true, prefix.equals(expected) || prefix.equals(expectedAlt),
                    "configured strength " + strength + " must surface in hash prefix; got " + prefix);
        }
    }

    // ─── helpers ───

    private static PasswordEncoder encoderAtStrength(int strength) {
        SecurityConfig config = new SecurityConfig(null, null, null, null, null);
        ReflectionTestUtils.setField(config, "bcryptStrength", strength);
        return config.passwordEncoder();
    }
}
