package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.repository.A2aRemoteAgentRepository;
import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import com.operativus.agentmanager.control.service.OutboundApiKeyMigrationService;
import com.operativus.agentmanager.core.entity.A2aRemoteAgentEntity;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of §22.6 — scheduled outbound
 * API key rotation for {@code a2a_remote_agents.outbound_api_key}. Exercises
 * {@link OutboundApiKeyMigrationService} against a real Postgres + a converter
 * configured with two key versions (v1, v2) and active=2, so that peer rows
 * seeded at v1 are identifiable rotation candidates.
 * State: Stateless. Spawns its own Spring context (different test properties than
 *   the shared one) so a multi-key converter can be constructed.
 *
 * Cases:
 *   1. rotateStaleRows_promotesV1RowToV2_plaintextPreserved
 *   2. rotateStaleRows_idempotentOnSubsequentRun_returnsZero
 *   3. rotateStaleRows_leavesAlreadyActiveRowsUnchanged
 *   4. legacyUnprefixedCiphertext_decryptsAndRotatesToV2
 *
 * The scheduled cron is set to the disable marker {@code "-"} so the @Scheduled
 * wrapper never auto-fires during the test; rotation is triggered by calling
 * {@link OutboundApiKeyMigrationService#rotateStaleRows()} directly.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@TestPropertySource(properties = {
        // Two-version key map. v1 uses the same 32-byte pattern as application-test.properties
        // so legacy-format rows written elsewhere remain decryptable.
        "agm.security.outbound-key-encryption.keys=" +
                "1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=," +
                "2:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=",
        "agm.security.outbound-key-encryption.active-version=2",
        // Rotation ENABLED so the service does work when invoked. Cron "-" disables
        // the @Scheduled auto-fire so the test is fully deterministic.
        "agm.security.outbound-key-rotation.enabled=true",
        "agm.security.outbound-key-rotation.cron=-"
})
public class OutboundApiKeyRotationRuntimeTest extends BaseIntegrationTest {

    /** Must match the v1 base64 key bytes in the @TestPropertySource above. */
    private static final byte[] V1_KEY_BYTES = Base64.getDecoder()
            .decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");

    private static final int IV_LEN  = 12;
    private static final int TAG_LEN = 128;

    @Autowired private OutboundApiKeyConverter        converter;
    @Autowired private OutboundApiKeyMigrationService migrationService;
    @Autowired private A2aRemoteAgentRepository       peerRepo;

    @BeforeEach
    void sanityCheckContext() {
        assertFalse(converter.isPassthroughMode(),
                "Converter must be in encrypted mode — check @TestPropertySource keys binding.");
        assertEquals(2, converter.activeVersion(),
                "Active version must be 2 so v1 rows are stale rotation candidates.");
    }

    // ─── Case 1 — v1 row rotates to v2; plaintext round-trip preserved ───
    @Test
    void rotateStaleRows_promotesV1RowToV2_plaintextPreserved() {
        String plaintext  = "sk-remote-" + UUID.randomUUID();
        String v1CipherDb = "v1:" + encryptWithV1(plaintext);
        String peerId     = seedPeerDirect("alpha", v1CipherDb, /*keyVersion=*/ 1);

        int rotated = migrationService.rotateStaleRows();

        A2aRemoteAgentEntity reloaded = peerRepo.findById(peerId).orElseThrow();
        String dbValue = jdbc.queryForObject(
                "SELECT outbound_api_key FROM a2a_remote_agents WHERE id = ?", String.class, peerId);

        assertAll(
                () -> assertEquals(1, rotated,
                        "Exactly one stale row at v1 should have been rotated."),
                () -> assertEquals(2, reloaded.getKeyVersion(),
                        "key_version column must be updated to the active version."),
                () -> assertNotNull(dbValue),
                () -> assertTrue(dbValue.startsWith("v2:"),
                        "Ciphertext wire format must carry the new v2 prefix: " + dbValue),
                () -> assertEquals(plaintext, reloaded.getOutboundApiKey(),
                        "Plaintext round-trip through v1→v2 rotation must preserve the value.")
        );
    }

