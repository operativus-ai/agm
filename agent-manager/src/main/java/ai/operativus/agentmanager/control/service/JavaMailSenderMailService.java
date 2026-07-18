package ai.operativus.agentmanager.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Production {@link MailService} backed by Spring's
 *   {@link JavaMailSender} (which in turn wraps Jakarta Mail). Composes the
 *   plain-text subject/body for each email type from server-side templates so
 *   user-supplied content never reaches the mail body unsanitized.
 * State: Stateless. The {@link JavaMailSender} bean is configured by Spring Boot
 *   from {@code spring.mail.*} properties; this service only assembles messages.
 *
 * <p>Plain-text only by design. HTML templates add an injection surface
 * (HTML-escaping the reset URL would still leak the URL itself through any
 * downstream parser quirk) and are not needed at v1. Migrate to a templating
 * engine + multipart messages when the product needs branded notifications.
 */
@Service
public class JavaMailSenderMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(JavaMailSenderMailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String productName;

    public JavaMailSenderMailService(
            JavaMailSender mailSender,
            @Value("${agentmanager.mail.from-address:noreply@example.com}") String fromAddress,
            @Value("${agentmanager.mail.product-name:Agent Manager}") String productName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.productName = productName;
    }

    @Override
    public void sendPasswordResetEmail(String toAddress, String resetUrl, int ttlMinutes) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(toAddress);
        msg.setSubject(productName + " — password reset");
        msg.setText(String.format("""
                A password reset was requested for your %s account.

                If this was you, click the link below within the next %d minutes to
                set a new password:

                %s

                If this was not you, you can safely ignore this email — your password
                has not been changed.

                — %s
                """, productName, ttlMinutes, resetUrl, productName));

        // Spring's send() throws MailException on transport failure (auth, network,
        // recipient-refused). We let that propagate up to PasswordResetService, which
        // logs + masks the failure so the caller still gets a 200 (no enumeration).
        mailSender.send(msg);
        if (log.isDebugEnabled()) {
            log.debug("Sent password reset email to {} (ttl={}min)", toAddress, ttlMinutes);
        }
    }
}
