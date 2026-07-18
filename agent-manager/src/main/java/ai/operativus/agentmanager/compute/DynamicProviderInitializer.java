package ai.operativus.agentmanager.compute;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Domain Responsibility: Injects literal dummy API keys for every LLM provider
 *     auto-configuration so Spring AI's eager validation passes at boot. Real keys
 *     are resolved per-request from the database — see
 *     {@code AbstractDynamicModelProvider.resolveApiKey}. Runs as an
 *     {@link ApplicationContextInitializer} so the dummy property source lands before
 *     any provider bean is instantiated.
 * State: Stateless (Configuration).
 */
public class DynamicProviderInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String DUMMY_KEY = "dummy-key-to-pass-validation";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();

        // Inject literal dummy values ONLY for the api-key properties — Spring AI's eager
        // validation chokes on null/empty keys at boot, and real keys are resolved per-request
        // from the DB via AbstractDynamicModelProvider.resolveApiKey. We deliberately do NOT
        // inject spring.ai.google.genai.project-id here — that field IS legitimately env-driven
        // (Vertex AI embedding needs the real GCP project) and application.properties already
        // declares a ${GOOGLE_PROJECT_ID:unused} fallback. Adding it here with addFirst() would
        // shadow the .env value and route every Vertex embedding call to project "unused".
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.ai.openai.api-key", DUMMY_KEY);
        properties.put("spring.ai.anthropic.api-key", DUMMY_KEY);
        properties.put("spring.ai.google.genai.api-key", DUMMY_KEY);

        environment.getPropertySources().addFirst(new MapPropertySource("dynamicProviderProperties", properties));

        // SLF4J is not yet wired at the ApplicationContextInitializer phase, so the banner
        // goes to System.out directly. Real key resolution happens per-request from the
        // database via AbstractDynamicModelProvider.resolveApiKey.
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  LLM keys: DB-only resolution (provider_credentials) ║");
        System.out.println("║  Configure via POST /api/v1/provider-credentials     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }
}
