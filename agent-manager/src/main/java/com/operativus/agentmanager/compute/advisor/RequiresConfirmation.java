package com.operativus.agentmanager.compute.advisor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Domain Responsibility: Custom annotation used to mark tool execution methods that require Human-In-The-Loop (HITL) approval before proceeding. Satisfies Requirement 5.4.
 * State: Stateless (Annotation)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresConfirmation {
}
