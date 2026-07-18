package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.control.dto.SkillAgentBindingDTO;
import com.operativus.agentmanager.control.dto.SkillRequest;
import com.operativus.agentmanager.control.dto.SkillResponse;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.AgentSkillRepository;
import com.operativus.agentmanager.control.repository.SkillRepository;
import com.operativus.agentmanager.core.entity.AgentSkill;
import com.operativus.agentmanager.core.entity.Skill;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.registry.SkillOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Domain Responsibility: Manages Skill lifecycle (CRUD, agent attach/detach) and implements
 *     the {@link SkillOperations} SPI so the compute layer can resolve active skills at run time
 *     without depending on this concrete class.
 * State: Stateless
 */
@Service
public class SkillService implements SkillOperations {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

    private final SkillRepository skillRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;

    public SkillService(SkillRepository skillRepository,
                        AgentSkillRepository agentSkillRepository,
                        AgentRepository agentRepository) {
        this.skillRepository = skillRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.agentRepository = agentRepository;
    }

    // --- SkillOperations SPI ---

    @Override
    public List<Skill> findActiveSkillsForAgent(String agentId) {
        return skillRepository.findActiveSkillsForAgent(agentId);
    }

    // --- CRUD ---

    @Transactional
    public SkillResponse createSkill(SkillRequest request) {
        String orgId = callerOrgId();
        validateToolNames(request.allowedTools());
        if (skillRepository.existsByOrgIdAndName(orgId, request.name())) {
            throw new IllegalArgumentException("Skill with name '" + request.name() + "' already exists in this organization");
        }
        Skill skill = new Skill(UUID.randomUUID().toString(), orgId, request.name(),
                request.description(), request.systemPromptSnippet());
        if (request.allowedTools() != null) {
            skill.setAllowedTools(new HashSet<>(request.allowedTools()));
        }
        if (request.active() != null) {
            skill.setActive(request.active());
        }
        return toResponse(skillRepository.save(skill));
    }

    public SkillResponse getSkill(String id) {
        return skillRepository.findByIdAndOrgId(id, callerOrgId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
    }

    public Page<SkillResponse> listSkills(Pageable pageable) {
        return skillRepository.findAllByOrgId(callerOrgId(), pageable).map(this::toResponse);
    }

    @Transactional
    public SkillResponse updateSkill(String id, SkillRequest request) {
        String orgId = callerOrgId();
        validateToolNames(request.allowedTools());
        Skill skill = skillRepository.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
        if (!skill.getName().equals(request.name())
                && skillRepository.existsByOrgIdAndName(orgId, request.name())) {
            throw new IllegalArgumentException("Skill with name '" + request.name() + "' already exists in this organization");
        }
        skill.setName(request.name());
        skill.setDescription(request.description());
        skill.setSystemPromptSnippet(request.systemPromptSnippet());
        if (request.allowedTools() != null) {
            skill.setAllowedTools(new HashSet<>(request.allowedTools()));
        }
        if (request.active() != null) {
            skill.setActive(request.active());
        }
        return toResponse(skillRepository.save(skill));
    }

    @Transactional
    public void deleteSkill(String id) {
        String orgId = callerOrgId();
        if (!skillRepository.existsByIdAndOrgId(id, orgId)) {
            throw new ResourceNotFoundException("Skill", id);
        }
        agentSkillRepository.deleteAllBySkillId(id);
        skillRepository.deleteById(id);
    }

    // --- Attach / Detach ---

    @Transactional
    public void attachSkill(String agentId, String skillId, Integer priority) {
        String orgId = callerOrgId();
        if (!agentRepository.existsByIdAndOrgId(agentId, orgId)) {
            throw new ResourceNotFoundException("Agent", agentId);
        }
        if (!skillRepository.existsByIdAndOrgId(skillId, orgId)) {
            throw new ResourceNotFoundException("Skill", skillId);
        }
        if (!agentSkillRepository.existsByAgentIdAndSkillId(agentId, skillId)) {
            agentSkillRepository.save(new AgentSkill(agentId, skillId, priority));
        }
    }

    @Transactional
    public void detachSkill(String agentId, String skillId) {
        String orgId = callerOrgId();
        if (!agentRepository.existsByIdAndOrgId(agentId, orgId)) {
            throw new ResourceNotFoundException("Agent", agentId);
        }
        agentSkillRepository.deleteByAgentIdAndSkillId(agentId, skillId);
    }

    /**
     * Lists the agents a skill is attached to (priority ASC), for the admin "manage agents"
     * view. Tenant-scoped: a skill outside the caller's org yields 404 (existence-leak rule).
     */
    @Transactional(readOnly = true)
    public List<SkillAgentBindingDTO> listAgentsForSkill(String skillId) {
        String orgId = callerOrgId();
        if (!skillRepository.existsByIdAndOrgId(skillId, orgId)) {
            throw new ResourceNotFoundException("Skill", skillId);
        }
        return agentSkillRepository.findBySkillIdOrderByPriorityAscCreatedAtAsc(skillId).stream()
                .map(b -> new SkillAgentBindingDTO(b.getAgentId(), b.getPriority()))
                .toList();
    }

    // --- Internal helpers ---

    private void validateToolNames(Set<String> toolNames) {
        if (toolNames == null) return;
        for (String name : toolNames) {
            if (name == null || name.isBlank() || !TOOL_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "Invalid tool name: '" + name + "'. Must match [a-z][a-z0-9_]*");
            }
        }
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }

    private SkillResponse toResponse(Skill s) {
        return new SkillResponse(
                s.getId(), s.getOrgId(), s.getName(), s.getDescription(),
                s.getSystemPromptSnippet(), s.getAllowedTools(), s.getActive(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
