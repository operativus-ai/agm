package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.ModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain Responsibility: One-time idempotent migration that re-saves all ModelEntity rows
 * through the JPA layer so the OutboundApiKeyConverter encrypts any existing plaintext api_key values.
 *
 * Runs on ApplicationReadyEvent (after all beans are initialized and the DataSource is live).
 * Safe to run repeatedly — a row whose api_key is already encrypted will decrypt then re-encrypt
 * with a fresh IV, which is harmless and produces a valid ciphertext.
 *
 * In passthrough mode (no encryption key configured) the converter is a no-op, so this
 * migration also becomes a no-op.
 *
 * State: Stateless (single-use event listener)
 */
@Component
public class ModelApiKeyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ModelApiKeyMigrationService.class);

    private final ModelRepository modelRepository;

    public ModelApiKeyMigrationService(ModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void encryptExistingApiKeys() {
        var models = modelRepository.findAll();
        if (models.isEmpty()) {
            return;
        }
        int count = 0;
        for (var model : models) {
            if (model.getApiKey() != null && !model.getApiKey().isBlank()) {
                // Re-saving triggers convertToDatabaseColumn — encrypts plaintext or re-encrypts ciphertext with fresh IV.
                modelRepository.save(model);
                count++;
            }
        }
        if (count > 0) {
            log.info("ModelApiKeyMigrationService: encrypted api_key for {} model(s).", count);
        }
    }
}
