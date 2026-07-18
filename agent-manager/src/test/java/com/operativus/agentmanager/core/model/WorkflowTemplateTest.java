package com.operativus.agentmanager.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTemplateTest {

    @Test
    void builtInTemplates_ReturnsNonEmptyList() {
        List<WorkflowTemplate> templates = WorkflowTemplate.builtInTemplates();
        assertFalse(templates.isEmpty());
    }

    @Test
    void builtInTemplates_AllHaveUniqueIds() {
        List<WorkflowTemplate> templates = WorkflowTemplate.builtInTemplates();
        Set<String> ids = templates.stream().map(WorkflowTemplate::id).collect(Collectors.toSet());
        assertEquals(templates.size(), ids.size(), "Template IDs must be unique");
    }

    @Test
    void builtInTemplates_AllHaveNonBlankNames() {
        for (WorkflowTemplate t : WorkflowTemplate.builtInTemplates()) {
            assertNotNull(t.name(), "Template name must not be null for: " + t.id());
            assertFalse(t.name().isBlank(), "Template name must not be blank for: " + t.id());
        }
    }

    @Test
    void builtInTemplates_CustomTemplateHasEmptySteps() {
        WorkflowTemplate custom = WorkflowTemplate.builtInTemplates().stream()
                .filter(t -> "custom".equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing 'custom' template"));

        assertTrue(custom.steps().isEmpty());
    }

    @Test
    void builtInTemplates_NonCustomTemplatesHaveSteps() {
        for (WorkflowTemplate t : WorkflowTemplate.builtInTemplates()) {
            if (!"custom".equals(t.id())) {
                assertFalse(t.steps().isEmpty(), "Non-custom template must have steps: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_StepsHaveSequentialOrder() {
        for (WorkflowTemplate t : WorkflowTemplate.builtInTemplates()) {
            List<WorkflowTemplate.WorkflowTemplateStep> steps = t.steps();
            for (int i = 0; i < steps.size(); i++) {
                assertEquals(i + 1, steps.get(i).stepOrder(),
                        "Step order must be sequential starting at 1 for template: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_StepsHaveValidTypes() {
        Set<String> validTypes = Set.of("AGENT", "CONDITION", "PARALLEL", "WEBHOOK", "LOOP");
        for (WorkflowTemplate t : WorkflowTemplate.builtInTemplates()) {
            for (WorkflowTemplate.WorkflowTemplateStep step : t.steps()) {
                assertTrue(validTypes.contains(step.stepType()),
                        "Invalid step type '" + step.stepType() + "' in template: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_IncludesSupportTriageWithCondition() {
        WorkflowTemplate triage = WorkflowTemplate.builtInTemplates().stream()
                .filter(t -> "support_triage".equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing 'support_triage' template"));

        boolean hasCondition = triage.steps().stream()
                .anyMatch(s -> "CONDITION".equals(s.stepType()));
        assertTrue(hasCondition, "Support triage template should include a CONDITION step");
    }
}
