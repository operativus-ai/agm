package com.operativus.agentmanager.control.config;

import com.operativus.agentmanager.control.security.AgentPermissionEvaluator;
import com.operativus.agentmanager.control.security.ApiKeyAuthenticationFilter;
import com.operativus.agentmanager.control.security.AuthEntryPointJwt;
import com.operativus.agentmanager.control.security.JwtAuthenticationFilter;
import com.operativus.agentmanager.control.security.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


/**
 * Domain Responsibility: Central configuration for Spring Security, defining the filter chain, CORS policies, and authentication providers for the application.
 * State: Stateless (Configuration)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // /workflows/ws is permitted at the HTTP filter layer because a browser WebSocket cannot send
    // an Authorization header; the JwtHandshakeInterceptor enforces the JWT via the ?token= query
    // param at handshake time. Without this, Spring Security 401s the upgrade before the interceptor runs.
    @Value("${app.security.public-paths:/api/auth/**,/api/v1/health/**,/error,/actuator/**,/swagger-ui/**,/v3/api-docs/**,/workflows/ws}")
    private String[] publicPaths;

    @Value("${agentmanager.security.bcrypt-strength:12}")
    private int bcryptStrength;

    private final UserDetailsServiceImpl userDetailsService;
    private final AuthEntryPointJwt unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final com.operativus.agentmanager.control.security.SseTokenAuthFilter sseTokenAuthFilter;

    public SecurityConfig(
            UserDetailsServiceImpl userDetailsService,
            AuthEntryPointJwt unauthorizedHandler,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            com.operativus.agentmanager.control.security.SseTokenAuthFilter sseTokenAuthFilter) {
        this.userDetailsService = userDetailsService;
        this.unauthorizedHandler = unauthorizedHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.sseTokenAuthFilter = sseTokenAuthFilter;
    }



    /**
     * @summary Configures the DAO authentication provider.
     * @logic
     * - Injects the custom `UserDetailsService`.
     * - Configures the `BCryptPasswordEncoder` for password hashing.
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * @summary Exposes the AuthenticationManager as a Spring Bean.
     * @logic
     * - Retrieves the AuthenticationManager from the AuthenticationConfiguration.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    /**
     * BCrypt password encoder. Strength is externalised so ops can tune the
     * work factor for the host class without a redeploy and so test profiles
     * can drop to a cheap factor (no security relevance at test time).
     *
     * <p>Default is 12 — OWASP 2024+ recommendation, ~250 ms per hash on
     * modest hardware. Spring Security default ({@code BCryptPasswordEncoder()})
     * uses 10 (~50 ms), which is below current minimums for production
     * password storage. Verification works across strengths because the cost
     * is embedded in the stored hash ({@code $2a$NN$...}).
     *
     * <p>Valid range is 4–31; {@link BCryptPasswordEncoder} throws
     * {@link IllegalArgumentException} outside that. Pre-launch — no migration
     * path needed for existing hashes (none in production yet).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    /**
     * @summary Registers the custom {@link AgentPermissionEvaluator} so
     *   {@code @PreAuthorize("hasPermission(#id, 'AgentDefinition', '…')")} on
     *   {@code AgentAdminService} resolves to the org-scoped rules rather than Spring's
     *   deny-all default. Replaces the previous blanket 403 behavior pinned by
     *   {@code AgentsCrudRuntimeTest}.
     *
     *   <p>Also wires {@link org.springframework.security.access.hierarchicalroles.RoleHierarchy}
     *   into the method-level evaluator so {@code @PreAuthorize("hasRole('ADMIN')")} accepts
     *   a {@code ROLE_SUPER_ADMIN}-only principal per {@code RoleHierarchyConfig}'s declared
     *   {@code SUPER_ADMIN > ADMIN > OPERATOR > USER > VIEWER} chain. Without this setter the
     *   hierarchy bean is wired into the HTTP-layer evaluator only — method-level SpEL falls
     *   back to literal-role matching and rejects transitive elevations. Pinned by
     *   {@code RoleHierarchyRuntimeTest}.
     */
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            AgentPermissionEvaluator evaluator,
            org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(evaluator);
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }

    /**
     * @summary Constructs the core security filter chain for the HTTP request lifecycle.
     * @logic
     * - Disables CSRF (typical for stateless JWT APIs).
     * - Configures stateless session management.
     * - Defines URL authorization rules (e.g., permitting /api/auth/** while securing others).
     * - Injects the custom `JwtAuthenticationFilter` before the standard username/password filter.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(org.springframework.security.config.Customizer.withDefaults())
            // CSRF disabled: API is fully stateless (JWT Bearer tokens, no cookies).
            // Revisit if session-based auth or cookie-based tokens are ever introduced.
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR, jakarta.servlet.DispatcherType.ASYNC).permitAll();
                auth.requestMatchers(publicPaths).permitAll();
                // A2A networking boundary: accepts both JWT (admin) and X-A2A-Api-Key (peer M2M)
                auth.requestMatchers("/api/v1/a2a/**").authenticated();
                auth.anyRequest().authenticated();
            });

        http.authenticationProvider(authenticationProvider());
        // JWT filter runs first so admin callers with a Bearer token are authenticated before
        // the API key filter checks — ApiKeyAuthenticationFilter skips when SecurityContext is
        // already populated, allowing dual-auth on /api/v1/a2a/** without conflict.
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);
        // OBS-T005: SSE token filter must run BEFORE the JWT filter so EventSource requests
        // bearing only ?token=… (no Authorization header) are authenticated by token rather
        // than rejected as anonymous by the JWT filter's expectation of a Bearer header.
        // The filter early-returns to chain.doFilter for any non-/events path, so non-SSE
        // requests are unaffected.
        http.addFilterBefore(sseTokenAuthFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
