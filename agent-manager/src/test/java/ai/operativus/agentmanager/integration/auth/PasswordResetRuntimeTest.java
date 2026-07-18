package ai.operativus.agentmanager.integration.auth;

import ai.operativus.agentmanager.control.repository.PasswordResetTokenRepository;
import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.core.entity.PasswordResetToken;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.RecordingMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end coverage for the self-serve password-reset
 *   flow (Phase 1 #7). Composes the two-step journey via real HTTP against the
 *   integration harness: {@code POST /api/auth/password-reset/request} →
 *   recorded email with reset URL → {@code POST /api/auth/password-reset/confirm}
 *   with the token from the URL → new password persisted as a BCrypt hash.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}
 *   in {@code @AfterEach}; {@link RecordingMailService#reset()} runs in
 *   {@code @BeforeEach} so recorded emails don't leak across cases.
 *
 * <p>{@link RecordingMailService} is a {@code @Primary} stub for
 * {@link ai.operativus.agentmanager.control.service.MailService} that records
 * each {@code sendPasswordResetEmail} call into an in-memory list. This avoids
 * standing up GreenMail or a real SMTP server while still exercising the full
 * controller → service → repository → email-dispatch path.
 */
@Import({
        RecordingMailService.class,
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class
})
public class PasswordResetRuntimeTest extends BaseIntegrationTest {

    @Autowired private RecordingMailService recordingMail;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository tokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ai.operativus.agentmanager.control.service.PasswordResetService passwordResetService;

    @BeforeEach
    void clearRecordedEmails() {
        recordingMail.reset();
    }

    // ─── happy path ──────────────────────────────────────────────────────────

    @Test
    void requestThenConfirm_updatesPasswordAndConsumesToken() {
        String email = registerUserAndReturnEmail("reset-happy");
        String originalUsername = "reset-happy";
        User before = userRepository.findByUsername(originalUsername).orElseThrow();
        String originalHash = before.getPassword();

        // 1. Request — endpoint returns 200; email recorded; token row persisted.
        ResponseEntity<Void> requestResp = requestReset(email);
        assertEquals(HttpStatus.OK, requestResp.getStatusCode());
        assertEquals(1, recordingMail.sent.size(),
                "exactly one reset email must be recorded for a known-email request");
        RecordingMailService.SentEmail recorded = recordingMail.sent.get(0);
        assertEquals(email, recorded.to());
        assertTrue(recorded.resetUrl().contains("/reset-password?token="),
                "reset URL must embed the token query param; got: " + recorded.resetUrl());
        assertEquals(15, recorded.ttlMinutes(),
                "default TTL is 15 min (agentmanager.password-reset.token-ttl-minutes)");

        // Extract the raw token from the URL the user would click.
        String rawToken = recorded.resetUrl().substring(
                recorded.resetUrl().indexOf("token=") + "token=".length());

        // Token row exists, not yet consumed.
        assertEquals(1, tokenRepository.count(),
                "one token row must be persisted after a valid request");

        // 2. Confirm — endpoint returns 200; password updated; token consumed.
        String newPassword = "new-pass-after-reset-456";
        ResponseEntity<Void> confirmResp = confirmReset(rawToken, newPassword);
        assertEquals(HttpStatus.OK, confirmResp.getStatusCode());

        User after = userRepository.findByUsername(originalUsername).orElseThrow();
        assertFalse(originalHash.equals(after.getPassword()),
                "password hash MUST change after confirm — got the same value");
        assertTrue(passwordEncoder.matches(newPassword, after.getPassword()),
                "new password must verify against the stored hash via BCryptPasswordEncoder.matches");

        PasswordResetToken consumed = tokenRepository.findAll().get(0);
        assertNotNull(consumed.getConsumedAt(),
                "token row must carry consumed_at after a successful confirm");
    }

    // ─── anti-enumeration ────────────────────────────────────────────────────

    @Test
    void requestForUnknownEmail_returnsOk_andRecordsNoEmail() {
        // Important security pin: the endpoint MUST NOT reveal whether the email
        // is registered. Same 200 status, zero recorded emails.
        ResponseEntity<Void> resp = requestReset("ghost-" + System.nanoTime() + "@nowhere.invalid");
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "unknown-email request must return 200, not 404 — defeats user enumeration");
        assertEquals(0, recordingMail.sent.size(),
                "unknown-email request must NOT dispatch an email; got "
                        + recordingMail.sent.size() + " — silent-no-op contract broken");
        assertEquals(0, tokenRepository.count(),
                "unknown-email request must NOT persist a token row");
    }

    // ─── token-invalid failure modes ─────────────────────────────────────────

    @Test
    void confirmWithBogusToken_returns400_andDoesNotChangePassword() {
        String email = registerUserAndReturnEmail("reset-bogus");
        User before = userRepository.findByUsername("reset-bogus").orElseThrow();
        String originalHash = before.getPassword();

        ResponseEntity<Map<String, Object>> resp = confirmResetExpectingError(
                "this-token-was-never-issued-12345678", "new-pass-bogus-456");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "unknown token must produce 400, not 200/404");

        User after = userRepository.findByUsername("reset-bogus").orElseThrow();
        assertEquals(originalHash, after.getPassword(),
                "password hash must NOT change on a failed confirm");
        // The original email wasn't even requested — confirms passwords aren't
        // mutated by stray confirm calls.
        assertEquals(0, recordingMail.sent.size());
    }

    @Test
    void confirmWithExpiredToken_returns400_andDoesNotChangePassword() {
        String email = registerUserAndReturnEmail("reset-expired");
        requestReset(email);
        assertEquals(1, recordingMail.sent.size());
        String rawToken = extractToken(recordingMail.sent.get(0).resetUrl());

        // Age the token to before-now so it appears expired without sleeping.
        PasswordResetToken row = tokenRepository.findAll().get(0);
        ReflectionTestUtils.setField(row, "expiresAt", LocalDateTime.now().minusMinutes(1));
        tokenRepository.save(row);

        ResponseEntity<Map<String, Object>> resp = confirmResetExpectingError(rawToken, "new-pass-expired-456");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "expired token must yield 400 — same shape as bogus token (no enum)");

        PasswordResetToken after = tokenRepository.findById(row.getId()).orElseThrow();
        assertEquals(null, after.getConsumedAt(),
                "expired-token confirm must NOT mark the row consumed — would obscure the audit trail");
    }

    @Test
    void confirmReplay_returns400_onSecondAttempt() {
        // First confirm: succeeds. Second confirm with the same token: rejected.
        String email = registerUserAndReturnEmail("reset-replay");
        requestReset(email);
        String rawToken = extractToken(recordingMail.sent.get(0).resetUrl());

        assertEquals(HttpStatus.OK, confirmReset(rawToken, "new-pass-replay-456").getStatusCode());

        ResponseEntity<Map<String, Object>> replay = confirmResetExpectingError(
                rawToken, "different-pass-via-replay-789");
        assertEquals(HttpStatus.BAD_REQUEST, replay.getStatusCode(),
                "replay of a consumed token must yield 400 — single-use semantics");
    }

    // ─── rate limit ──────────────────────────────────────────────────────────

    @Test
    void perUserRateLimit_silentlyDropsRequestsBeyondTheLimit() {
        // Default budget is 5 per hour. Fire 6; only the first 5 dispatch emails.
        // Sixth still returns 200 (anti-enumeration) but records nothing.
        String email = registerUserAndReturnEmail("reset-rl");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<Void> r = requestReset(email);
            assertEquals(HttpStatus.OK, r.getStatusCode(),
                    "request #" + (i + 1) + " within limit must return 200");
        }
        assertEquals(5, recordingMail.sent.size(),
                "five requests should yield five emails");

        ResponseEntity<Void> sixth = requestReset(email);
        assertEquals(HttpStatus.OK, sixth.getStatusCode(),
                "rate-limited request must still return 200 (anti-enumeration)");
        assertEquals(5, recordingMail.sent.size(),
                "sixth request must NOT dispatch an email; got "
                        + recordingMail.sent.size() + " (rate-limit bypass)");
    }

    // ─── validation ──────────────────────────────────────────────────────────

    @Test
    void confirmWithWeakPassword_returns400_andDoesNotChangePassword() {
        String email = registerUserAndReturnEmail("reset-weak");
        requestReset(email);
        String rawToken = extractToken(recordingMail.sent.get(0).resetUrl());
        User before = userRepository.findByUsername("reset-weak").orElseThrow();
        String originalHash = before.getPassword();

        // 7 chars — below the @Size(min=8) on the DTO. Validation fails before
        // the service sees it.
        ResponseEntity<Map<String, Object>> resp = confirmResetExpectingError(rawToken, "short");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());

        User after = userRepository.findByUsername("reset-weak").orElseThrow();
        assertEquals(originalHash, after.getPassword(),
                "weak-password rejection must NOT mutate the user's row");
        PasswordResetToken row = tokenRepository.findAll().get(0);
        assertEquals(null, row.getConsumedAt(),
                "weak-password rejection must NOT consume the token (user can retry with a stronger one)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String registerUserAndReturnEmail(String username) {
        String email = username + "@test.local";
        // The base class authenticateAs() registers + logs in; we only need the
        // registration side-effect here.
        authenticateAs(username, email, "starting-pass-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
        return email;
    }

    private ResponseEntity<Void> requestReset(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/auth/password-reset/request"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email), h),
                Void.class);
    }

    private ResponseEntity<Void> confirmReset(String token, String newPassword) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/auth/password-reset/confirm"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token, "newPassword", newPassword), h),
                Void.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> confirmResetExpectingError(String token, String newPassword) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/auth/password-reset/confirm"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token, "newPassword", newPassword), h),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private static String extractToken(String resetUrl) {
        int idx = resetUrl.indexOf("token=");
        return resetUrl.substring(idx + "token=".length());
    }
}
