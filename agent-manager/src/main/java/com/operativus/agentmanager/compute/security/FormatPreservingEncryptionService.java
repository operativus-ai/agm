package com.operativus.agentmanager.compute.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * Domain Responsibility: Provides Format-Preserving Encryption (FPE) by replacing detected PII
 * with structurally valid fake values that maintain the same length and character class composition.
 * This allows downstream tools requiring valid formatting (e.g., a 16-digit credit card) to continue
 * operating without exposure to real data.
 *
 * <p>Implementation Note: This service uses deterministic seeding derived from a hash of the original
 * value to ensure that the same input always produces the same fake output within a single application
 * lifecycle, enabling reversible lookups if a master key is later introduced.</p>
 *
 * State: Stateless
 */
@Service
public class FormatPreservingEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(FormatPreservingEncryptionService.class);

    /**
     * @summary Replaces a detected PII value with a structurally identical fake value.
     * @logic Inspects the character composition of the input and generates a replacement where:
     *        digits are replaced with random digits, letters with random letters (preserving case),
     *        and all other characters (hyphens, dots, '@') are preserved in-place.
     *        The replacement is seeded from the input hash for deterministic repeatability.
     *
     * @param original the raw PII string detected by the NER engine
     * @return a structurally valid fake string of the same length and format
     */
    public String encrypt(String original) {
        if (original == null || original.isEmpty()) {
            return original;
        }

        Random seededRandom = new Random(original.hashCode());
        StringBuilder result = new StringBuilder(original.length());

        for (char c : original.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append(seededRandom.nextInt(10));
            } else if (Character.isUpperCase(c)) {
                result.append((char) ('A' + seededRandom.nextInt(26)));
            } else if (Character.isLowerCase(c)) {
                result.append((char) ('a' + seededRandom.nextInt(26)));
            } else {
                // Preserve structural characters: @, -, ., spaces, etc.
                result.append(c);
            }
        }

        String encrypted = result.toString();

        // Guard: if the output accidentally equals the original, shift each digit by 1
        if (encrypted.equals(original)) {
            StringBuilder shifted = new StringBuilder(encrypted.length());
            for (char c : encrypted.toCharArray()) {
                if (Character.isDigit(c)) {
                    shifted.append((c - '0' + 1) % 10);
                } else if (Character.isLetter(c)) {
                    shifted.append(Character.isUpperCase(c)
                            ? (char) ('A' + (c - 'A' + 1) % 26)
                            : (char) ('a' + (c - 'a' + 1) % 26));
                } else {
                    shifted.append(c);
                }
            }
            encrypted = shifted.toString();
        }

        log.debug("FPE: replaced PII value of length {} (format preserved)", original.length());
        return encrypted;
    }

    /**
     * @summary Generates a simple redaction label for a given PII policy name.
     * @logic Constructs a standard bracketed label like {@code [REDACTED_EMAIL_ADDRESS]}.
     *
     * @param policyName the name of the PII policy that matched
     * @return a bracketed redaction label
     */
    public String redact(String policyName) {
        return "[REDACTED_" + policyName + "]";
    }

    /**
     * @summary Generates an ephemeral AES-256 symmetric key for session-level database encryption.
     * @return a Base64-encoded AES key string
     */
    public String generateSessionKey() {
        try {
            javax.crypto.KeyGenerator keyGen = javax.crypto.KeyGenerator.getInstance("AES");
            keyGen.init(256);
            javax.crypto.SecretKey secretKey = keyGen.generateKey();
            return java.util.Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (Exception e) {
            log.error("Failed to generate AES-256 session key", e);
            throw new RuntimeException("Cryptographic failure: AES-256 generator not available", e);
        }
    }

    /**
     * @summary Derives a deterministic AES-256 key from a sessionId using HMAC-SHA256.
     * @logic Used by the EncryptedSessionInterceptor to encrypt/decrypt AgentMessage payloads
     *        without requiring a database lookup for the parent AgentSession's ephemeral key.
     *        The key is stable for a given sessionId, enabling consistent encrypt/decrypt cycles.
     *
     * @param sessionId the session identifier to derive the key from
     * @return a Base64-encoded AES-256 key derived from the sessionId
     */
    public String generateDeterministicKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Cannot derive encryption key from null sessionId");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            // Use a fixed application-level secret as the HMAC key
            byte[] hmacKey = "procurator-aes-derivation-key-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] derived = mac.doFinal(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(derived);
        } catch (Exception e) {
            log.error("Failed to derive deterministic key for session {}", sessionId, e);
            throw new RuntimeException("Cryptographic key derivation failure", e);
        }
    }

    /**
     * @summary Encrypts a plaintext payload using the provided Base64 AES-256 session key.
     * @logic Uses AES/GCM/NoPadding for authenticated encryption. Appends the initialization vector (IV) to the cipher text.
     */
    public String encryptPayload(String plaintext, String base64Key) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(base64Key);
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[12]; // GCM standard IV length
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] encryptedData = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prefix IV for decryption extraction
            byte[] combinedContext = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combinedContext, 0, iv.length);
            System.arraycopy(encryptedData, 0, combinedContext, iv.length, encryptedData.length);

            return java.util.Base64.getEncoder().encodeToString(combinedContext);
        } catch (Exception e) {
            log.error("AES-GCM encryption failed", e);
            throw new RuntimeException("Database Encryption Failure", e);
        }
    }

    /**
     * @summary Decrypts an AES/GCM encrypted payload using the provided Base64 session key.
     * @logic Extracts the 12-byte IV from the prefix and decrypts the remainder.
     */
    public String decryptPayload(String encryptedBase64, String base64Key) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) return encryptedBase64;
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(base64Key);
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");

            byte[] combinedContext = java.util.Base64.getDecoder().decode(encryptedBase64);
            
            byte[] iv = new byte[12];
            System.arraycopy(combinedContext, 0, iv, 0, iv.length);
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] encryptedData = new byte[combinedContext.length - iv.length];
            System.arraycopy(combinedContext, iv.length, encryptedData, 0, encryptedData.length);

            byte[] decryptedData = cipher.doFinal(encryptedData);
            return new String(decryptedData, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES-GCM decryption failed", e);
            throw new RuntimeException("Database Decryption Failure", e);
        }
    }
}
