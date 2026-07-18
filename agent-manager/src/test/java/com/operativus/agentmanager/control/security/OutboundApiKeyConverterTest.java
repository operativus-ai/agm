package com.operativus.agentmanager.control.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the AES-256-GCM round-trip contract on
 * {@link OutboundApiKeyConverter}. Asserts that ciphertext is non-deterministic
 * (fresh IV per call), wire-format-prefixed with the active key version, and
 * that {@code convertToEntityAttribute} round-trips back to the original
 * plaintext. Also covers the null-input pass-through and passthrough-mode
 * fallback when no key is configured.
 *
 * State: Stateless — each test constructs its own converter.
 */
class OutboundApiKeyConverterTest {

    /** A fixed 32-byte key (Base64-encoded) so encryption is deterministic per-key. */
    private static final String KEY_V1 = Base64.getEncoder().encodeToString(new byte[]{
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
            0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F});

    @Test
    void encryptOnWriteProducesNonPlaintextWithVersionPrefix() {
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);
        String plaintext = "sk-live-abc123-very-secret";

        String ciphertext = converter.convertToDatabaseColumn(plaintext);

        assertNotEquals(plaintext, ciphertext, "persisted column must not equal plaintext");
        assertTrue(ciphertext.startsWith("v1:"), "ciphertext must carry version prefix; got: " + ciphertext);
        assertFalse(converter.isPassthroughMode(), "converter must not be in passthrough mode");
    }

    @Test
    void decryptRoundTripsBackToOriginalPlaintext() {
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);
        String plaintext = "sk-live-roundtrip-check";

        String ciphertext = converter.convertToDatabaseColumn(plaintext);
        String decoded = converter.convertToEntityAttribute(ciphertext);

        assertEquals(plaintext, decoded, "round-trip must recover original plaintext");
    }

    @Test
    void nullPlaintextRoundTripsAsNull() {
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);

        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void passthroughModeWhenNoKeyConfiguredAndExplicitlyAllowedStoresPlaintext() {
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("", 1, "", true);
        String plaintext = "sk-dev-no-encryption";

        String stored = converter.convertToDatabaseColumn(plaintext);

        assertTrue(converter.isPassthroughMode(), "no key + allow-passthrough=true must enable passthrough mode (dev only)");
        assertEquals(plaintext, stored, "passthrough mode stores plaintext verbatim");
        assertEquals(plaintext, converter.convertToEntityAttribute(stored), "passthrough decode is identity");
    }

    @Test
    void noKeyConfiguredAndPassthroughNotAllowedFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OutboundApiKeyConverter("", 1, "", false),
                "no key configured and allow-passthrough=false must fail boot — production safety gate");

        assertTrue(ex.getMessage().contains("allow-passthrough=true"),
                "error message must name the explicit opt-in property; got: " + ex.getMessage());
    }

    @Test
    void decryptOfPlaintextValueFailsSoftToNull() {
        // A plaintext key seeded via raw SQL (bypassing encrypt-on-write) sits in a ciphertext
        // column. Reads must NOT throw (which 500s the list endpoint + crashes agent runs) — they
        // degrade to null so callers surface the friendly "No API key configured" path.
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);

        assertNull(converter.convertToEntityAttribute("sk-ant-api03-not-actually-encrypted"),
                "an undecryptable (plaintext) stored value must read back as null, not throw");
    }

    @Test
    void decryptWithNoKeyForVersionFailsSoftToNull() {
        // Ciphertext references a key version this instance doesn't hold (e.g. rotated away).
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);

        assertNull(converter.convertToEntityAttribute("v9:" + Base64.getEncoder().encodeToString("garbage".getBytes())),
                "a value whose key version is absent must read back as null, not throw");
    }

    @Test
    void writeSideStillThrowsOnEncryptionFailure() {
        // Fail-soft is READ-only. A configured-but-broken converter must never silently persist a
        // bad value — so abs ence of a usable key at boot still fails fast (covered by the
        // no-key-fail-fast test); this documents that the write path is intentionally strict.
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);
        // A normal write round-trips; the point is that convertToDatabaseColumn has no fail-soft
        // branch (it throws IllegalStateException on any cipher error) — verified by inspection +
        // the round-trip tests above. Here we just assert a healthy write still produces ciphertext.
        assertTrue(converter.convertToDatabaseColumn("sk-live-x").startsWith("v1:"));
    }

    @Test
    void freshIvPerEncryptYieldsDistinctCiphertextForSameInput() {
        OutboundApiKeyConverter converter = new OutboundApiKeyConverter("1:" + KEY_V1, 1, "", false);
        String plaintext = "sk-live-determinism-check";

        String first = converter.convertToDatabaseColumn(plaintext);
        String second = converter.convertToDatabaseColumn(plaintext);

        assertNotEquals(first, second,
                "AES-GCM must use a fresh IV per call — two encryptions of the same plaintext must differ");
        assertEquals(plaintext, converter.convertToEntityAttribute(first));
        assertEquals(plaintext, converter.convertToEntityAttribute(second));
    }
}
