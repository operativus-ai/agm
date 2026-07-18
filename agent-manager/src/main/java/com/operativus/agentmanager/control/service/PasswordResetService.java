package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.PasswordResetTokenRepository;
import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.PasswordResetToken;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Domain Responsibility: Self-serve password reset orchestration. Two ops:
 *   <ul>
 *     <li>{@link #requestReset} — generates a single-use token, persists its
 *         SHA-256, and dispatches the reset email. Silent on unknown emails to
 *         defeat user-enumeration via this endpoint.</li>
 *     <li>{@link #confirmReset} — validates a presented token (lookup-by-hash,
 *         not-expired, not-consumed), updates the user's bcrypt-hashed password,
 *         marks the token consumed.</li>
 *   </ul>
 * State: Stateless. All durable state lives in {@code password_reset_tokens} +
 *   the user row's password column.
 *
 * <p><strong>Security invariants:</strong>
 * <ol>
 *   <li>The raw token never leaves the {@link #requestReset} flow — only its
 *       SHA-256 hash is persisted. A DB read does not yield a usable credential.</li>
 *   <li>Single-use: {@code consumed_at} flips on confirm. A replayed token is
 *       rejected even within its TTL window.</li>
 *   <li>Per-user rate limit (default 5 / hour) so a compromised inbox can't be
 *       used to lock-out a victim's account via continuous fresh resets.</li>
 *   <li>{@link #requestReset} returns void with no signal — caller ALWAYS sees
 *       200 OK regardless of whether the email existed.</li>
 * </ol>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    // 32 bytes (256 bits) of entropy in the raw token. Base64URL-encoded yields 43
    // chars — short enough to copy from an email, large enough that brute-force
    // search via the confirm endpoint is computationally infeasible.
    private static final int RAW_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random;

    private final int ttlMinutes;
    private final int rateLimitPerHour;
    private final String publicBaseUrl;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            MailService mailService,
            PasswordEncoder passwordEncoder,
            @Value("${agentmanager.password-reset.token-ttl-minutes:15}") int ttlMinutes,
            @Value("${agentmanager.password-reset.rate-limit-per-hour:5}") int rateLimitPerHour,
            @Value("${agentmanager.app.public-url:http://localhost:3000}") String publicBaseUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.mailService = mailService;
        this.passwordEncoder = passwordEncoder;
        this.ttlMinutes = ttlMinutes;
        this.rateLimitPerHour = rateLimitPerHour;
        // Strip any trailing slash so we can safely concatenate "/reset-password?token=..."
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.random = new SecureRandom();
    }

    /**
     * Issues a reset token + emails the link. Silent on every failure path so the
     * caller can NOT distinguish "email is registered" from "email is not registered."
     * Returns even when the rate limit fires (a per-user attempt counter is checked
     * before issuing, but the caller still gets no signal).
     */
    @Transactional
    public void requestReset(String emailAddress, String requesterIp, String requesterUa) {
        Optional<User> maybeUser = userRepository.findByEmail(emailAddress);
        if (maybeUser.isEmpty()) {
            // Unknown email — silent return. Do NOT log the inbound address at info level
            // (would leak the absent-email signal to an operator with log access; not the
            // same trust boundary as the attacker but worth keeping discreet).
            log.debug("Password reset requested for unknown email; silently ignored.");
            return;
        }
        User user = maybeUser.get();

        // Per-user rate limit. Counts BOTH consumed and unconsumed tokens in the window
        // so an attacker who can read the user's inbox can't reset their password
        // repeatedly to lock them out.
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long recentCount = tokenRepository.countRequestsForUserSince(user.getId(), since);
        if (recentCount >= rateLimitPerHour) {
            log.warn("Password reset rate limit exceeded for userId={}; silently ignored.",
                    user.getId());
            return;
        }

        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        LocalDateTime now = LocalDateTime.now();
        PasswordResetToken row = new PasswordResetToken(
                tokenHash, user.getId(),
                now, now.plusMinutes(ttlMinutes),
                truncate(requesterIp, 64), truncate(requesterUa, 512));
        tokenRepository.save(row);

        String resetUrl = publicBaseUrl + "/reset-password?token=" + rawToken;
        try {
            mailService.sendPasswordResetEmail(user.getEmail(), resetUrl, ttlMinutes);
        } catch (RuntimeException e) {
            // SMTP transport failure should not roll back the issued token (the user can
            // retry; the token will simply expire harmlessly). Log + swallow so the
            // endpoint's silent-return contract holds. The token row remains as a
            // forensic record that the request happened.
            log.error("Password reset email dispatch failed for userId={}: {}",
                    user.getId(), e.getMessage());
        }
    }

    /**
     * Validates the presented token and applies the new password. Throws
     * {@link BusinessValidationException} on any failure mode (unknown / expired /
     * consumed token, weak password) — the controller maps it to a 400.
     */
    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessValidationException("Reset token is required.");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessValidationException("New password must be at least 8 characters.");
        }

        String tokenHash = sha256Hex(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessValidationException(
                        "Reset token is invalid or has expired. Request a new one."));

        LocalDateTime now = LocalDateTime.now();
        if (!token.isUsable(now)) {
            throw new BusinessValidationException(
                    "Reset token is invalid or has expired. Request a new one.");
        }

        User user = userRepository.findById(token.getUserId()).orElseThrow(() ->
                // Should not happen given the FK + ON DELETE CASCADE; treat as invalid.
                new BusinessValidationException(
                        "Reset token is invalid or has expired. Request a new one."));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.markConsumed(now);
        tokenRepository.save(token);

        log.info("Password reset confirmed for userId={}", user.getId());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String generateRawToken() {
        byte[] buf = new byte[RAW_TOKEN_BYTES];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required on every JCA provider; this branch is reachable only
            // on a broken JVM and is not worth a recoverable shape.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
