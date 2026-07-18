package ai.operativus.agentmanager.control.service.queue;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(JobHandler::jobType, h -> h));
    }

    public JobHandler get(String jobType) {
        return Optional.ofNullable(handlers.get(jobType))
                .orElseThrow(() -> new IllegalStateException("No handler registered for job type: " + jobType));
    }
}
