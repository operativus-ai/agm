package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.model.AuthModels.*;
import ai.operativus.agentmanager.control.repository.UserRepository;
import ai.operativus.agentmanager.control.security.JwtUtils;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.control.service.SystemAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import ai.operativus.agentmanager.core.model.enums.RoleType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Handles user authentication, token generation, and registration. Writes
 *   {@code system_audits} rows directly for auth events (LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT,
 *   REGISTER) — the {@link ai.operativus.agentmanager.control.config.interceptor.SystemAuditInterceptor}
 *   excludes {@code /api/auth/**} because the login-failure branch throws before a handler's
 *   afterCompletion runs with a meaningful status.
 * State: Stateless
 * Dependencies: AuthenticationManager, UserRepository, PasswordEncoder, JwtUtils, SystemAuditService
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String RESOURCE_TYPE_AUTH = "AUTH";

    /**
     * Placeholder recorded as {@code system_audits.username} for LOGIN_FAILURE events to
     * defeat enumeration. Without sanitization, an admin with audit-log read permission
     * could enumerate which usernames exist (real user + wrong password → row with that
     * username; non-existent user → row with the attempted username). Recording the same
     * literal for both cases removes that signal.
     *
     * <p>Trade-off: forensic value of "who tried to log in as alice" is lost from the
     * audit-log read surface. If granular forensic value is needed, route through a
     * separate restricted-access table.
     */
    private static final String LOGIN_FAILURE_PLACEHOLDER = "<authentication-failed>";

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    private final JwtUtils jwtUtils;
    private final SystemAuditService systemAuditService;
    private final ai.operativus.agentmanager.control.service.PasswordResetService passwordResetService;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository,
                          PasswordEncoder encoder, JwtUtils jwtUtils,
                          SystemAuditService systemAuditService,
                          ai.operativus.agentmanager.control.service.PasswordResetService passwordResetService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.encoder = encoder;
        this.jwtUtils = jwtUtils;
        this.passwordResetService = passwordResetService;
        this.systemAuditService = systemAuditService;
    }

    /**
     * @summary Authenticates a user credential set and issues a signed JWT token. Records
     *          LOGIN_SUCCESS or LOGIN_FAILURE in {@code system_audits} regardless of outcome.
     */
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        log.debug("Authentication attempt for username: {}", loginRequest.username());
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.username(), loginRequest.password()));
        } catch (AuthenticationException e) {
            // Sanitize the attempted username out of the audit row to defeat enumeration
            // via /api/admin/audit-logs?action=LOGIN_FAILURE&username=... — see
            // LOGIN_FAILURE_PLACEHOLDER javadoc for the trade-off.
            systemAuditService.record(
                    null,
                    LOGIN_FAILURE_PLACEHOLDER,
                    "LOGIN_FAILURE",
                    RESOURCE_TYPE_AUTH,
                    LOGIN_FAILURE_PLACEHOLDER,
                    "POST",
                    "/api/auth/login",
                    401);
            throw e;
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());

        log.info("User {} successfully authenticated", userDetails.getUsername());
        userRepository.findById(userDetails.getId()).ifPresent(u -> {
            u.setLastLoginAt(java.time.LocalDateTime.now());
            userRepository.save(u);
        });
        systemAuditService.record(
                userDetails.getOrgId(),
                userDetails.getUsername(),
                "LOGIN_SUCCESS",
                RESOURCE_TYPE_AUTH,
                userDetails.getUsername(),
                "POST",
                "/api/auth/login",
                200);
        return ResponseEntity.ok(new JwtResponse(jwt,
                userDetails.getId().toString(),
                userDetails.getUsername(),
                userDetails.getEmail(),
                roles));
    }

    /**
     * @summary Registers a new user account into the system.
     * @logic
     * - Verifies that the proposed username and email do not already exist (returns 400 if so).
     * - Encapsulates the details into a new User entity and hashes the password via PasswordEncoder.
     * - Assigns requested roles or defaults to 'ROLE_USER'.
     * - Persists the new User entity via UserRepository.
     */
    /**
     * Returned for ANY registration-collision (existing username OR existing email). The
     * single shared response prevents account enumeration — an attacker can no longer probe
     * /register with candidate usernames + emails and distinguish "exists" from "doesn't
     * exist" by reading the response body. Operator-side observability is preserved via
     * the distinct WARN log lines below.
     */
    private static final String REGISTRATION_COLLISION_RESPONSE =
            "Error: Registration could not be completed.";

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody RegisterRequest signUpRequest) {
        log.info("Registration attempt for username: {}", signUpRequest.username());
        if (userRepository.existsByUsername(signUpRequest.username())) {
            log.warn("Registration failed: Username {} is already taken", signUpRequest.username());
            return ResponseEntity.badRequest().body(new MessageResponse(REGISTRATION_COLLISION_RESPONSE));
        }

        if (userRepository.existsByEmail(signUpRequest.email())) {
            log.warn("Registration failed: Email {} is already in use", signUpRequest.email());
            return ResponseEntity.badRequest().body(new MessageResponse(REGISTRATION_COLLISION_RESPONSE));
        }

        // Create new user's account
        User user = new User(signUpRequest.username(),
                signUpRequest.email(),
                encoder.encode(signUpRequest.password()));

        Set<RoleType> strRoles = new HashSet<>();
        if (signUpRequest.role() != null && !signUpRequest.role().isEmpty()) {
            strRoles.addAll(signUpRequest.role().stream().map(RoleType::fromValue).collect(Collectors.toSet()));
        } else {
            // Self-register default: minimal-privilege ROLE_USER. Previously also stamped
            // ROLE_ADMIN, which meant any anonymous registrant on a public /register endpoint
            // became an admin. Pinned by UserAdminCrudRuntimeTest.selfRegisterWithEmptyRoles_*.
            strRoles.add(RoleType.ROLE_USER);
        }

        user.setRoles(strRoles);
        userRepository.save(user);

        systemAuditService.record(
                user.getOrgId(),
                signUpRequest.username(),
                "REGISTER",
                RESOURCE_TYPE_AUTH,
                signUpRequest.username(),
                "POST",
                "/api/auth/register",
                200);

        return ResponseEntity.ok(new MessageResponse("User registered successfully!"));
    }

    /**
     * @summary Logs the caller out by writing a LOGOUT row to {@code system_audits} and
     *     clearing the {@link SecurityContextHolder}. Since the system is JWT-stateless
     *     (no server-side session to invalidate), the client is responsible for discarding
     *     the JWT — this endpoint exists primarily to produce the audit-trail event and to
     *     give the FE a canonical "I'm done" call.
     * @logic
     *   - Resolves username + orgId from the bound principal (Spring's auth filter has
     *     already vetted the caller via JWT).
     *   - Writes a LOGOUT row carrying that username + orgId.
     *   - Clears SecurityContextHolder (effectively no-op for stateless requests but
     *     correct hygiene if the same thread is reused for a follow-up call).
     *   - Returns 204 No Content.
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = null;
        String orgId = null;
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl userDetails) {
            username = userDetails.getUsername();
            orgId = userDetails.getOrgId();
        } else if (auth != null) {
            username = auth.getName();
        }
        log.info("Logout for username: {}", username);
        systemAuditService.record(
                orgId,
                username,
                "LOGOUT",
                RESOURCE_TYPE_AUTH,
                username,
                "POST",
                "/api/auth/logout",
                204);
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Self-serve password reset — request phase.
     * @logic
     *   - Always returns 200 regardless of whether the email is known. The service
     *     looks the user up by email and silently no-ops on unknown addresses.
     *     This defeats user-enumeration via this endpoint.
     *   - On a known address, generates a single-use token, stores its SHA-256,
     *     dispatches an email with the reset URL (link valid for the configured
     *     TTL, default 15 min).
     *   - Per-user rate limit (default 5/hour) is enforced inside the service; a
     *     rate-limited request still returns 200.
     *   - Per-IP rate limit (existing 5/min on /api/auth/**) gates the endpoint
     *     itself via {@code RateLimitingFilter}; nothing new wired here.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(
            @jakarta.validation.Valid @RequestBody
            ai.operativus.agentmanager.control.dto.PasswordResetDtos.PasswordResetRequest body,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String requesterIp = clientIp(httpRequest);
        String requesterUa = truncateUa(httpRequest.getHeader("User-Agent"));
        passwordResetService.requestReset(body.email(), requesterIp, requesterUa);
        return ResponseEntity.ok().build();
    }

    /**
     * @summary Self-serve password reset — confirm phase.
     * @logic
     *   - Validates the presented token (hash-matched, not-expired, not-consumed).
     *   - Encodes the new password with the configured BCrypt encoder and writes
     *     it to the user row.
     *   - Marks the token consumed so a replay within the TTL window is rejected.
     *   - 200 on success, 400 on any validation failure (invalid/expired/used
     *     token OR weak password). The same 400 shape for all failure modes — no
     *     enum of which-token-was-valid is leaked.
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @jakarta.validation.Valid @RequestBody
            ai.operativus.agentmanager.control.dto.PasswordResetDtos.PasswordResetConfirm body) {
        passwordResetService.confirmReset(body.token(), body.newPassword());
        return ResponseEntity.ok().build();
    }

    /** Best-effort client-IP extraction; falls back to remoteAddr. */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            // First entry in a comma-separated chain is the original client.
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }

    private static String truncateUa(String ua) {
        if (ua == null) return null;
        return ua.length() <= 512 ? ua : ua.substring(0, 512);
    }
}
