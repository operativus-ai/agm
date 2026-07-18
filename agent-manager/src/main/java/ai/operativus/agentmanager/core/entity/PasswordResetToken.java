package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain Responsibility: Persistent record of a single self-serve password-reset
 *   token. The raw token is emailed to the user; only its SHA-256 hash lives here.
 *   On confirm, the server hashes the inbound token and looks up by hash, then
 *   checks expiry + not-already-consumed before updating the user's password.
 * State: Stateful (JPA entity). Insert-once / consume-once: the {@code consumedAt}
 *   field flips from null to a timestamp on successful confirm, and an already-
 *   consumed row is no longer accepted (single-use semantics).
 *
 * <p>Audit fields ({@code requesterIp}, {@code requesterUa}) are populated at
 * request time so an operator can investigate "did this reset really happen,
 * and from where" without correlating across systems.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * SHA-256 hex of the raw token. 64 chars. We never store the raw token, so a
     * DB read does not yield a usable reset-bearer credential.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Foreign key to {@code users.id}; cascade-deletes if the user is removed. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Null until the token is consumed by a successful {@code /confirm} call. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "requester_ip", length = 64)
    private String requesterIp;

    @Column(name = "requester_ua", length = 512)
    private String requesterUa;

    public PasswordResetToken() {
        // JPA
    }

    public PasswordResetToken(String tokenHash, UUID userId,
                              LocalDateTime requestedAt, LocalDateTime expiresAt,
                              String requesterIp, String requesterUa) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.requesterIp = requesterIp;
        this.requesterUa = requesterUa;
    }

    public UUID getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public UUID getUserId() { return userId; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public String getRequesterIp() { return requesterIp; }
    public String getRequesterUa() { return requesterUa; }

    public void markConsumed(LocalDateTime when) { this.consumedAt = when; }

    /** True iff the token is still usable: not consumed AND not expired at {@code now}. */
    public boolean isUsable(LocalDateTime now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }
}
