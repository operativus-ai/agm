package com.operativus.agentmanager.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Domain Responsibility: Provides the application's UTC {@link Clock} bean. Time-dependent services
 *   (e.g. {@code DailySpendService}'s daily-window boundary) inject this rather than calling
 *   {@code LocalDate.now(UTC)} directly, so tests can pin a fixed instant and assert day-windowed
 *   logic deterministically. {@link ConditionalOnMissingBean} lets a {@code @TestConfiguration}
 *   override it with a {@code Clock.fixed(...)} without a bean-definition clash.
 * State: Stateless.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
