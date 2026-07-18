package com.operativus.agentmanager.control.repository;

import com.operativus.agentmanager.core.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Lookup by SHA-256 of the raw token presented at confirm. Returns Optional so a
     * forged token quietly returns empty (the confirm endpoint maps both empty and
     * expired/consumed to the same 400 — no enumeration of which tokens have existed).
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Rate-limit input: how many tokens has this user requested in the last window.
     * Counts ALL rows for the user regardless of consumed status — a request that's
     * been consumed still counts toward the per-user rate limit so an attacker who
     * has compromised a user's email can't spam reset requests by burning each one.
     */
    @Query("SELECT COUNT(t) FROM PasswordResetToken t " +
           "WHERE t.userId = :userId AND t.requestedAt >= :since")
    long countRequestsForUserSince(@Param("userId") UUID userId,
                                   @Param("since") LocalDateTime since);

    /**
     * Retention sweep — DataRetentionService can call this periodically. Deletes any
     * row whose token has been expired for the configured retention window. Consumed
     * rows are also pruned via the same {@code expires_at} predicate since their
     * expiry is also in the past by the time the retention window elapses.
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
