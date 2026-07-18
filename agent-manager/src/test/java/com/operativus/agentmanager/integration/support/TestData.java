package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.repository.ScheduleRepository;
import com.operativus.agentmanager.control.repository.TeamRepository;
import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.control.repository.WorkflowRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.entity.KnowledgeBase;
import com.operativus.agentmanager.core.entity.Schedule;
import com.operativus.agentmanager.core.entity.Team;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.entity.Workflow;
import com.operativus.agentmanager.core.model.enums.RoleType;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Domain Responsibility: Persistence-backed test fixture builders. Each method
 *   constructs a minimal-valid entity, persists it via the production repository,
 *   and returns the saved instance. Tests should call these instead of inline
 *   {@code new Entity()} so the rules around required columns, defaults, and
 *   FK relationships live in one place.
 * State: Stateless (delegates to injected repositories).
 *
 * Usage: {@code @Import(TestData.class)} on the test class, then
 *   {@code @Autowired protected TestData td;}.
 */
public class TestData {

    private final UserRepository users;
    private final AgentRepository agents;
    private final KnowledgeBaseRepository knowledgeBases;
    private final TeamRepository teams;
    private final WorkflowRepository workflows;
    private final ScheduleRepository schedules;
    private final RunRepository runs;
    private final PasswordEncoder passwordEncoder;

    public TestData(UserRepository users,
                    AgentRepository agents,
                    KnowledgeBaseRepository knowledgeBases,
                    TeamRepository teams,
                    WorkflowRepository workflows,
                    ScheduleRepository schedules,
                    RunRepository runs,
                    PasswordEncoder passwordEncoder) {
        this.users = users;
        this.agents = agents;
        this.knowledgeBases = knowledgeBases;
        this.teams = teams;
        this.workflows = workflows;
        this.schedules = schedules;
        this.runs = runs;
        this.passwordEncoder = passwordEncoder;
    }

    public User user(String username, String email, String rawPassword, RoleType... roles) {
        User u = new User(username, email, passwordEncoder.encode(rawPassword));
        Set<RoleType> roleSet = new HashSet<>();
        if (roles.length == 0) {
            roleSet.add(RoleType.ROLE_USER);
        } else {
            for (RoleType r : roles) {
                roleSet.add(r);
            }
        }
        u.setRoles(roleSet);
        return users.save(u);
    }

    public AgentEntity agent(String name) {
        return agentForOrg(name, null);
    }

    public AgentEntity agentForOrg(String name, String orgId) {
        AgentEntity a = new AgentEntity();
        a.setId(UUID.randomUUID().toString());
        a.setName(name);
        a.setOrgId(orgId);
        a.setActive(true);
        return agents.save(a);
    }

    public KnowledgeBase knowledgeBase(String name) {
        return knowledgeBases.save(new KnowledgeBase(name, "Test KB " + name));
    }

    public Team team(String name) {
        return teams.save(new Team(
                UUID.randomUUID().toString(),
                name,
                "Test team",
                "COORDINATOR",
                null,
                null,
                "Test instructions",
                null,
                false,
                true,
                List.of()));
    }

    public Workflow workflow(String name) {
        return workflows.save(new Workflow(
                UUID.randomUUID().toString(),
                name,
                "Test workflow"));
    }

    /**
     * Persists an {@link AgentRun} with the given terminal/in-flight status. Use this when a test
     * needs run rows of a specific status (RUNNING, QUEUED, FAILED, …) — the real run lifecycle
     * via {@code POST /api/agents/{id}/runs} ends in COMPLETED almost immediately under the
     * fake ChatModel and cannot be parked in an intermediate state from outside the runtime.
     */
    public AgentRun run(String agentId, String orgId, RunStatus status) {
        // sessionId stays null on purpose — agent_runs.session_id has a FK to agent_sessions
        // and the synthetic runs we seed for counter tests are not tied to a real session.
        AgentRun r = new AgentRun(agentId, null, "test-input", "test-user", orgId);
        r.setStatus(status);
        // Cast to disambiguate: RunRepository inherits save(AgentRun) from both JpaRepository
        // and RunOperations (the core/registry SPI). Either resolves identically at runtime.
        return ((org.springframework.data.jpa.repository.JpaRepository<AgentRun, String>) runs).save(r);
    }

    public Schedule schedule(String name, String cron, String targetType, String targetId) {
        return schedules.save(new Schedule(
                UUID.randomUUID().toString(),
                name,
                "Test schedule",
                cron,
                targetType,
                targetId,
                null,
                null,
                true));
    }
}
