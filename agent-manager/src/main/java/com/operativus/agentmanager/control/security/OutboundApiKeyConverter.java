package com.operativus.agentmanager.control.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain Responsibility: Transparent AES-256-GCM encryption/decryption for the
 * {@code outbound_api_key} column in {@code a2a_remote_agents} and {@code api_key} in
 * {@code models}. Supports multi-version keys for rotation (§22.6).
 *
 * L-2 Fix: The original schema stored outbound peer credentials in plaintext, leaving
 * them fully exposed in a database breach. This converter encrypts each value before
 * persistence and decrypts on read — transparent to all JPA callers.
 *
 * Algorithm: AES/GCM/NoPadding (256-bit key, 96-bit IV, 128-bit auth tag).
 *
 * Wire format (versioned, §22.6):
 *   v{N}:Base64(IV[12] || Ciphertext || AuthTag[16])
 * where {N} is the integer version of the key used to encrypt. Legacy rows written
 * before versioning have no {@code v{N}:} prefix — decoded with key version 1.
 *
 * Key configuration — three forms, evaluated in this order:
 *   1. {@code agm.security.outbound-key-encryption.keys=1:<b64>,2:<b64>,...}
 *        Multi-version. Encrypt uses the {@code active-version}.
 *   2. {@code agm.security.outbound-key-encryption-key=<b64>} (legacy)
 *        Single key. Treated as version 1. Kept so existing deployments keep working
 *        without config changes.
 *   3. Neither set → boot FAILS unless
 *      {@code agm.security.outbound-key-encryption.allow-passthrough=true} is set
 *      explicitly. Opt-in PASSTHROUGH stores plaintext and logs a WARNING; intended
 *      only for local dev and tests, NEVER for non-dev profiles.
 *
 * Active version: {@code agm.security.outbound-key-encryption.active-version} (default 1).
 * Must refer to a version present in the keys map. Encrypting always uses the active
 * version; decrypting picks the version from the wire-format prefix.
 *
 * Architecture:
 * - Spring {@code @Component} so the {@code @Value} injection works.
 * - {@code autoApply = false}: applied explicitly on the fields that need it.
 *
 * State: Stateless (key material is immutable after construction).
 */
