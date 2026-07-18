package ai.operativus.agentmanager.core.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Centralized exception handling component for the REST API.
 * State: Stateless (Spring ControllerAdvice)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Resource Not Found");
        problemDetail.setType(URI.create("urn:problem-type:resource-not-found"));
        problemDetail.setProperty("resourceName", ex.getResourceName());
        problemDetail.setProperty("identifier", ex.getIdentifier());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFoundException(NoResourceFoundException ex) {
        // Do not log a full stack trace for standard 404 navigation errors
        log.warn("Static resource or API endpoint not found: {}", ex.getResourcePath());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The requested resource could not be found.");
        problemDetail.setTitle("Endpoint Not Found");
        problemDetail.setType(URI.create("urn:problem-type:not-found"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        // Surfaced when the request body fails to deserialize (malformed JSON, truncated
        // payload, type mismatch). Without this handler the catch-all returned 500, which
        // breaks the "client gave us bad input" contract — a 4xx is the correct semantic.
        log.warn("Malformed request body: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body could not be parsed. Ensure it is valid JSON matching the endpoint's schema.");
        problemDetail.setTitle("Malformed Request Body");
        problemDetail.setType(URI.create("urn:problem-type:malformed-request-body"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handlePayloadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        // Surfaced when a multipart upload exceeds spring.servlet.multipart.max-file-size
        // or max-request-size. Without this handler the catch-all returned 500 (or worse,
        // a misleading 404 when the body was rejected before route matching). 413 is the
        // RFC-correct status (RFC 9110 §15.5.14 — Content Too Large).
        long maxBytes = ex.getMaxUploadSize();
        log.warn("Payload too large: limit={} bytes, message={}", maxBytes, ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "The request body exceeds the server's configured size limit.");
        problemDetail.setTitle("Payload Too Large");
        problemDetail.setType(URI.create("urn:problem-type:payload-too-large"));
        if (maxBytes > 0) {
            problemDetail.setProperty("maxBytes", maxBytes);
        }
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        // Surfaced when the Content-Type the client sent isn't accepted by the matched
        // handler (e.g. text/plain at a @RequestBody endpoint). 415 is the RFC-correct
        // status; without this handler the catch-all returned 500.
        log.warn("Unsupported media type: {} (supported: {})", ex.getContentType(), ex.getSupportedMediaTypes());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "The Content-Type of the request is not supported by this endpoint.");
        problemDetail.setTitle("Unsupported Media Type");
        problemDetail.setType(URI.create("urn:problem-type:unsupported-media-type"));
        if (ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()) {
            problemDetail.setProperty("supportedMediaTypes",
                    ex.getSupportedMediaTypes().stream().map(MediaType::toString).toList());
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        // Surfaced when a client hits an existing path with the wrong verb (e.g. PUT to a
        // PATCH-only endpoint). Without this handler the catch-all returned 500 with a
        // misleading "Unhandled exception" log line. 405 is the correct semantic.
        log.warn("Method not allowed: {} (supported: {})", ex.getMessage(), ex.getSupportedHttpMethods());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "The requested HTTP method is not supported for this endpoint.");
        problemDetail.setTitle("Method Not Allowed");
        problemDetail.setType(URI.create("urn:problem-type:method-not-allowed"));
        if (ex.getSupportedHttpMethods() != null) {
            problemDetail.setProperty("supportedMethods",
                    ex.getSupportedHttpMethods().stream().map(Object::toString).toList());
        }
        var responseBuilder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Spring's HttpRequestMethodNotSupportedException carries the supported set so
        // the response can advertise it via the standard Allow header. RFC 7231 §7.4.1.
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            responseBuilder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return responseBuilder.body(problemDetail);
    }

    @ExceptionHandler({StaleDataException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleStaleDataException(Exception ex) {
        log.warn("Optimistic locking failure or stale data: {}", ex.getMessage());
        String message = ex instanceof StaleDataException ? ex.getMessage() : "The record was modified concurrently by another user.";
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        problemDetail.setTitle("Stale Data Conflict");
        problemDetail.setType(URI.create("urn:problem-type:stale-data"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        // PK and UNIQUE-constraint violations come through here. Without this handler,
        // a duplicate POST falls to the generic Exception handler and returns 500.
        // The Throwable.getMessage() chain often carries the constraint name; surface
        // a hint so operators can disambiguate which key collided.
        String message = "A record with the same identifier or unique value already exists.";
        Throwable cause = ex.getMostSpecificCause();
        String hint = cause != null ? cause.getMessage() : ex.getMessage();
        log.warn("Data integrity violation: {}", hint);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        problemDetail.setTitle("Resource Already Exists");
        problemDetail.setType(URI.create("urn:problem-type:resource-already-exists"));
        if (hint != null) {
            problemDetail.setProperty("hint", hint);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<ProblemDetail> handleBusinessValidationException(BusinessValidationException ex) {
        log.warn("Business validation failed: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Business Validation Error");
        problemDetail.setType(URI.create("urn:problem-type:business-validation-error"));
        problemDetail.setProperty("errors", ex.getErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationExceptions(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        log.warn("Method argument validation failed: {}", ex.getMessage());
        
        List<java.util.Map<String, String>> invalidParams = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> java.util.Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value"
                ))
                .collect(Collectors.toList());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for one or more arguments.");
        problemDetail.setTitle("Invalid Request Content");
        problemDetail.setType(URI.create("urn:problem-type:invalid-request"));
        problemDetail.setProperty("invalidParams", invalidParams);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Invalid Argument");
        problemDetail.setType(URI.create("urn:problem-type:invalid-argument"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentialsException(org.springframework.security.authentication.BadCredentialsException ex) {
        log.warn("Failed login attempt: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        problemDetail.setTitle("Unauthorized Access");
        problemDetail.setType(URI.create("urn:problem-type:unauthorized"));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.security.authentication.AccountStatusException.class)
    public ResponseEntity<ProblemDetail> handleAccountStatusException(org.springframework.security.authentication.AccountStatusException ex) {
        log.warn("Login blocked by account status ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Account is not available.");
        problemDetail.setTitle("Unauthorized Access");
        problemDetail.setType(URI.create("urn:problem-type:unauthorized"));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied.");
        problemDetail.setTitle("Forbidden");
        problemDetail.setType(URI.create("urn:problem-type:forbidden"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<ProblemDetail> handleTooManyRequestsException(org.springframework.web.client.HttpClientErrorException.TooManyRequests ex) {
        log.warn("Upstream rate limit or quota exceeded: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "The AI provider's rate limit or token quota has been exceeded. Please wait a moment and try again.");
        problemDetail.setTitle("Rate Limit Exceeded");
        problemDetail.setType(URI.create("urn:problem-type:rate-limit-exceeded"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceededException(RateLimitExceededException ex) {
        log.warn("Per-model rate limit exceeded: {}", ex.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problemDetail.setTitle("Rate Limit Exceeded");
        problemDetail.setType(URI.create("urn:problem-type:model-rate-limit-exceeded"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    /**
     * Maps {@link ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException}
     * to <strong>HTTP 402 Payment Required</strong>. This is a deliberate semantic choice over
     * 429 Too Many Requests: budget exhaustion in this session does NOT recover by retrying —
     * the only successful resolutions are (1) ending the session, (2) raising the ceiling, or
     * (3) routing through a different budget. Mapping it as 429 would invite client auto-retry
     * loops that burn additional tokens without ever succeeding.
     *
     * <p>Public contract pinned by {@code GlobalExceptionHandlerFinOpsTest}. Drift in the
     * status code, content type, or {@code cumulativeUsd} / {@code budgetCeilingUsd} body
     * fields is a breaking change for integration clients.
     */
    @ExceptionHandler(ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException.class)
    public ResponseEntity<ProblemDetail> handleFinOpsBudgetExhausted(
            ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException ex) {
        log.warn("FinOps budget exhausted — session={} agent={} run={} model={} cumulative=${} ceiling=${}",
                ex.getSessionId(), ex.getAgentId(), ex.getRunId(), ex.getModelId(),
                String.format("%.4f", ex.getCumulativeUsd()),
                String.format("%.4f", ex.getBudgetCeilingUsd()));
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED,
                "Session has exceeded its configured FinOps budget ceiling. " +
                "Retrying will not succeed within this session — end the session, raise the ceiling, " +
                "or route through a different budget.");
        problemDetail.setTitle("Budget Exhausted");
        problemDetail.setType(URI.create("urn:problem-type:finops-budget-exhausted"));
        problemDetail.setProperty("sessionId", ex.getSessionId());
        problemDetail.setProperty("agentId", ex.getAgentId());
        problemDetail.setProperty("runId", ex.getRunId());
        problemDetail.setProperty("modelId", ex.getModelId());
        problemDetail.setProperty("cumulativeUsd", ex.getCumulativeUsd());
        problemDetail.setProperty("budgetCeilingUsd", ex.getBudgetCeilingUsd());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    /**
     * Maps {@link ai.operativus.agentmanager.control.finops.exception.DailyBudgetExceededException}
     * to <strong>HTTP 402 Payment Required</strong> — same status + anti-retry rationale as the
     * per-session ceiling (retrying within the same UTC day will not succeed until the cap resets
     * or is raised). Distinct exception so the preflight daily gate stays decoupled from the
     * mid-flight HITL-pause path.
     */
    @ExceptionHandler(ai.operativus.agentmanager.control.finops.exception.DailyBudgetExceededException.class)
    public ResponseEntity<ProblemDetail> handleDailyBudgetExceeded(
            ai.operativus.agentmanager.control.finops.exception.DailyBudgetExceededException ex) {
        log.warn("FinOps daily budget exhausted — org={} todaySpend=${} cap=${}",
                ex.getOrgId(), String.format("%.4f", ex.getCurrentSpendUsd()),
                String.format("%.4f", ex.getDailyCapUsd()));
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED,
                "Your organization has reached its daily FinOps spend cap. Requests will succeed " +
                "again after the daily window resets (UTC midnight) or once the cap is raised.");
        problemDetail.setTitle("Daily Budget Exhausted");
        problemDetail.setType(URI.create("urn:problem-type:finops-daily-budget-exhausted"));
        problemDetail.setProperty("orgId", ex.getOrgId());
        problemDetail.setProperty("currentSpendUsd", ex.getCurrentSpendUsd());
        problemDetail.setProperty("dailyCapUsd", ex.getDailyCapUsd());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    /**
     * Honor the status a controller/service explicitly declared via {@link
     * org.springframework.web.server.ResponseStatusException} (e.g. the extension SSRF guard's
     * 400, an agent's maintenance-mode 503, approvals' 400/401) instead of letting the catch-all
     * {@link #handleGenericException} collapse it to a misleading 500. More specific than
     * {@code Exception.class}, so Spring routes {@code ResponseStatusException} here.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException ex) {
        var status = ex.getStatusCode();
        String detail = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        if (status.is5xxServerError()) {
            log.error("ResponseStatusException ({})", status, ex);
        } else {
            log.warn("ResponseStatusException ({}): {}", status, detail);
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle("Request Failed");
        problemDetail.setType(URI.create("urn:problem-type:response-status"));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        log.error("Unhandled exception occurred", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("urn:problem-type:internal-server-error"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }
}
