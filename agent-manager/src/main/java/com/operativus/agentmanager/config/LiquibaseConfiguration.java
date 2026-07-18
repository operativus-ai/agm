package com.operativus.agentmanager.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class LiquibaseConfiguration {

    /**
     * This custom {@link SpringLiquibase} bean REPLACES Spring Boot's autoconfigured one (kept for
     * {@code setClearCheckSums(true)} + the entityManagerFactory dependsOn ordering below). Because
     * it replaces the autoconfig, it must apply {@code spring.liquibase.contexts} itself — the
     * autoconfig's wiring of that property does NOT run for a user-defined bean. Without this,
     * contexts stayed null and Liquibase ran EVERY changeset, including the {@code context:"demo"}
     * seed data — leaking demo agents/users/jobs into prod (found by the 2026-06-17 prod dry-run,
     * pinned by LiquibaseDemoContextExclusionRuntimeTest). The runtime context is a positive
     * active-context list ("app" by default); demo changesets are tagged {@code context:"demo"} and
     * are excluded unless "demo" is among the active contexts (seeded manually via
     * {@code ./mvnw liquibase:update -Dliquibase.contexts=demo}).
     */
    @Bean
    public SpringLiquibase liquibase(DataSource dataSource,
                                     @Value("${spring.liquibase.contexts:}") String contexts) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        liquibase.setShouldRun(true);
        liquibase.setClearCheckSums(true);
        if (contexts != null && !contexts.isBlank()) {
            liquibase.setContexts(contexts);
        }
        return liquibase;
    }

    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor liquibaseEntityManagerFactoryDependencyPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                String[] dependsOn = beanFactory.getBeanDefinition("entityManagerFactory").getDependsOn();
                if (dependsOn == null) {
                    beanFactory.getBeanDefinition("entityManagerFactory").setDependsOn("liquibase");
                } else {
                    java.util.List<String> dependsOnList = new java.util.ArrayList<>(java.util.Arrays.asList(dependsOn));
                    dependsOnList.add("liquibase");
                    beanFactory.getBeanDefinition("entityManagerFactory").setDependsOn(dependsOnList.toArray(new String[0]));
                }
            }
        };
    }
}
