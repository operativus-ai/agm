package com.operativus.agentmanager.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TeamTemplateTest {

    private static final Set<String> VALID_MODES = Set.of(
            "ROUTER", "SEQUENTIAL", "SWARM", "PLANNER", "COORDINATOR"
    );

    private static final Set<String> VALID_ROLES = Set.of("LEADER", "MEMBER");

    @Test
    void builtInTemplates_ReturnsNonEmptyList() {
        List<TeamTemplate> templates = TeamTemplate.builtInTemplates();
        assertFalse(templates.isEmpty());
    }

    @Test
    void builtInTemplates_AllHaveUniqueIds() {
        List<TeamTemplate> templates = TeamTemplate.builtInTemplates();
        Set<String> ids = templates.stream().map(TeamTemplate::id).collect(Collectors.toSet());
        assertEquals(templates.size(), ids.size(), "Template IDs must be unique");
    }

    @Test
    void builtInTemplates_AllHaveNonBlankNames() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            assertNotNull(t.name(), "Template name must not be null for: " + t.id());
            assertFalse(t.name().isBlank(), "Template name must not be blank for: " + t.id());
        }
    }

    @Test
    void builtInTemplates_NonCustomTemplatesHaveValidMode() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            if (!"custom".equals(t.id())) {
                assertNotNull(t.teamMode(), "Team mode must not be null for: " + t.id());
                assertTrue(VALID_MODES.contains(t.teamMode()),
                        "Invalid team mode '" + t.teamMode() + "' for: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_CustomTemplateHasNoModeOrMembers() {
        TeamTemplate custom = TeamTemplate.builtInTemplates().stream()
                .filter(t -> "custom".equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing 'custom' template"));

        assertNull(custom.teamMode());
        assertTrue(custom.members().isEmpty());
    }

    @Test
    void builtInTemplates_NonCustomTemplatesHaveMembers() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            if (!"custom".equals(t.id())) {
                assertFalse(t.members().isEmpty(), "Non-custom template must have members: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_AllMembersHaveValidRoles() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            for (TeamTemplate.TeamTemplateMember m : t.members()) {
                assertTrue(VALID_ROLES.contains(m.role()),
                        "Invalid role '" + m.role() + "' in template: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_AllMembersHaveLabels() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            for (TeamTemplate.TeamTemplateMember m : t.members()) {
                assertNotNull(m.label(), "Member label must not be null in template: " + t.id());
                assertFalse(m.label().isBlank(), "Member label must not be blank in template: " + t.id());
            }
        }
    }

    @Test
    void builtInTemplates_CoordinatorTemplateHasLeader() {
        TeamTemplate coordinator = TeamTemplate.builtInTemplates().stream()
                .filter(t -> "COORDINATOR".equals(t.teamMode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing COORDINATOR template"));

        boolean hasLeader = coordinator.members().stream()
                .anyMatch(m -> "LEADER".equals(m.role()));
        assertTrue(hasLeader, "COORDINATOR template must have a LEADER member");
    }

    @Test
    void builtInTemplates_CoversAllValidModes() {
        Set<String> coveredModes = TeamTemplate.builtInTemplates().stream()
                .map(TeamTemplate::teamMode)
                .filter(m -> m != null)
                .collect(Collectors.toSet());

        for (String mode : VALID_MODES) {
            assertTrue(coveredModes.contains(mode), "Missing template for mode: " + mode);
        }
    }

    @Test
    void builtInTemplates_NonCustomTemplatesHaveInstructions() {
        for (TeamTemplate t : TeamTemplate.builtInTemplates()) {
            if (!"custom".equals(t.id())) {
                assertNotNull(t.instructions(), "Instructions must not be null for: " + t.id());
                assertFalse(t.instructions().isBlank(), "Instructions must not be blank for: " + t.id());
            }
        }
    }
}
