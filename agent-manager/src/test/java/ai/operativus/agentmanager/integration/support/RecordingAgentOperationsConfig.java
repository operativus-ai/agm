package ai.operativus.agentmanager.integration.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Domain Responsibility: {@code @TestConfiguration} that publishes
 *   {@link RecordingAgentOperations} as the {@code @Primary AgentOperations} bean,
 *   overriding {@code AgentService} for integration tests that need to script agent
 *   responses without invoking the real ChatClient advisor chain.
 *
 *   <p>{@code application-test.properties} sets
 *   {@code spring.main.allow-bean-definition-overriding=true} which is what permits
 *   the {@code @Primary} override.
 *
 * State: Stateless config.
 */
@TestConfiguration
public class RecordingAgentOperationsConfig {

    @Bean
    @Primary
    public RecordingAgentOperations recordingAgentOperations() {
        return new RecordingAgentOperations();
    }
}
