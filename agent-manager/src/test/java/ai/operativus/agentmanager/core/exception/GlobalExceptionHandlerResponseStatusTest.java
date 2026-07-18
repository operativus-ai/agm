package ai.operativus.agentmanager.core.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin that {@link GlobalExceptionHandler} honors the status carried by a
 * {@link ResponseStatusException} instead of collapsing it to 500 via the catch-all handler.
 * Regression guard for the bug where the extension SSRF guard's {@code 400} (and maintenance-mode
 * {@code 503}, approvals' {@code 400}/{@code 401}, escalations' {@code 400}) surfaced as {@code 500}.
 */
class GlobalExceptionHandlerResponseStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleResponseStatusException_honorsBadRequestStatus() {
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "extension URL rejected by SSRF guard: loopback");

        ResponseEntity<ProblemDetail> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(body.getDetail())
                .as("the caller's reason is preserved for the client, not swallowed into a generic 500 message")
                .contains("SSRF guard");
        assertThat(body.getType()).isEqualTo(URI.create("urn:problem-type:response-status"));
    }

    @Test
    void handleResponseStatusException_honorsServiceUnavailableStatus() {
        // Mirrors AgentService maintenance-mode: must reach the client as 503, not 500.
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Agent is currently in maintenance mode");

        ResponseEntity<ProblemDetail> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().getDetail()).contains("maintenance mode");
    }

    @Test
    void handleResponseStatusException_honorsUnauthorizedStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "nope");

        ResponseEntity<ProblemDetail> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
