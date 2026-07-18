package ai.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTOs for the self-serve password reset flow on
 *   {@code AuthController}. Two records — one per endpoint.
 * State: Immutable records.
 */
public final class PasswordResetDtos {

    private PasswordResetDtos() {
        // not instantiable
    }

    /**
     * Body for {@code POST /api/auth/password-reset/request}.
     *
     * <p>The endpoint always returns 200 regardless of whether the email is known
     * to the system (anti-enumeration). Validation here only screens for clearly
     * malformed input — a present-but-malformed email yields 400 by design,
     * while a well-formed-but-unknown email yields 200.
     */
    public record PasswordResetRequest(
            @NotBlank @Email @Size(max = 255) String email
    ) {}

    /**
     * Body for {@code POST /api/auth/password-reset/confirm}. Token is the raw
     * value the user copied from their email; the service hashes it server-side
     * before lookup.
     *
     * <p>{@code @Size(min = 8)} on {@code newPassword} is the controller-layer
     * minimum; the service applies the same check defensively. 128 ceiling
     * prevents pathological inputs (BCrypt itself truncates at 72 chars, but
     * accepting 128 lets a strict password manager submit its full output).
     */
    public record PasswordResetConfirm(
            @NotBlank @Size(min = 16, max = 256) String token,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}
}
