package com.operativus.agentmanager.control.service.queue;

import com.operativus.agentmanager.core.entity.BackgroundJob;

public interface JobHandler {

    String jobType();

    void execute(BackgroundJob job) throws Exception;
}
