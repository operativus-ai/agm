package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.control.service.MailService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain Responsibility: Test-only {@link MailService} that records every
 *   dispatched email into an in-memory list. Tests inspect {@link #sent} to
 *   assert that the password-reset email landed with the expected recipient +
 *   URL + TTL — without spinning up SMTP / WireMock / GreenMail.
 * State: Mutable per test. Call {@link #reset()} in {@code @BeforeEach} to clear
 *   recorded calls so assertions don't see fixture leak across cases.
 *
 * <p>Why not GreenMail: GreenMail starts a real SMTP listener in-process which
 * works but adds startup time + flakiness; the production behaviour we care
 * about ends at {@code mailSender.send(msg)} — capturing the semantic call here
 * is sufficient + faster.
 */
@TestConfiguration
public class RecordingMailService implements MailService {

    public final List<SentEmail> sent = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    public RecordingMailService recordingMailService() {
        return this;
    }

    public void reset() {
        sent.clear();
    }

    @Override
    public void sendPasswordResetEmail(String toAddress, String resetUrl, int ttlMinutes) {
        sent.add(new SentEmail(toAddress, resetUrl, ttlMinutes));
    }

    public record SentEmail(String to, String resetUrl, int ttlMinutes) {}
}
