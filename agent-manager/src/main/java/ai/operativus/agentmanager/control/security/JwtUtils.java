package ai.operativus.agentmanager.control.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

/**
 * Domain Responsibility: Provides utility functions for generating, extracting, and validating
 *   JSON Web Tokens (JWT) signed with the application's configured HS256 secret.
 *
 * <p><strong>Secret configuration is REQUIRED.</strong> {@code agentmanager.app.jwtSecret}
 *   must be set explicitly in every profile (dev/demo/test set their own non-prod values;
 *   prod requires the {@code JWT_SECRET} env var). The {@code @Value} annotation no longer
 *   carries a hardcoded fallback — Spring will fail at injection time if the property is
 *   missing, and the {@link #validateSecretOrFail()} {@code @PostConstruct} hook then
 *   rejects values that are too short for HS256 (256-bit minimum). The combination ensures
 *   that no deployment can silently fall back to a well-known signing key.
 *
 * State: Stateless (Utility Component).
 */
@Component
public class JwtUtils {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    /** Minimum byte length of the HMAC key for HS256 (256 bits). */
    private static final int MIN_HS256_KEY_BYTES = 32;

    /**
     * Previously shipped as a hardcoded fallback on the {@code @Value} default — kept here
     * as a sentinel so that any environment that somehow still resolves to this literal
     * (e.g., a .properties file that copied the old string) fails fast at startup instead
     * of silently using a publicly known signing key.
     */
    private static final String LEGACY_INSECURE_DEFAULT =
            "mySuperSecretKeyThatIsVeryLongAndSecureEnoughForHS256AlgorithmByDefault";

    @Value("${agentmanager.app.jwtSecret}")
    private String jwtSecret;

    @Value("${agentmanager.app.jwtExpirationMs:86400000}")
    private int jwtExpirationMs;

    /**
     * Issuer claim ({@code iss}) stamped on every token at generation time AND required
     * to match on parse. Defense-in-depth alongside the HS256 signature: if the same
     * secret somehow ends up shared with a sibling system (test/staging reused in prod,
     * key leaked then re-used elsewhere), the iss/aud mismatch still rejects the
     * foreign-issued token. Override via property for multi-deployment isolation
     * (each environment can carry a distinct issuer so cross-env token replay fails
     * the parse, not just the signature check).
     */
    @Value("${agentmanager.app.jwtIssuer:agentmanager}")
    private String jwtIssuer;

    /**
     * Audience claim ({@code aud}). Same shape as the issuer — stamped at generation,
     * required on parse. Default {@code agentmanager-api} marks the token as
     * intended for the AGM REST surface. A future split (e.g. internal vs A2A
     * audiences) would override this per-issuer.
     */
    @Value("${agentmanager.app.jwtAudience:agentmanager-api}")
    private String jwtAudience;

    /**
     * Fail-fast guard executed by Spring after dependency injection. Rejects:
     * <ul>
     *   <li>the legacy hardcoded default literal that previously shipped as the
     *       {@code @Value} fallback (CVE-class: well-known secret)</li>
     *   <li>any value that decodes to fewer than {@value #MIN_HS256_KEY_BYTES} bytes
     *       (HS256 requires &gt;= 256-bit keys; the underlying
     *       {@link Keys#hmacShaKeyFor(byte[])} throws otherwise — we surface it here
     *       with a clear message so operators see the misconfiguration immediately)</li>
     * </ul>
     */
    @PostConstruct
    void validateSecretOrFail() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "agentmanager.app.jwtSecret is required but was not set. "
                            + "Set the JWT_SECRET env var (prod) or the property in your "
                            + "active profile (dev/demo/test). Generate a secret with: "
                            + "openssl rand -base64 32");
        }
        if (LEGACY_INSECURE_DEFAULT.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "agentmanager.app.jwtSecret is set to the legacy hardcoded default. "
                            + "This value was previously shipped as a @Value fallback in the "
                            + "source tree and is therefore publicly known. Generate a new "
                            + "secret with: openssl rand -base64 32, then set JWT_SECRET (prod) "
                            + "or the property in your active profile.");
        }
        byte[] keyBytes = decodeKeyBytes(jwtSecret);
        if (keyBytes.length < MIN_HS256_KEY_BYTES) {
            throw new IllegalStateException(
                    "agentmanager.app.jwtSecret decodes to " + keyBytes.length
                            + " bytes; HS256 requires at least " + MIN_HS256_KEY_BYTES
                            + " bytes (256 bits). Generate a longer secret with: "
                            + "openssl rand -base64 32");
        }
    }

    public String generateJwtToken(Authentication authentication) {
        UserDetailsImpl userPrincipal = (UserDetailsImpl) authentication.getPrincipal();

        JwtBuilder builder = Jwts.builder()
                .setIssuer(jwtIssuer)
                .setAudience(jwtAudience)
                .setSubject((userPrincipal.getUsername()))
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + jwtExpirationMs));

        if (userPrincipal.getOrgId() != null && !userPrincipal.getOrgId().isBlank()) {
            builder.claim("org_id", userPrincipal.getOrgId());
        }

        return builder.signWith(getSignInKey(), SignatureAlgorithm.HS256).compact();
    }

    private Key getSignInKey() {
        return Keys.hmacShaKeyFor(decodeKeyBytes(jwtSecret));
    }

    private static byte[] decodeKeyBytes(String secret) {
        try {
            return Decoders.BASE64.decode(secret);
        } catch (Exception e) {
            // Fallback for plain-text (non-Base64) secrets set via env var or profile.
            return secret.getBytes();
        }
    }

    /**
     * Centralized parser builder so every parse site (validate, name, orgId) enforces
     * the same iss/aud contract. {@code requireIssuer}/{@code requireAudience} throw
     * {@link MissingClaimException} when the claim is absent and
     * {@link IncorrectClaimException} when it mismatches — both surface to
     * {@link #validateJwtToken} via the {@link MalformedJwtException} catch.
     */
    private JwtParser parser() {
        return Jwts.parserBuilder()
                .setSigningKey(getSignInKey())
                .requireIssuer(jwtIssuer)
                .requireAudience(jwtAudience)
                .build();
    }

    public String getUserNameFromJwtToken(String token) {
        return parser().parseClaimsJws(token).getBody().getSubject();
    }

    public String getOrgIdFromJwtToken(String token) {
        Object claim = parser().parseClaimsJws(token).getBody().get("org_id");
        return claim == null ? null : claim.toString();
    }

    public boolean validateJwtToken(String authToken) {
        try {
            parser().parseClaimsJws(authToken);
            return true;
        } catch (MissingClaimException e) {
            // Token was signed with our key but omits a required iss/aud claim — most
            // likely a stale token issued before this guard landed, OR a cross-system
            // token signed with a reused secret. Either way, reject.
            logger.error("JWT token missing required claim: {}", e.getMessage());
        } catch (IncorrectClaimException e) {
            // Token has iss/aud but with the wrong value — same threat shape as above.
            logger.error("JWT token has incorrect claim: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage());
        }

        return false;
    }
}