    // ─── Case 2 — repeat invocation is a no-op ───
    @Test
    void rotateStaleRows_idempotentOnSubsequentRun_returnsZero() {
        String v1CipherDb = "v1:" + encryptWithV1("sk-idempotent");
        seedPeerDirect("beta", v1CipherDb, 1);

        int first  = migrationService.rotateStaleRows();
        int second = migrationService.rotateStaleRows();

        assertAll(
                () -> assertEquals(1, first,  "First pass rotates the one stale row."),
                () -> assertEquals(0, second, "Second pass must find no stale rows left.")
        );
    }

    // ─── Case 3 — rows already at the active version are untouched ───
    @Test
    void rotateStaleRows_leavesAlreadyActiveRowsUnchanged() {
        // Seed through JPA so the converter writes a fresh v2 ciphertext + key_version=2.
        A2aRemoteAgentEntity fresh = new A2aRemoteAgentEntity();
        fresh.setId(UUID.randomUUID().toString());
        fresh.setRemoteAgentId("remote-gamma");
        fresh.setBaseUrl("https://peer.example/gamma");
        fresh.setAlias("gamma");
        fresh.setOrgId("org-test");
        fresh.setOutboundApiKey("sk-already-fresh");
        fresh.setKeyVersion(2);
        fresh.setDataZone("us-east-1");
        fresh.setSecurityTier(1);
        fresh.setTrusted(true);
        fresh.setRegisteredBy("test");
        peerRepo.save(fresh);

        String dbBefore = jdbc.queryForObject(
                "SELECT outbound_api_key FROM a2a_remote_agents WHERE id = ?", String.class, fresh.getId());

        int rotated   = migrationService.rotateStaleRows();
        String dbAfter = jdbc.queryForObject(
                "SELECT outbound_api_key FROM a2a_remote_agents WHERE id = ?", String.class, fresh.getId());

        assertAll(
                () -> assertEquals(0, rotated, "No rows at v1 — rotation should be a no-op."),
                () -> assertTrue(dbBefore.startsWith("v2:"), "Pre-state must be v2."),
                () -> assertEquals(dbBefore, dbAfter,
                        "v2 row ciphertext must NOT be re-encrypted when it is already at the active version.")
        );
    }

    // ─── Case 4 — legacy unprefixed ciphertext decrypts as v1 and rotates to v2 ───
    @Test
    void legacyUnprefixedCiphertext_decryptsAndRotatesToV2() {
        String plaintext       = "sk-legacy";
        // Legacy format: no "vN:" prefix — converter must default-decode as v1.
        String legacyCipherDb  = encryptWithV1(plaintext);
        String peerId          = seedPeerDirect("delta", legacyCipherDb, /*keyVersion=*/ 1);

        int rotated = migrationService.rotateStaleRows();

        A2aRemoteAgentEntity reloaded = peerRepo.findById(peerId).orElseThrow();
        String dbAfter = jdbc.queryForObject(
                "SELECT outbound_api_key FROM a2a_remote_agents WHERE id = ?", String.class, peerId);

        assertAll(
                () -> assertEquals(1, rotated),
                () -> assertEquals(2, reloaded.getKeyVersion()),
                () -> assertTrue(dbAfter.startsWith("v2:"),
                        "Legacy unprefixed row must be rewritten with the v2 prefix after rotation."),
                () -> assertEquals(plaintext, reloaded.getOutboundApiKey(),
                        "Legacy plaintext must survive the rotation intact.")
        );
    }

    // ─── helpers ───

    /**
     * Inserts a peer row directly via JDBC so we can control the exact stored
     * ciphertext shape and key_version — something the JPA-layer converter wouldn't
     * let us do (it always encrypts with the active version).
     */
    private String seedPeerDirect(String alias, String outboundApiKeyDbValue, int keyVersion) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO a2a_remote_agents
                    (id, remote_agent_id, base_url, alias, org_id,
                     outbound_api_key, key_version, data_zone, security_tier, trusted,
                     registered_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                "remote-" + alias,
                "https://peer.example/" + alias,
                alias,
                "org-test",
                outboundApiKeyDbValue,
                keyVersion,
                "us-east-1",
                1,
                true,
                "test",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return id;
    }

    /** Produces {@code Base64(IV || Ciphertext || Tag)} using the v1 key — no version prefix. */
    private static String encryptWithV1(String plaintext) {
        try {
            SecretKey v1     = new SecretKeySpec(V1_KEY_BYTES, "AES");
            byte[]    iv     = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, v1, new GCMParameterSpec(TAG_LEN, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0,         iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Test helper encrypt failed", e);
        }
    }
}
