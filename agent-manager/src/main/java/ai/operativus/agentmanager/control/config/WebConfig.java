package ai.operativus.agentmanager.control.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ai.operativus.agentmanager.control.config.logging.WebLoggingInterceptor;
import ai.operativus.agentmanager.control.config.interceptor.IdempotencyInterceptor;
import ai.operativus.agentmanager.control.config.interceptor.SystemAuditInterceptor;

import java.util.Arrays;

/**
 * Domain Responsibility: Configures Spring MVC infrastructural components, including global CORS rules and generic request interceptors.
 * State: Stateless (Configuration)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Sentinel value previously shipped in {@code application-prod.properties} as a
     * placeholder operators were expected to override. Kept here as a literal so any
     * environment that boots with this string (because the operator forgot to override
     * it) fails fast at startup instead of silently allowing an unregistered/attacker-
     * controlled domain in the CORS allowlist. Matches the fail-fast shape used by
     * {@code JwtUtils.validateSecretOrFail} for the legacy hardcoded JWT secret.
     */
    static final String PLACEHOLDER_PRODUCTION_ORIGIN = "https://your-production-domain.com";

    @Value("${app.cors.allowed-origin-patterns:http://localhost:*}")
    private String[] allowedOriginPatterns;

    private final WebLoggingInterceptor webLoggingInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;
    private final SystemAuditInterceptor systemAuditInterceptor;

    public WebConfig(WebLoggingInterceptor webLoggingInterceptor,
                     IdempotencyInterceptor idempotencyInterceptor,
                     SystemAuditInterceptor systemAuditInterceptor) {
        this.webLoggingInterceptor = webLoggingInterceptor;
        this.idempotencyInterceptor = idempotencyInterceptor;
        this.systemAuditInterceptor = systemAuditInterceptor;
    }

    /**
     * Fail-fast guard on the resolved {@code app.cors.allowed-origin-patterns}. Rejects
     * any entry matching {@link #PLACEHOLDER_PRODUCTION_ORIGIN} — that string was shipped
     * in {@code application-prod.properties} as a placeholder, and an operator that
     * activates the {@code prod} profile without overriding it would otherwise expose a
     * CORS allowlist for an attacker-registrable domain. Also rejects entries that are
     * blank to surface comma-typo misconfigurations early. The dev default
     * {@code http://localhost:*} is never rejected.
     */
    @PostConstruct
    void validateCorsOriginsOrFail() {
        validatePatterns(allowedOriginPatterns);
    }

    /**
     * Package-private static validator so this can be exercised directly from unit tests
     * without booting a Spring context. Throws {@link IllegalStateException} on any
     * disallowed configuration — see {@link #validateCorsOriginsOrFail()} for the contract.
     */
    static void validatePatterns(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            throw new IllegalStateException(
                    "app.cors.allowed-origin-patterns is required but resolved to an empty list. "
                            + "Set it to your actual origin pattern(s) (e.g. https://app.example.com) "
                            + "via the active profile or environment.");
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalStateException(
                        "app.cors.allowed-origin-patterns contains a blank entry: "
                                + Arrays.toString(patterns)
                                + ". Likely a stray comma — fix the property in your active profile.");
            }
            if (PLACEHOLDER_PRODUCTION_ORIGIN.equals(pattern.trim())) {
                throw new IllegalStateException(
                        "app.cors.allowed-origin-patterns contains the placeholder '"
                                + PLACEHOLDER_PRODUCTION_ORIGIN + "' which was shipped in "
                                + "application-prod.properties as a sentinel for operators to "
                                + "override. An attacker can register this domain. Set "
                                + "app.cors.allowed-origin-patterns to your actual production "
                                + "origin pattern(s) before activating the prod profile.");
            }
        }
    }

    /**
     * @summary Provides a shared {@link RestTemplate} for outbound HTTP calls (e.g., A2A card resolution).
     * @logic Configured with explicit connect and read timeouts so a hanging remote peer cannot
     *        block a virtual thread indefinitely. Card fetches are fast metadata calls — 5 s
     *        connect, 15 s read is generous. Adjust via {@code agm.a2a.card-fetch-timeout-ms}.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    /**
     * @summary Configures global Cross-Origin Resource Sharing (CORS) mappings.
     * @logic
     * - Allows requests from any localhost port.
     * - Authorizes standard REST methods (GET, POST, PUT, DELETE, OPTIONS).
     * - Permits credentials.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * @summary Registers custom HTTP interceptors into the Spring MVC lifecycle.
     * @logic
     * - Registers the `WebLoggingInterceptor` for all `/api/**` paths to capture REST traffic.
     * - Registers the `IdempotencyInterceptor` for specific critical mutation endpoints to prevent duplicate processing.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(webLoggingInterceptor)
                .addPathPatterns("/api/**"); // Only log REST controller traffic, not statics or actuator
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/admin/agents/**", "/api/runs/**", "/api/knowledge/**"); // Protect critical mutation endpoints
        registry.addInterceptor(systemAuditInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**"); // Auth events are recorded from AuthController directly.
    }
}
