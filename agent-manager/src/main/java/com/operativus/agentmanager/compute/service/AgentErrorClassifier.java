package com.operativus.agentmanager.compute.service;

/**
 * Domain Responsibility: Classifies AI provider exceptions into user-facing error categories.
 * State: Stateless (static utility)
 */
final class AgentErrorClassifier {

    private AgentErrorClassifier() {}

    static boolean isContextLimitError(Throwable e) {
        if (e == null) return false;
        if (e instanceof com.fasterxml.jackson.core.JsonParseException ||
            (e.getCause() != null && e.getCause() instanceof com.fasterxml.jackson.core.JsonParseException)) {
            return false;
        }
        java.util.List<String> signals = java.util.List.of(
            "context length exceeded", "too many tokens", "input too long", "too large", "maximum context",
            "context_length_exceeded", "context window", "exceeds the maximum", "request too large"
        );
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (signals.stream().anyMatch(lower::contains)) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean isQuotaOrRateLimitError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof org.springframework.web.client.HttpClientErrorException.TooManyRequests
                || current instanceof org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests) {
                return true;
            }
            current = current.getCause();
        }
        java.util.List<String> signals = java.util.List.of(
            "rate_limit_exceeded", "rate limit", "quota exceeded", "insufficient_quota",
            "resource_exhausted", "overloaded_error", "too many requests", "429",
            "tokens per min", "requests per min", "rpm limit", "tpm limit"
        );
        current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (signals.stream().anyMatch(lower::contains)) return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
