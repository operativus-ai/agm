package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.service.UserAdminService;
import ai.operativus.agentmanager.core.entity.User;
import ai.operativus.agentmanager.core.model.UserAdminDTO;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Domain Responsibility: REST API boundary for admin user management — CRUD, role assignment, enable/disable.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);

    private static final Duration BULK_IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String BULK_IDEMPOTENCY_KEY_PREFIX = "idempotency-response:user-bulk:";

    private final UserAdminService userAdminService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public UserAdminController(UserAdminService userAdminService,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.userAdminService = userAdminService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<Page<UserAdminDTO>> listUsers(
            @PageableDefault(sort = "username") Pageable pageable) {
        Page<UserAdminDTO> page = userAdminService.listUsers(pageable).map(this::toDTO);
        return ResponseEntity.ok(page);
    }

    @PostMapping
    public ResponseEntity<UserAdminDTO> createUser(@Valid @RequestBody UserAdminDTO.CreateRequest req) {
        String callerOrgId = CallerContext.resolveCallerOrgId();
        User created = userAdminService.createUser(req.username(), req.email(), req.password(), req.roles(), callerOrgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserAdminDTO> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UserAdminDTO.UpdateRequest req) {
        String callerOrgId = CallerContext.resolveCallerOrgId();
        User updated = userAdminService.updateUser(id, req.email(), req.roles(), req.disabled(), callerOrgId);
        return ResponseEntity.ok(toDTO(updated));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody UserAdminDTO.ResetPasswordRequest req) {
        String callerOrgId = CallerContext.resolveCallerOrgId();
        userAdminService.resetPassword(id, req.password(), callerOrgId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        String callerOrgId = CallerContext.resolveCallerOrgId();
        String callerUsername = CallerContext.resolveCallerUsername();
        userAdminService.deleteUser(id, callerOrgId, callerUsername);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk-creates users with two layers of idempotency:
     *
     * <ol>
     *   <li><b>Per-item (always on):</b> each row that names an existing username is returned
     *       as {@code already_exists} rather than rejected — resubmitting the same body
     *       converges.</li>
     *   <li><b>Response replay via Idempotency-Key (optional):</b> if the header is present
     *       and the same key has been seen in the last 24 h, the original response body is
     *       returned verbatim instead of re-invoking the service. If no header is supplied we
     *       fall back to a SHA-256 content-hash of the request body so an identical payload
     *       still replays.</li>
     * </ol>
     *
     * <p>Matrix §23 case 6.</p>
     */
    @PostMapping("/bulk")
    public ResponseEntity<UserAdminDTO.BulkCreateResponse> bulkCreate(
            @Valid @RequestBody UserAdminDTO.BulkCreateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String effectiveKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey : contentHash(req);
        String redisKey = BULK_IDEMPOTENCY_KEY_PREFIX + effectiveKey;

        // The replay cache is an optimization, not a correctness requirement: if Redis is
        // unavailable, degrade to a normal (idempotency-uncached) execution rather than 500.
        // Mirrors the RedisModelRateLimiter local-fallback contract.
        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(redisKey);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Redis unavailable for bulk-create idempotency lookup ({}); proceeding without replay cache",
                    e.getMessage());
        }
        if (cached != null) {
            try {
                UserAdminDTO.BulkCreateResponse replayed = objectMapper.readValue(
                        cached, UserAdminDTO.BulkCreateResponse.class);
                log.info("Bulk-create replay for idempotency key {} ({} items)",
                        effectiveKey, replayed.items().size());
                return ResponseEntity.ok(replayed);
            } catch (JsonProcessingException e) {
                log.warn("Stored bulk-create response for key {} was unreadable; re-executing", effectiveKey);
            }
        }

        String callerOrgId = CallerContext.resolveCallerOrgId();
        UserAdminDTO.BulkCreateResponse response = userAdminService.bulkCreate(req.users(), callerOrgId);
        try {
            redisTemplate.opsForValue().set(redisKey,
                    objectMapper.writeValueAsString(response), BULK_IDEMPOTENCY_TTL);
        } catch (JsonProcessingException | org.springframework.dao.DataAccessException e) {
            log.warn("Failed to cache bulk-create response for idempotency key {}: {}",
                    effectiveKey, e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    private String contentHash(UserAdminDTO.BulkCreateRequest req) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(req);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to compute content hash for bulk-create request", e);
        }
    }

    private UserAdminDTO toDTO(User u) {
        return new UserAdminDTO(u.getId(), u.getUsername(), u.getEmail(),
                u.getRoles(), u.isDisabled(), u.getLastLoginAt());
    }
}
