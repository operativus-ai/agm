package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Signals that an internal rate limit (typically the per-model RPM
 * override from §6 M-12) has rejected a request. Caller is expected to surface a 429.
 * Distinct from {@code HttpClientErrorException.TooManyRequests}, which is the upstream
 * provider's 429; this exception is AGM's own gate firing at the edge so the request
 * never reaches the provider in the first place.
 *
 * State: Stateless / immutable carrier.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
