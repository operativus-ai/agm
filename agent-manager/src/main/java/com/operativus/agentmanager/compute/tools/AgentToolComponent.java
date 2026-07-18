package com.operativus.agentmanager.compute.tools;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Domain Responsibility: Custom stereotype mapping to Spring's @Component for automatic Context-wide discovery of Agent tools.
 * State: Stateless
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AgentToolComponent {
}
