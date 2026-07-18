package ai.operativus.agentmanager.compute.service;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins both classifier methods in {@link AgentErrorClassifier}.
 *   These two functions drive retry/no-retry policy across the agent runtime (8 callers
 *   total — {@code AgentService.run}, {@code AgentStreamManager.stream},
 *   {@code RunExecutionManager.runFailedPayload}, plus 5 more). Misclassification
 *   matters:
 *   <ul>
 *     <li>A context-limit error misclassified as transient → infinite retry loop</li>
 *     <li>A rate-limit error misclassified as a hard fault → no backoff, hammers
 *         the upstream provider</li>
 *     <li>A genuine error misclassified as either → wrong remediation surfaced
 *         to the operator / end user</li>
 *   </ul>
 *
 *   <p>Both methods are package-private static functions that walk the exception
 *   cause chain, so this test class must live in the same package to call them.
 *
 * State: Stateless (pure-function classifiers).
 */
public class AgentErrorClassifierTest {

    // ════════════════════════════════════════════════════════════════
    // isContextLimitError
    // ════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "context length exceeded",
            "too many tokens",
            "input too long",
            "too large",
            "maximum context",
            "context_length_exceeded",
            "context window",
            "exceeds the maximum",
            "request too large"
    })
    void isContextLimitError_topLevelMessageMatchesEachSignal_returnsTrue(String signal) {
        assertTrue(AgentErrorClassifier.isContextLimitError(new RuntimeException(signal)),
                "signal '" + signal + "' must be recognized verbatim");
    }

    @Test
    void isContextLimitError_signalMatchIsCaseInsensitive() {
        assertTrue(AgentErrorClassifier.isContextLimitError(
                new RuntimeException("CONTEXT LENGTH EXCEEDED for prompt")));
        assertTrue(AgentErrorClassifier.isContextLimitError(
                new RuntimeException("The model REQUEST was Too Large to process")));
    }

    @Test
    void isContextLimitError_signalEmbeddedInLongerMessage_returnsTrue() {
        // Real provider error messages embed the signal in a longer sentence.
        assertTrue(AgentErrorClassifier.isContextLimitError(new RuntimeException(
                "openai.BadRequestError: This model's maximum context length is 128000 tokens, "
                        + "however you requested 132456 tokens. Please reduce your prompt.")));
    }

    @Test
    void isContextLimitError_signalInCauseChain_returnsTrue() {
        Throwable root = new RuntimeException("input too long");
        Throwable mid = new RuntimeException("provider call failed", root);
        Throwable top = new IllegalStateException("agent run failed", mid);
        assertTrue(AgentErrorClassifier.isContextLimitError(top),
                "must walk the cause chain — signal is on the root, not the top");
    }

    @Test
    void isContextLimitError_noMatchingSignalAnywhere_returnsFalse() {
        Throwable e = new RuntimeException("provider connection refused", new RuntimeException("ECONNREFUSED"));
        assertFalse(AgentErrorClassifier.isContextLimitError(e));
    }

    @Test
    void isContextLimitError_jsonParseExceptionAtTop_returnsFalseEvenIfSignalInCause() {
        // Hard short-circuit: a malformed-JSON failure must NOT be classified as
        // context-limit even when downstream causes carry a matching signal — this is
        // the contract that prevents JSON-parse retries from being misrouted to
        // context-shrink remediation.
        Throwable root = new RuntimeException("context length exceeded");
        Throwable jsonFail = new JsonParseException(null, "malformed response body", root);
        assertFalse(AgentErrorClassifier.isContextLimitError(jsonFail),
                "JsonParseException at top must short-circuit to false");
    }

    @Test
    void isContextLimitError_jsonParseExceptionAsDirectCause_returnsFalseEvenIfSignalElsewhere() {
        Throwable jsonRoot = new JsonParseException(null, "malformed JSON");
        Throwable top = new RuntimeException("too many tokens", jsonRoot);
        assertFalse(AgentErrorClassifier.isContextLimitError(top),
                "JsonParseException as direct cause must short-circuit even when top message looks like a signal");
    }

    @Test
    void isContextLimitError_nullException_returnsFalse() {
        assertFalse(AgentErrorClassifier.isContextLimitError(null));
    }

    @Test
    void isContextLimitError_exceptionWithNullMessage_returnsFalse() {
        assertFalse(AgentErrorClassifier.isContextLimitError(new RuntimeException((String) null)));
    }

    @Test
    void isContextLimitError_exceptionWithEmptyMessage_returnsFalse() {
        assertFalse(AgentErrorClassifier.isContextLimitError(new RuntimeException("")));
    }

    @Test
    void isContextLimitError_deepCauseChain_walksUntilSignalFound() {
        Throwable l5 = new RuntimeException("maximum context");
        Throwable l4 = new RuntimeException("layer 4", l5);
        Throwable l3 = new RuntimeException("layer 3", l4);
        Throwable l2 = new RuntimeException("layer 2", l3);
        Throwable l1 = new RuntimeException("layer 1", l2);
        assertTrue(AgentErrorClassifier.isContextLimitError(l1),
                "must walk a deep cause chain, not give up after a few levels");
    }

    // ════════════════════════════════════════════════════════════════
    // isQuotaOrRateLimitError
    // ════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            "rate_limit_exceeded",
            "rate limit",
            "quota exceeded",
            "insufficient_quota",
            "resource_exhausted",
            "overloaded_error",
            "too many requests",
            "429",
            "tokens per min",
            "requests per min",
            "rpm limit",
            "tpm limit"
    })
    void isQuotaOrRateLimitError_topLevelMessageMatchesEachSignal_returnsTrue(String signal) {
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(new RuntimeException(signal)),
                "signal '" + signal + "' must be recognized verbatim");
    }

    @Test
    void isQuotaOrRateLimitError_httpClientErrorTooManyRequests_returnsTrue() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertTrue(ex instanceof HttpClientErrorException.TooManyRequests,
                "precondition: HttpClientErrorException.create yields the TooManyRequests subtype for 429");
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(ex),
                "TooManyRequests must be recognized by exception type even with no matching message signal");
    }

    @Test
    void isQuotaOrRateLimitError_webClientResponseTooManyRequestsInCauseChain_returnsTrue() {
        WebClientResponseException webEx = WebClientResponseException.create(
                429, "Too Many Requests", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertTrue(webEx instanceof WebClientResponseException.TooManyRequests,
                "precondition: WebClientResponseException.create yields the TooManyRequests subtype for 429");
        Throwable top = new RuntimeException("agent run failed", webEx);
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(top),
                "must walk the cause chain to find the WebClient TooManyRequests");
    }

    @Test
    void isQuotaOrRateLimitError_signalMatchIsCaseInsensitive() {
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(
                new RuntimeException("RATE LIMIT exceeded on this endpoint")));
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(
                new RuntimeException("HTTP 429 Too Many Requests")));
    }

    @Test
    void isQuotaOrRateLimitError_signalInCauseChain_returnsTrue() {
        Throwable root = new RuntimeException("quota exceeded for org");
        Throwable top = new IllegalStateException("agent run failed", root);
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(top));
    }

    @Test
    void isQuotaOrRateLimitError_otherClientError_returnsFalse() {
        // Defensive: a 404 or 401 must NOT be misclassified as rate-limited — otherwise
        // the caller would back-off-and-retry on a permanent error.
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(notFound),
                "404 must not be classified as rate-limit");

        HttpClientErrorException unauth = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(unauth),
                "401 must not be classified as rate-limit");
    }

    @Test
    void isQuotaOrRateLimitError_noMatchingSignalAnywhere_returnsFalse() {
        Throwable e = new RuntimeException("provider connection refused",
                new RuntimeException("ECONNREFUSED"));
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(e));
    }

    @Test
    void isQuotaOrRateLimitError_nullException_returnsFalse() {
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(null));
    }

    @Test
    void isQuotaOrRateLimitError_exceptionWithNullMessage_returnsFalse() {
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(new RuntimeException((String) null)));
    }

    @Test
    void isQuotaOrRateLimitError_exceptionWithEmptyMessage_returnsFalse() {
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(new RuntimeException("")));
    }

    @Test
    void isQuotaOrRateLimitError_deepCauseChain_walksUntilSignalFound() {
        Throwable l5 = new RuntimeException("insufficient_quota");
        Throwable l4 = new RuntimeException("layer 4", l5);
        Throwable l3 = new RuntimeException("layer 3", l4);
        Throwable l2 = new RuntimeException("layer 2", l3);
        Throwable l1 = new RuntimeException("layer 1", l2);
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(l1));
    }

    // ════════════════════════════════════════════════════════════════
    // Classifier independence — context-limit signals must NOT be classified
    // as rate-limit, and vice versa
    // ════════════════════════════════════════════════════════════════

    @Test
    void contextLimitSignal_isNotMisclassifiedAsRateLimit() {
        Throwable ctxErr = new RuntimeException("context length exceeded");
        assertTrue(AgentErrorClassifier.isContextLimitError(ctxErr));
        assertFalse(AgentErrorClassifier.isQuotaOrRateLimitError(ctxErr),
                "context-limit must not also trip rate-limit classifier — would cause retry storms on a non-retryable error");
    }

    @Test
    void rateLimitSignal_isNotMisclassifiedAsContextLimit() {
        Throwable rateErr = new RuntimeException("rate_limit_exceeded");
        assertTrue(AgentErrorClassifier.isQuotaOrRateLimitError(rateErr));
        assertFalse(AgentErrorClassifier.isContextLimitError(rateErr),
                "rate-limit must not also trip context-limit classifier — would surface wrong remediation");
    }
}
