package com.operativus.agentmanager.core.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the §-AGM-clear-out follow-up — `HttpRequestMethodNotSupportedException`
 * surfaces as 405 Method Not Allowed with a structured problem-detail and the standard
 * RFC 7231 §7.4.1 Allow header advertising the supported methods. Before this handler
 * existed the catch-all `handleGenericException` returned 500 and logged "Unhandled
 * exception", which the PR #240 → #242 investigation surfaced as a smell.
 */
class GlobalExceptionHandlerMethodNotAllowedTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMethodNotAllowed_returns405WithProblemDetailAndAllowHeader() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                "PUT", Set.of("GET", "PATCH", "DELETE"));

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(body.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(body.getType()).isEqualTo(URI.create("urn:problem-type:method-not-allowed"));
        assertThat(body.getDetail())
                .as("operator-facing message describes the contract violation in plain English")
                .contains("HTTP method is not supported");
        assertThat(body.getProperties())
                .as("supported methods are exposed in the body for programmatic clients")
                .containsKey("supportedMethods");
        @SuppressWarnings("unchecked")
        List<String> supported = (List<String>) body.getProperties().get("supportedMethods");
        assertThat(supported).containsExactlyInAnyOrder("GET", "PATCH", "DELETE");
    }

    @Test
    void handleMethodNotAllowed_setsAllowHeaderPerRfc7231() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                "PUT", Set.of("PATCH"));

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotAllowed(ex);

        // RFC 7231 §7.4.1: a 405 response MUST generate an Allow header field containing
        // a list of the target resource's currently supported methods.
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getAllow())
                .as("Allow header advertises the supported set per RFC 7231")
                .containsExactly(HttpMethod.PATCH);
    }

    @Test
    void handleMethodNotAllowed_emptySupportedSet_stillReturns405WithoutAllowHeader() {
        // Edge case: a misconfigured route could throw with an empty supported set.
        // The handler must not NPE; the Allow header is omitted (RFC compliance is
        // best-effort when the supported set is unavailable).
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("PUT");

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        // No Allow header when supported set is null/empty — graceful degradation.
        assertThat(response.getHeaders().getAllow()).isEmpty();
    }
}
