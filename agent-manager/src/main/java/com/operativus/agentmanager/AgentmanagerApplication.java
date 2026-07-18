package com.operativus.agentmanager;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Domain Responsibility: The main entry point for the Agent Manager Spring Boot application. Bootstraps the context and enables Core capabilities such as Caching, Async execution, Scheduling, JPA Auditing, and Retry policies.
 * State: Stateless (Runner)
 */
@SpringBootApplication(excludeName = "org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration")
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
@org.springframework.scheduling.annotation.EnableAsync(proxyTargetClass = true)
@org.springframework.scheduling.annotation.EnableScheduling
@EnableCaching(proxyTargetClass = true)
@EnableJpaAuditing
@EnableRetry
public class AgentmanagerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AgentmanagerApplication.class)
            .initializers(new com.operativus.agentmanager.compute.DynamicProviderInitializer())
            .run(args);
    }
}
