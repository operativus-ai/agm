package ai.operativus.agentmanager.control.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link WebConfig#validatePatterns(String[])} — the
 *   fail-fast guard that rejects the {@code https://your-production-domain.com}
 *   placeholder previously shipped in {@code application-prod.properties}. Without
 *   this guard, an operator who activates the {@code prod} profile but forgets to
 *   override the property would boot with an attacker-registrable origin pattern in
 *   the CORS allowlist.
 *
 *   <p>Mirrors the fail-fast shape established by {@code JwtUtils.validateSecretOrFail}
 *   for the legacy hardcoded JWT secret (PR #1014).
 *
 * State: Stateless (validator is a pure static function).
 */
public class WebConfigCorsPlaceholderRejectionTest {

    @Test
    void devDefaultLocalhostPatternIsAccepted() {
        assertDoesNotThrow(() ->
                WebConfig.validatePatterns(new String[]{"http://localhost:*"}));
    }

    @Test
    void multipleSafeProductionOriginsAreAccepted() {
        assertDoesNotThrow(() -> WebConfig.validatePatterns(new String[]{
                "https://app.example.com",
                "https://admin.example.com"}));
    }

    @Test
    void placeholderProductionOriginIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(new String[]{WebConfig.PLACEHOLDER_PRODUCTION_ORIGIN}));
        assertTrue(ex.getMessage().contains("placeholder"),
                "rejection message must name the cause; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(WebConfig.PLACEHOLDER_PRODUCTION_ORIGIN),
                "rejection message must echo the offending value so operators see it; got: "
                        + ex.getMessage());
    }

    @Test
    void mixedSafeAndPlaceholderOriginsAreRejected() {
        // Even with a real origin alongside, the placeholder must be rejected — otherwise
        // an operator might add a real domain "next to" the sentinel and leave the trap.
        assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(new String[]{
                        "https://app.example.com",
                        WebConfig.PLACEHOLDER_PRODUCTION_ORIGIN}));
    }

    @Test
    void placeholderWithSurroundingWhitespaceIsRejected() {
        // application.properties can leave trailing spaces after a value; the trim()
        // in validatePatterns must catch it.
        assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(new String[]{"  https://your-production-domain.com  "}));
    }

    @Test
    void nullArrayIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(null));
        assertTrue(ex.getMessage().contains("required"),
                "null patterns must surface as a clear 'required' message; got: " + ex.getMessage());
    }

    @Test
    void emptyArrayIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(new String[]{}));
    }

    @Test
    void blankEntryIsRejected() {
        // Catches the stray-comma case in application.properties:
        //   app.cors.allowed-origin-patterns=https://app.example.com,,https://admin.example.com
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                WebConfig.validatePatterns(new String[]{
                        "https://app.example.com", "", "https://admin.example.com"}));
        assertTrue(ex.getMessage().contains("blank"),
                "blank-entry rejection must name the cause; got: " + ex.getMessage());
    }
}
