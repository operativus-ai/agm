package com.operativus.agentmanager.control.config.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Domain Responsibility: Intercepts Spring MVC controller execution to log HTTP request execution time and status codes.
 * State: Stateless (Interceptor)
 */
@Component
public class WebLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebLoggingInterceptor.class);
    private static final String START_TIME_ATTR = "WebLoggingInterceptor.startTime";

    /**
     * @summary Captures the start time of the incoming HTTP request.
     * @logic
     * - Checks if DEBUG logging is enabled for efficiency.
     * - Records the current system time in milliseconds.
     * - Stores the start time as a request attribute.
     * - Logs the incoming method and URI.
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        if (log.isDebugEnabled()) {
            long startTime = System.currentTimeMillis();
            request.setAttribute(START_TIME_ATTR, startTime);
            log.debug("--> {} {}", request.getMethod(), request.getRequestURI());
        }
        return true;
    }

    /**
     * @summary Calculates and logs the total execution duration of the HTTP request upon completion.
     * @logic
     * - Retrieves the previously stored start time from the request attributes.
     * - Calculates the duration (current time - start time).
     * - Logs the method, URI, duration, HTTP status code, and any uncaught exception messages.
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        if (log.isDebugEnabled()) {
            Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                if (ex != null) {
                    log.debug("<-- {} {} ({}ms) [{}] - {}", request.getMethod(), request.getRequestURI(), duration, response.getStatus(), ex.getMessage());
                } else {
                    log.debug("<-- {} {} ({}ms) [{}]", request.getMethod(), request.getRequestURI(), duration, response.getStatus());
                }
            }
        }
    }
}