@Component
@Converter(autoApply = false)
public class OutboundApiKeyConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(OutboundApiKeyConverter.class);

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH  = 12;  // 96-bit IV — GCM standard
    private static final int    TAG_LENGTH = 128; // 128-bit auth tag — GCM standard

    /** Matches {@code v{digits}:} at the start of a ciphertext string. */
    private static final Pattern VERSION_PREFIX = Pattern.compile("^v(\\d+):");

    /** Legacy rows with no prefix are decoded with this version. */
    private static final int LEGACY_VERSION = 1;

    private final Map<Integer, SecretKey> keys;
    private final int                     activeVersion;
    private final boolean                 passthroughMode;

    public OutboundApiKeyConverter(
            @Value("${agm.security.outbound-key-encryption.keys:}") String versionedKeysCsv,
            @Value("${agm.security.outbound-key-encryption.active-version:1}") int activeVersion,
            @Value("${agm.security.outbound-key-encryption-key:}") String legacyKey,
            @Value("${agm.security.outbound-key-encryption.allow-passthrough:false}") boolean allowPassthrough) {

        Map<Integer, SecretKey> parsed = new LinkedHashMap<>();
        if (versionedKeysCsv != null && !versionedKeysCsv.isBlank()) {
            for (String entry : versionedKeysCsv.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) continue;
                int colon = trimmed.indexOf(':');
                if (colon < 1) {
                    throw new IllegalStateException(
                        "agm.security.outbound-key-encryption.keys entry must be <version>:<base64>, got: " + trimmed);
                }
                int version;
                try {
                    version = Integer.parseInt(trimmed.substring(0, colon).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                        "agm.security.outbound-key-encryption.keys version must be an integer, got: " + trimmed, e);
                }
                parsed.put(version, toSecretKey(trimmed.substring(colon + 1).trim()));
            }
        } else if (legacyKey != null && !legacyKey.isBlank()) {
            parsed.put(LEGACY_VERSION, toSecretKey(legacyKey.trim()));
        }

        if (parsed.isEmpty()) {
            if (!allowPassthrough) {
                throw new IllegalStateException(
                    "OutboundApiKeyConverter: no encryption key configured. " +
                    "Set agm.security.outbound-key-encryption.keys=<version>:<base64-32-bytes>[,...] " +
                    "(or the legacy agm.security.outbound-key-encryption-key=<base64-32-bytes>). " +
                    "Generate a key with: openssl rand -base64 32. " +
                    "To run in plaintext PASSTHROUGH mode (dev/test only — NEVER production), " +
                    "set agm.security.outbound-key-encryption.allow-passthrough=true explicitly.");
            }
            log.warn("OutboundApiKeyConverter: no encryption key configured and " +
                     "agm.security.outbound-key-encryption.allow-passthrough=true. " +
                     "Running in PASSTHROUGH mode — outbound API keys will be stored as plaintext. " +
                     "This MUST NOT be used in production.");
            this.keys            = Collections.emptyMap();
            this.activeVersion   = LEGACY_VERSION;
            this.passthroughMode = true;
            return;
        }

        if (!parsed.containsKey(activeVersion)) {
            throw new IllegalStateException(
                "agm.security.outbound-key-encryption.active-version=" + activeVersion +
                " has no corresponding key in the configured keys map. Present versions: " + parsed.keySet());
        }

        this.keys            = Collections.unmodifiableMap(parsed);
        this.activeVersion   = activeVersion;
        this.passthroughMode = false;
        log.info("OutboundApiKeyConverter: AES-256-GCM active. versions={}, active={}.",
                 parsed.keySet(), activeVersion);
    }

    /** @return active key version used when encrypting new ciphertexts. */
    public int activeVersion() {
        return activeVersion;
    }

    /** @return true if no keys are configured and the converter stores plaintext. */
    public boolean isPassthroughMode() {
        return passthroughMode;
    }

    /**
     * @summary Encrypts the plaintext API key before it is written to the database.
     * @logic Runs AES-256-GCM with a fresh random IV against the active key version,
     *        then prepends the {@code v{active}:} wire-format prefix before Base64.
     */
    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        if (passthroughMode) return plaintext;

        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            byte[] ivAndCipher = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv,         0, ivAndCipher, 0,           iv.length);
            System.arraycopy(ciphertext, 0, ivAndCipher, iv.length,   ciphertext.length);

            return "v" + activeVersion + ":" + Base64.getEncoder().encodeToString(ivAndCipher);
        } catch (Exception e) {
            throw new IllegalStateException("OutboundApiKeyConverter: encryption failed", e);
        }
    }

    /**
     * @summary Decrypts a stored ciphertext back to the plaintext API key.
     * @logic Parses the {@code v{N}:} prefix to pick the decryption key; values without
     *        a prefix are treated as version 1 (legacy format).
     */
    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null) return null;
        if (passthroughMode) return dbValue;

        int version;
        String body;
        Matcher m = VERSION_PREFIX.matcher(dbValue);
        if (m.find()) {
            version = Integer.parseInt(m.group(1));
            body    = dbValue.substring(m.end());
        } else {
            version = LEGACY_VERSION;
            body    = dbValue;
        }

        // Read-side fail-soft: a single undecryptable value (a plaintext key seeded via raw SQL, a
        // rotated-away key version, or a tampered ciphertext) must NOT propagate as a 500 that breaks
        // the whole list endpoint or crashes an agent run with an opaque "Unexpected error". Return
        // null so callers treat the key as absent and surface the friendly "No API key configured"
        // path. The WRITE side (convertToDatabaseColumn) still throws — we never silently persist a
        // bad value; only reads degrade. Every miss is WARN-logged so ops can find and re-encrypt it.
        SecretKey key = keys.get(version);
        if (key == null) {
            log.warn("OutboundApiKeyConverter: no key configured for version {} — cannot decrypt a stored "
                    + "value; treating it as absent. Present versions: {}. Re-save the affected credential "
                    + "through the app (POST /api/v1/provider-credentials or the admin UI) to re-encrypt it.",
                    version, keys.keySet());
            return null;
        }

        try {
            byte[] ivAndCipher = Base64.getDecoder().decode(body);

            byte[] iv         = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[ivAndCipher.length - IV_LENGTH];
            System.arraycopy(ivAndCipher, 0,         iv,         0, IV_LENGTH);
            System.arraycopy(ivAndCipher, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            log.warn("OutboundApiKeyConverter: could not decrypt a stored value (version {}) — treating it "
                    + "as absent. Most likely a plaintext value written outside the app (raw-SQL seed) or a "
                    + "key mismatch. Re-save the credential through the app to re-encrypt it. Cause: {}",
                    version, e.toString());
            return null;
        }
    }

    private static SecretKey toSecretKey(String base64) {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "outbound-key-encryption key must decode to exactly 32 bytes (256 bits). Got " +
                keyBytes.length + " bytes. Generate with: openssl rand -base64 32");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
