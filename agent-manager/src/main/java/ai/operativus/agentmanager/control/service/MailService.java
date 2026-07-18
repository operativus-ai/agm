package ai.operativus.agentmanager.control.service;

/**
 * Domain Responsibility: Outbound-email boundary. One method per "kind of email
 *   the platform sends." Production binds the {@link JavaMailSenderMailService}
 *   implementation; tests can swap in a recording stub via {@code @Primary}.
 * State: Stateless. Implementations are expected to delegate to a configured
 *   {@code JavaMailSender} (or an equivalent transport) and not buffer messages
 *   in-process — that would defeat the test-recording pattern.
 *
 * <p>Why an interface rather than calling {@code JavaMailSender} directly: every
 * email this product sends is a discrete domain operation (password reset, alert
 * notification, invite, etc.) with its own subject/body shape. Capturing each
 * intent as a method here means the call sites stay readable AND tests can assert
 * on the semantic call ("sendPasswordResetEmail to bob@... with token ...") rather
 * than dissecting a raw RFC822 message body.
 */
public interface MailService {

    /**
     * Sends a password-reset email containing the reset link.
     *
     * @param toAddress recipient (the user's {@code users.email})
     * @param resetUrl  fully-qualified URL the user clicks to land on the
     *                  reset-confirm screen. Format:
     *                  {@code https://<DOMAIN>/reset-password?token=<raw>}
     * @param ttlMinutes the token's lifetime in minutes, surfaced in the email
     *                  body so the user knows their window
     */
    void sendPasswordResetEmail(String toAddress, String resetUrl, int ttlMinutes);
}
