package ai.operativus.agentmanager.control.service.queue;

import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.model.SecurityPrincipals;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.springframework.stereotype.Component;

@Component
public class AgentRunJobHandler implements JobHandler {

    public static final String JOB_TYPE = "AGENT_RUN";

    private final AgentOperations agentOperations;

    public AgentRunJobHandler(AgentOperations agentOperations) {
        this.agentOperations = agentOperations;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) {
        agentOperations.run(
                job.getAgentId(),
                job.getPayload(),
                null,
                null,
                SecurityPrincipals.SYSTEM_PRINCIPAL,
                SecurityPrincipals.SYSTEM_PRINCIPAL,
                false,
                null
        );
    }
}
