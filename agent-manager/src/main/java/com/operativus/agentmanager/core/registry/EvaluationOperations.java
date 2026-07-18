package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.entity.EvaluationCase;
import com.operativus.agentmanager.core.entity.EvaluationRun;
import com.operativus.agentmanager.core.entity.EvaluationSuite;

/**
 * Domain Responsibility: Registry contract to access Evaluation operations from the Control plane.
 */
public interface EvaluationOperations {
    EvaluationSuite createSuite(String name, String description, String createdBy);
    EvaluationCase addCaseToSuite(String suiteId, String name, String input, String expectedOutput, String promptOverride);
    EvaluationRun runSuite(String suiteId, String agentId);
}
