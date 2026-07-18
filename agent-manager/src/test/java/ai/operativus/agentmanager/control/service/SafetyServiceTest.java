package ai.operativus.agentmanager.control.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Unit test for {@link SafetyService}. Pins the contract split that fixes
 * KB ingestion of scraped content: {@code sanitizeForStorage} must NOT hard-block reference content
 * on the prompt-injection pattern (only redact PII), while {@code sanitizeInput} keeps its
 * throw-on-injection behaviour for any prompt-path use.
 * State: Stateless.
 */
class SafetyServiceTest {

    private final SafetyService service = new SafetyService();

    private static final String INJECTIONY_DOC =
            "This doc explains Agno's prompt injection guardrail and how to stop jailbreak attempts.";

    @Test
    void sanitizeForStorage_doesNotThrowOnInjectionPattern_andReturnsContent() {
        String result = assertDoesNotThrow(() -> service.sanitizeForStorage(INJECTIONY_DOC));
        // Content preserved (no redaction triggers here), and crucially: not rejected.
        assertTrue(result.contains("jailbreak"), "injection-like reference content must still be stored");
    }

    @Test
    void sanitizeForStorage_redactsEmailAndPhone() {
        String result = service.sanitizeForStorage("contact me at jane@example.com or 555-123-4567 please");
        assertTrue(result.contains("[EMAIL_REDACTED]"), "email should be redacted");
        assertTrue(result.contains("[PHONE_REDACTED]"), "phone should be redacted");
        assertFalse(result.contains("jane@example.com"));
    }

    @Test
    void sanitizeForStorage_nullInput_returnsNull() {
        assertEquals(null, service.sanitizeForStorage(null));
    }

    @Test
    void sanitizeInput_stillThrowsOnInjection_contractPreserved() {
        assertThrows(SecurityException.class, () -> service.sanitizeInput(INJECTIONY_DOC));
    }
}
