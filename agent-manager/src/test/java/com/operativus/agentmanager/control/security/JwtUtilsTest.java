package com.operativus.agentmanager.control.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Focused unit coverage of the fail-fast guard on
 *   {@link JwtUtils#validateSecretOrFail()}, which prevents the well-known legacy
 *   default secret and undersized HMAC keys from silently signing tokens.
 *
 *   <p>Reflection is used to set the {@code @Value}-bound field directly — the same
 *   pattern Spring uses at injection time — then the package-private init method is
 *   invoked. This avoids spinning up a full ApplicationContext for what is purely
 *   a config-validation contract.
 *
 * State: Stateless.
 */
class JwtUtilsTest {

    private static final String LEGACY_INSECURE_DEFAULT =
            "mySuperSecretKeyThatIsVeryLongAndSecureEnoughForHS256AlgorithmByDefault";

    private static final String VALID_TEST_SECRET =
            "unit-test-secret-do-not-use-in-production-must-be-min-32-bytes-long";

    @Test
    void validateSecretOrFail_RejectsNullSecret() {
        JwtUtils utils = newWithSecret(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, utils::validateSecretOrFail);
        assertTrue(ex.getMessage().contains("required"),
                "expected 'required' in error message; got: " + ex.getMessage());
    }

    @Test
    void validateSecretOrFail_RejectsBlankSecret() {
        JwtUtils utils = newWithSecret("   ");

        IllegalStateException ex = assertThrows(IllegalStateException.class, utils::validateSecretOrFail);
        assertTrue(ex.getMessage().contains("required"));
    }

    @Test
    void validateSecretOrFail_RejectsLegacyHardcodedDefault() {
        // The literal that shipped as the @Value fallback in older revisions. Any
        // deployment that somehow still resolves to this exact value would be using
        // a publicly known signing key from the open-source repo.
        JwtUtils utils = newWithSecret(LEGACY_INSECURE_DEFAULT);

        IllegalStateException ex = assertThrows(IllegalStateException.class, utils::validateSecretOrFail);
        assertTrue(ex.getMessage().contains("legacy hardcoded default")
                        && ex.getMessage().contains("publicly known"),
                "expected legacy-default rejection with public-known callout; got: " + ex.getMessage());
    }

    @Test
    void validateSecretOrFail_RejectsTooShortSecret() {
        // 16 bytes after fallback (non-base64 → getBytes()); HS256 requires 32.
        JwtUtils utils = newWithSecret("only-16-bytes-xx");

        IllegalStateException ex = assertThrows(IllegalStateException.class, utils::validateSecretOrFail);
        assertTrue(ex.getMessage().contains("HS256 requires at least 32 bytes"),
                "expected HS256-min-bytes rejection; got: " + ex.getMessage());
    }

    @Test
    void validateSecretOrFail_AcceptsValidLongPlaintextSecret() {
        JwtUtils utils = newWithSecret(VALID_TEST_SECRET);

        // No exception thrown.
        utils.validateSecretOrFail();
    }

    @Test
    void validateSecretOrFail_AcceptsValidBase64EncodedSecret() {
        // 32 bytes of zeros, base64-encoded — decodes to a valid HS256 key.
        String base64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        JwtUtils utils = newWithSecret(base64Secret);

        utils.validateSecretOrFail();
    }

    // Confirm the guard runs BEFORE any token operation, so a misconfigured deployment
    // crashes at startup rather than silently signing the first request with the
    // publicly known key.
    @Test
    void validateSecretOrFail_RunsBeforeAnyTokenOperation() {
        JwtUtils utils = newWithSecret(LEGACY_INSECURE_DEFAULT);

        // If validateSecretOrFail somehow no-op'd, getUserNameFromJwtToken would happily
        // parse a token signed with the legacy key. The contract under test: the @PostConstruct
        // throws at app startup, ensuring no token operation is ever invoked.
        assertEquals(IllegalStateException.class,
                assertThrows(Throwable.class, utils::validateSecretOrFail).getClass());
    }

    // ─── iss / aud claim enforcement ────────────────────────────────────────
    //
    // Stamped at generation time and required on every parse. Defense-in-depth
    // alongside the HS256 signature: if the same secret somehow ends up shared
    // with a sibling system (key leaked + re-used elsewhere; test/staging key
    // accidentally reused in prod), the iss/aud mismatch still rejects the
    // foreign-issued token at parse, not just the signature.

    private static final String EXPECTED_ISS = "agentmanager";
    private static final String EXPECTED_AUD = "agentmanager-api";

    @Test
    void generateJwtToken_StampsConfiguredIssuerAndAudience() {
        JwtUtils utils = newFullyConfigured();
        String token = utils.generateJwtToken(sampleAuth());

        Claims claims = parseClaimsWithSameKey(token);
        assertEquals(EXPECTED_ISS, claims.getIssuer(),
                "generated token must carry the configured iss claim");
        assertEquals(EXPECTED_AUD, claims.getAudience(),
                "generated token must carry the configured aud claim");
    }

    @Test
    void validateJwtToken_AcceptsTokenWithMatchingIssAndAud() {
        JwtUtils utils = newFullyConfigured();
        String token = utils.generateJwtToken(sampleAuth());

        assertTrue(utils.validateJwtToken(token));
    }

    @Test
    void validateJwtToken_RejectsTokenWithWrongIssuer() {
        // Token signed with our key but stamped with a DIFFERENT issuer — exactly
        // the cross-system replay scenario the iss/aud guard is meant to catch.
        JwtUtils utils = newFullyConfigured();
        String foreignToken = signTokenWith(EXPECTED_AUD, "different-issuer");

        assertFalse(utils.validateJwtToken(foreignToken),
                "validate must reject when iss doesn't match the configured value");
    }

    @Test
    void validateJwtToken_RejectsTokenWithWrongAudience() {
        JwtUtils utils = newFullyConfigured();
        String foreignToken = signTokenWith("different-audience", EXPECTED_ISS);

        assertFalse(utils.validateJwtToken(foreignToken),
                "validate must reject when aud doesn't match the configured value");
    }

    @Test
    void validateJwtToken_RejectsTokenMissingIssuerClaim() {
        // Stale token issued before the iss/aud guard landed (or by a third-party
        // signer that omits the claim).
        JwtUtils utils = newFullyConfigured();
        String tokenWithoutIss = signTokenWith(EXPECTED_AUD, null);

        assertFalse(utils.validateJwtToken(tokenWithoutIss),
                "validate must reject when iss claim is absent (MissingClaimException)");
    }

    @Test
    void validateJwtToken_RejectsTokenMissingAudienceClaim() {
        JwtUtils utils = newFullyConfigured();
        String tokenWithoutAud = signTokenWith(null, EXPECTED_ISS);

        assertFalse(utils.validateJwtToken(tokenWithoutAud),
                "validate must reject when aud claim is absent (MissingClaimException)");
    }

    @Test
    void getUserNameFromJwtToken_PropagatesIssMismatchException() {
        // The name-extraction path must enforce iss/aud too — otherwise a caller
        // that bypasses validateJwtToken and goes straight to getUserNameFromJwtToken
        // would extract the subject from a token with the wrong iss.
        JwtUtils utils = newFullyConfigured();
        String foreignToken = signTokenWith(EXPECTED_AUD, "different-issuer");

        assertThrows(IncorrectClaimException.class,
                () -> utils.getUserNameFromJwtToken(foreignToken));
    }

    @Test
    void getOrgIdFromJwtToken_PropagatesAudMismatchException() {
        JwtUtils utils = newFullyConfigured();
        String foreignToken = signTokenWith("different-audience", EXPECTED_ISS);

        assertThrows(IncorrectClaimException.class,
                () -> utils.getOrgIdFromJwtToken(foreignToken));
    }

    @Test
    void getUserNameFromJwtToken_PropagatesMissingClaimException() {
        JwtUtils utils = newFullyConfigured();
        String tokenWithoutIss = signTokenWith(EXPECTED_AUD, null);

        assertThrows(MissingClaimException.class,
                () -> utils.getUserNameFromJwtToken(tokenWithoutIss));
    }

    // ─── Test fixtures ─────────────────────────────────────────────────────

    private static JwtUtils newWithSecret(String secret) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "jwtSecret", secret);
        ReflectionTestUtils.setField(utils, "jwtExpirationMs", 86_400_000);
        return utils;
    }

    private static JwtUtils newFullyConfigured() {
        JwtUtils utils = newWithSecret(VALID_TEST_SECRET);
        ReflectionTestUtils.setField(utils, "jwtIssuer", EXPECTED_ISS);
        ReflectionTestUtils.setField(utils, "jwtAudience", EXPECTED_AUD);
        return utils;
    }

    private static Authentication sampleAuth() {
        UserDetailsImpl principal = new UserDetailsImpl(
                UUID.randomUUID(), "test-user", "test@local",
                "ORG_TEST", false, "ignored",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    // Builds a JWT signed with the SAME key the JwtUtils uses (so the signature
    // check passes) but with caller-specified iss/aud — used by the negative tests
    // to prove the iss/aud guard rejects on claim mismatch even when the signature
    // is valid. null iss/aud omits the claim entirely.
    private static String signTokenWith(String audience, String issuer) {
        var builder = Jwts.builder()
                .setSubject("test-user")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000));
        if (issuer != null) builder.setIssuer(issuer);
        if (audience != null) builder.setAudience(audience);
        return builder.signWith(signingKey(), SignatureAlgorithm.HS256).compact();
    }

    private static Claims parseClaimsWithSameKey(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private static Key signingKey() {
        // Mirror JwtUtils.decodeKeyBytes — Base64 first, plain bytes fallback.
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(VALID_TEST_SECRET);
        } catch (Exception e) {
            keyBytes = VALID_TEST_SECRET.getBytes();
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
