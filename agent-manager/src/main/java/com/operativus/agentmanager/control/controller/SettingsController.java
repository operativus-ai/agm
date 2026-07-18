package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Domain Responsibility: Exposes REST APIs for reading and writing global Agent runtime settings.
 * State: Stateless
 * Dependencies: SettingsService
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * @summary Provides all global parameters and system switches configured in the platform.
     * @logic
     * - Polls the setting maps via SettingsService for key-value combinations.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> getAllSettings() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    /**
     * @summary Bulk updates a map of specified platform configuration parameters.
     * @logic
     * - Dispatches map to SettingsService for update execution processing.
     *
     * <p>ARCH NOTE: {@code Map<String, String>} is INTENTIONAL here and is allowlisted in
     * {@code ControllerContractArchTest.REQUEST_BODY_ALLOWLIST} as a documented exception.
     * {@link SettingsService#updateSettings(Map)} iterates the entries and persists each
     * key-value pair via {@code GlobalSettingRepository.findById(key).orElseGet(...)} — the
     * key space is genuinely dynamic and includes both compile-time-known constants
     * ({@code KEY_CRAWLER_MAX_PAGES}, {@code KEY_DEFAULT_MODEL_*}, {@code KEY_RETENTION_*_DAYS},
     * etc.) AND user-defined keys. Promoting this to a typed record would either force fixed-key
     * constraints (BREAKING the dynamic-key behavior) or wrap the map in a single-field record
     * (wrapper-record proliferation per the campaign §v3-A anti-pattern). Do not promote.
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, String> updates) {
        log.info("Updating global settings: {}", updates.keySet());
        settingsService.updateSettings(updates);
        return ResponseEntity.ok().build();
    }
}
