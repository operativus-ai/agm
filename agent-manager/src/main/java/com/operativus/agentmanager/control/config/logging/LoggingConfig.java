package com.operativus.agentmanager.control.config.logging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

/**
 * Domain Responsibility: Configures application-level request logging formatting and behavior using Spring's built-in `CommonsRequestLoggingFilter`.
 * State: Stateless (Configuration)
 */
@Configuration
public class LoggingConfig {

    /**
     * @summary Instantiates and configures a global request logging filter.
     * @logic
     * - Includes client information (IP, session info).
     * - Includes query strings.
     * - Excludes request payload and headers to prevent accidental logging of PII or credentials.
     * - Sets a maximum payload length (though currently disabled).
     * - Prefixes logged messages with "HTTP REQUEST: ".
     */
    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter loggingFilter = new CommonsRequestLoggingFilter();
        loggingFilter.setIncludeClientInfo(true);
        loggingFilter.setIncludeQueryString(true);
        loggingFilter.setIncludePayload(false); // Can be true but risky for PII unless heavily masked
        loggingFilter.setMaxPayloadLength(10000);
        loggingFilter.setIncludeHeaders(false); // Excludes Authorization headers etc.
        loggingFilter.setAfterMessagePrefix("HTTP REQUEST: ");
        return loggingFilter;
    }
}
