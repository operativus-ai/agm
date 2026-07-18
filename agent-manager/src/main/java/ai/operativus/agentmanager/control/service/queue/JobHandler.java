package ai.operativus.agentmanager.control.service.queue;

import ai.operativus.agentmanager.core.entity.BackgroundJob;

public interface JobHandler {

    String jobType();

    void execute(BackgroundJob job) throws Exception;
}
