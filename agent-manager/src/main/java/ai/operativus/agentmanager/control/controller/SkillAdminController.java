package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.dto.SkillAgentBindingDTO;
import ai.operativus.agentmanager.control.dto.SkillRequest;
import ai.operativus.agentmanager.control.dto.SkillResponse;
import ai.operativus.agentmanager.control.service.SkillService;
import jakarta.validation.Valid;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: REST surface for admin-managed Skill lifecycle (CRUD + agent attach/detach).
 *     Gated by {@code agm.skills.enabled=true} — when disabled, the bean is not registered and
 *     all paths return 404 naturally (no controller, no route). Admin role required for every
 *     endpoint (non-admins get 403 via the security gate).
 * State: Stateless
 */
@RestController
@RequestMapping("/api/v1/skills")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "agm.skills.enabled", havingValue = "true")
public class SkillAdminController {

    private final SkillService skillService;

    public SkillAdminController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public ResponseEntity<Page<SkillResponse>> listSkills(Pageable pageable) {
        return ResponseEntity.ok(skillService.listSkills(pageable));
    }

    @PostMapping
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody SkillRequest request) {
        return ResponseEntity.status(201).body(skillService.createSkill(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillResponse> getSkill(@PathVariable String id) {
        return ResponseEntity.ok(skillService.getSkill(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillResponse> updateSkill(@PathVariable String id,
                                                     @Valid @RequestBody SkillRequest request) {
        return ResponseEntity.ok(skillService.updateSkill(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        skillService.deleteSkill(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{skillId}/agents")
    public ResponseEntity<List<SkillAgentBindingDTO>> listSkillAgents(@PathVariable String skillId) {
        return ResponseEntity.ok(skillService.listAgentsForSkill(skillId));
    }

    @PostMapping("/{skillId}/agents/{agentId}")
    public ResponseEntity<Void> attachSkill(@PathVariable String skillId,
                                            @PathVariable String agentId,
                                            @RequestParam(required = false) Integer priority) {
        skillService.attachSkill(agentId, skillId, priority);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{skillId}/agents/{agentId}")
    public ResponseEntity<Void> detachSkill(@PathVariable String skillId,
                                            @PathVariable String agentId) {
        skillService.detachSkill(agentId, skillId);
        return ResponseEntity.noContent().build();
    }
}
