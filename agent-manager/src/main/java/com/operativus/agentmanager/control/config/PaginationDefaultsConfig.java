package com.operativus.agentmanager.control.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Domain Responsibility: Caps the maximum page size for every paginated read endpoint —
 * the customizer bean enforces the cap on Spring Data {@link org.springframework.data.domain.Pageable}
 * arguments resolved from the request, and {@link #clampedPageRequest(int, int)} enforces the same
 * cap on controllers that build {@link PageRequest} manually from raw {@code @RequestParam} values
 * (those bypass the resolver).
 * State: Stateless (Configuration)
 */
@Configuration
public class PaginationDefaultsConfig {

    /** matches Spring Data REST default; tunable via configuration property if ops requests */
    public static final int MAX_PAGE_SIZE = 200;

    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableResolverCustomizer() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }

    /**
     * @summary Builds a {@link PageRequest} with {@code size} clamped at {@link #MAX_PAGE_SIZE}.
     *     Use from controllers that read {@code page}/{@code size} as raw {@code @RequestParam}
     *     values — those bypass the resolver bean.
     */
    public static PageRequest clampedPageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
