package ai.operativus.agentmanager.compute.config;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.support.TaskUtils;


import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Configures core infrastructure components, such as JVM concurrency models.
 * State: Stateless (Configuration)
 */
@Configuration
public class AgentManagerConfig {

    /**
     * @summary Configures the default asynchronous task executor to utilize Java 21 Virtual Threads.
     * @logic Overrides the default Spring applicationTaskExecutor bean and returns a TaskExecutorAdapter wrapping a new virtual thread-per-task executor.
     */
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor() {
        TaskExecutorAdapter adapter = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        // F23 — chain decorators outside-in: ScopedValueTaskDecorator binds JDK 21 ScopedValues
        // FIRST on the worker thread, then ContextPropagatingTaskDecorator (Micrometer) propagates
        // Reactor Context + registered ThreadLocalAccessors INSIDE that scope. Without the outer
        // ScopedValue rebind, every @Async method (ReflectionService, SessionSummarization, alerts,
        // …) ran with AgentContextHolder.getOrgId()==null, silently bypassing tenant isolation in
        // the advisor chain. See ScopedValueTaskDecorator Javadoc.
        org.springframework.core.task.TaskDecorator inner =
                new org.springframework.core.task.support.ContextPropagatingTaskDecorator();
        org.springframework.core.task.TaskDecorator outer = new ScopedValueTaskDecorator();
        adapter.setTaskDecorator(runnable -> outer.decorate(inner.decorate(runnable)));
        return adapter;
    }

    /**
     * @summary Makes ApplicationEvent dispatch asynchronous (logging-plan Pre-2, R-4).
     * @logic Spring's default ApplicationEventMulticaster is synchronous — a slow @EventListener
     *        would block the thread that called publishEvent(). With SSE/WebSocket listeners
     *        downstream of agent runs, that means a slow browser can stall the agent thread.
     *        Setting the multicaster's TaskExecutor to the virtual-thread pool dispatches every
     *        listener on its own vthread and keeps publishers non-blocking. Exceptions inside
     *        listeners are logged and suppressed so one misbehaving listener cannot take down
     *        unrelated listeners or the publisher.
     */
    @Bean(name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public ApplicationEventMulticaster applicationEventMulticaster(AsyncTaskExecutor applicationTaskExecutor) {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(applicationTaskExecutor);
        multicaster.setErrorHandler(TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER);
        return multicaster;
    }
}
