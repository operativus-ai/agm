package ai.operativus.agentmanager.integration.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Domain Responsibility: Wires a single {@link FakeChatModel} into the test
 *   ApplicationContext so production code that depends on the Spring AI {@link ChatModel}
 *   boundary executes end-to-end without ever leaving the JVM. Imported per-test via
 *   {@code @Import(FakeChatModelConfig.class)} — never auto-applied.
 * State: Stateless (the {@link FakeChatModel} bean carries the script).
 *
 * Override surface: the conventional per-provider {@link ChatModel} bean names
 *   ({@code openAiChatModel}, {@code anthropicChatModel}, {@code googleGenAiChatModel})
 *   are re-bound to the fake. Production code paths see a fake under each provider name,
 *   so {@code AgentClientFactory.chatModels} — a {@code Map<String, ChatModel>} — yields
 *   the fake regardless of which provider an agent definition selects, and the production
 *   {@code ChatConfig.chatClientBuilder(List<ChatModel>)} factory method receives only
 *   fake instances. We deliberately do NOT redeclare the {@code chatClientBuilder} bean
 *   here: registering a second {@code @Primary} of the same type causes
 *   "more than one 'primary' bean found" errors, and overriding it by name is fragile
 *   under SB4's {@code @TestConfiguration} processing order — letting the production
 *   factory build over our fake-backed model list is the deterministic path.
 *
 *   Requires {@code spring.main.allow-bean-definition-overriding=true} (set in
 *   {@code application-test.properties}).
 */
@TestConfiguration
public class FakeChatModelConfig {

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    // No @Primary on the per-provider overrides — Spring uses the runtime type
    // (FakeChatModel) for autowire lookup, so marking these as @Primary made every
    // FakeChatModel injection point ambiguous (more than one 'primary' bean).
    // The bean NAME is the only thing that matters here: each method shadows the
    // matching production bean by name (via allow-bean-definition-overriding) so
    // production code that injects ChatModel by qualifier or via Map<String, ChatModel>
    // still finds something under each provider key.

    @Bean(name = "openAiChatModel")
    public ChatModel openAiChatModelOverride(FakeChatModel fake) {
        return fake;
    }

    @Bean(name = "anthropicChatModel")
    public ChatModel anthropicChatModelOverride(FakeChatModel fake) {
        return fake;
    }

    @Bean(name = "googleGenAiChatModel")
    public ChatModel googleGenAiChatModelOverride(FakeChatModel fake) {
        return fake;
    }
}
