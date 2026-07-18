package ai.operativus.agentmanager.integration.settings;

import ai.operativus.agentmanager.control.service.SettingsService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the {@code /api/v1/settings} surface —
 *   {@link ai.operativus.agentmanager.control.controller.SettingsController} →
 *   {@link SettingsService} → {@code app_settings} table. Pins the read/write round-trip,
 *   the SSOT-override contract used by {@code AgentModelResolverService}, the cache-eviction
 *   passthrough under {@code NoOpCacheConfig}, and three current-contract gaps (no key
 *   allow-list, no admin RBAC, no org scoping) that the matrix §26 aspiration calls for but
 *   the code does not yet enforce.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §26 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T024.
 *
 * Implementation notes / pins:
 *   - {@link ai.operativus.agentmanager.control.controller.SettingsController} has NO
 *     method-level security; {@link ai.operativus.agentmanager.control.config.SecurityConfig}
 *     falls through to {@code anyRequest().authenticated()} for {@code /api/v1/settings/**}.
 *     That means every authenticated caller can mutate global settings today — the matrix's
 *     "non-admin cannot write" assertion (§26 case 6) is aspirational. Case (f) pins the
 *     CURRENT shape (200 for ROLE_USER) so a future RBAC landing flips this test on purpose.
 *   - {@link SettingsService#updateSettings(Map)} accepts any string key and upserts it
 *     verbatim — there's no allow-list or validation. Matrix case 5 ("invalid key → 400")
 *     is aspirational. Case (e) pins the current shape (200 + row created).
 *   - {@code app_settings} has no {@code org_id} column (see migration 001-schema.sql §15).
 *     Settings are globally visible; matrix case 2 (org-level override) is aspirational.
 *     Case (g) pins that two different users see the same map.
 *   - The controller's {@code getAllSettings()} returns ONLY rows stored in {@code app_settings} —
 *     it does NOT surface the {@code @Value}-injected fallbacks (e.g. {@code gemini-2.5-flash}
 *     for {@code DEFAULT_MODEL_ROUTER}). Those fallbacks are only observable through the
 *     typed resolver methods like {@link SettingsService#getDefaultModelRouter()}. Case (a)
 *     pins both shapes so a refactor that starts emitting fallbacks through the HTTP payload
 *     (or that drops the @Value fallbacks entirely) is caught here.
 *   - {@link SettingsService#updateSettings(Map)} carries {@code @CacheEvict(value="settings",
 *     allEntries=true)}. Under {@code NoOpCacheConfig} (applied by {@link BaseIntegrationTest})
 *     the {@code settings} cache is a {@code NoOpCache} — every {@code @Cacheable} read hits
 *     the repo anyway, so cache-skew cannot explain a stale read. Case (c) therefore pins the
 *     functional invariant (write → typed read reflects the write) rather than cache
 *     semantics; a real Redis-backed profile would exercise the eviction path through the
 *     same assertion.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SettingsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, String>> STRING_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private SettingsService settingsService;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // §26 — Case (a): With no rows in app_settings, GET /api/v1/settings returns an empty map
    // (the controller only surfaces DB-backed rows). The @Value-injected defaults that back
    // the typed resolvers remain resolvable through the service API — pinned via the
    // autowired SettingsService. A non-empty payload here would indicate either (1) the
    // truncate stopped clearing app_settings, or (2) the controller started surfacing the
    // @Value fallbacks in the HTTP shape, both of which are deliberate changes.
    @Test
    void getAllSettingsReturnsEmptyMapWhenNoRowsExistAndValueBackedDefaultsResolveFromProperties() {
        HttpHeaders auth = authenticateAs("settings-reader", "settings-reader@test.local",
                "pass-settings-1234", List.of("ROLE_USER"));

        ResponseEntity<Map<String, String>> resp = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET, new HttpEntity<>(auth), STRING_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, String> body = resp.getBody();
        assertNotNull(body, "GET must return a JSON object even when there are no rows — null would indicate the controller switched to 204, breaking the UI's settings page");
        assertTrue(body.isEmpty(),
                "with an empty app_settings table the controller must surface an empty map, not the @Value fallbacks — surfacing them would conflate 'admin-overridden' and 'compile-time default' in the UI");

        // The typed resolver still returns the properties-injected default (application.properties
        // sets agentmanager.models.default.router=gemini-2.5-flash); if a future change drops that
        // property or changes the @Value fallback chain, this assertion catches it.
        String routerDefault = settingsService.getDefaultModelRouter();
        assertNotNull(routerDefault, "the SSOT resolver must never return null — a null default would break every AgentModelResolverService call on unconfigured agents");
        assertFalse(routerDefault.isBlank(), "the router fallback must be a non-blank model name");
    }

    // §26 — Case (b): PUT writes a single setting, next GET sees it. Pins the simplest
    // round-trip — without this the admin UI cannot function.
    @Test
    void putGlobalSettingPersistsToAppSettingsTableAndNextGetReturnsIt() {
        HttpHeaders auth = authenticateAs("settings-writer", "settings-writer@test.local",
                "pass-settings-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        String uniqueKey = "crawler.maxPages";
        Map<String, String> updates = Map.of(uniqueKey, "42");
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT, new HttpEntity<>(updates, auth), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        String persistedValue = jdbc.queryForObject(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?", String.class, uniqueKey);
        assertEquals("42", persistedValue,
                "PUT must upsert into app_settings — a null here would mean the @Transactional did not commit; a different value would mean the service mapped the incoming key to a different column");

        ResponseEntity<Map<String, String>> get = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET, new HttpEntity<>(auth), STRING_MAP);
        assertEquals(HttpStatus.OK, get.getStatusCode());
        assertEquals("42", get.getBody().get(uniqueKey),
                "GET after PUT must observe the write — a stale read under NoOpCacheManager implies the write never committed");
    }

    // §26 — Case (c): Writing DEFAULT_MODEL_ROUTER via PUT causes the typed resolver to
    // return the new value, covering matrix cases 4 (SSOT override) and 7 (cache eviction)
    // in a single assertion: under NoOpCacheConfig the @Cacheable read hits the repo every
    // time, so the new value surfaces the moment the transaction commits. A real Redis
    // profile would go through @CacheEvict + repo-reload instead; either shape satisfies
    // this invariant, which is why it's the right place to pin SSOT behavior.
    @Test
    void putSettingsOverridesPropertiesDefaultAndTypedResolverReadsNewValue() {
        HttpHeaders auth = authenticateAs("settings-ssot", "settings-ssot@test.local",
                "pass-settings-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        String baseline = settingsService.getDefaultModelRouter();
        assertNotNull(baseline);

        String overrideValue = "custom-router-model-" + UUID.randomUUID();
        Map<String, String> updates = Map.of("DEFAULT_MODEL_ROUTER", overrideValue);
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT, new HttpEntity<>(updates, auth), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        String resolved = settingsService.getDefaultModelRouter();
        assertEquals(overrideValue, resolved,
                "after PUT the typed resolver must prefer the DB row over the @Value fallback — a mismatch means the resolver re-reads a stale value, which would defeat the SSOT pattern AgentModelResolverService depends on");
        org.junit.jupiter.api.Assertions.assertNotEquals(baseline, resolved,
                "the override must change the resolved value — equality here would mean the test accidentally reused the same value as the properties default and isn't actually pinning the override path");
    }

    // §26 — Case (d): The bulk variant — a single PUT with multiple keys persists all of
    // them atomically. Pins that the service iterates the map and does not partially apply
    // on a mid-iteration failure (no keys here cause a failure — a deliberate bad-key test
    // would be needed to pin atomicity, and the current validation-less service can't
    // generate one).
    @Test
    void putMultipleSettingsBulkUpdatesEveryKeyInOneTransaction() {
        HttpHeaders auth = authenticateAs("settings-bulk", "settings-bulk@test.local",
                "pass-settings-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        Map<String, String> updates = Map.of(
                "COMPRESSION_THRESHOLD_CHARS", "8192",
                "SUMMARIZATION_THRESHOLD_TURNS", "20",
                "crawler.formats", "pdf,html,markdown"
        );
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT, new HttpEntity<>(updates, auth), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_settings WHERE setting_key IN (?, ?, ?)", Long.class,
                "COMPRESSION_THRESHOLD_CHARS", "SUMMARIZATION_THRESHOLD_TURNS", "crawler.formats");
        assertEquals(3L, rowCount,
                "all three keys must land in app_settings — a lower number means the bulk update partially applied and the service is not iterating the map correctly");

        ResponseEntity<Map<String, String>> get = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET, new HttpEntity<>(auth), STRING_MAP);
        assertEquals("8192", get.getBody().get("COMPRESSION_THRESHOLD_CHARS"));
        assertEquals("20", get.getBody().get("SUMMARIZATION_THRESHOLD_TURNS"));
        assertEquals("pdf,html,markdown", get.getBody().get("crawler.formats"));
    }

    // §26 — Case (e) — GAP PIN for matrix case 5 ("invalid setting key → 400"). The current
    // SettingsService does not maintain an allow-list of recognized keys — it upserts any
    // string you hand it into app_settings. This test documents the current shape so a
    // future validation landing (key allow-list + @Valid annotation) flips this assertion
    // from 200 → 400. Without a test here the gap is invisible.
    @Test
    void putSettingsWithUnknownKeyCurrentlyCreatesRowRatherThan400() {
        HttpHeaders auth = authenticateAs("settings-unknown", "settings-unknown@test.local",
                "pass-settings-1234", List.of("ROLE_USER", "ROLE_ADMIN"));

        String bogusKey = "totally.made.up.key." + UUID.randomUUID();
        Map<String, String> updates = Map.of(bogusKey, "whatever");
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT, new HttpEntity<>(updates, auth), Void.class);

        assertEquals(HttpStatus.OK, put.getStatusCode(),
                "SettingsService has no key allow-list today; an unknown key is upserted into app_settings and returns 200. When validation lands, flip this to 400 and add a body check — the gap is documented inline in the class Javadoc.");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_settings WHERE setting_key = ?", Long.class, bogusKey);
        assertEquals(1L, rowCount,
                "the bogus key is persisted today — a 0 here would indicate validation has landed and this test needs the assertion flip described above");
    }

    // §26 — Case (f) — RBAC enforcement. SettingsController.PUT now carries
    // @PreAuthorize("hasRole('ADMIN')"). A plain ROLE_USER caller is rejected at the
    // method-security gate (AccessDeniedException → 403 via GlobalExceptionHandler).
    // The row MUST NOT land in app_settings because the rejection happens before
    // SettingsService.updateSettings runs. R2 production fix.
    @Test
    void putSettingsRequiresAdmin_403ForRoleUser_R2ProductionFix() {
        HttpHeaders userOnly = authenticateAs("settings-plain-user", "settings-plain-user@test.local",
                "pass-settings-1234", List.of("ROLE_USER"));

        String guardedKey = "DEFAULT_MODEL_FAST_R2_GUARD_" + UUID.randomUUID();
        Map<String, String> updates = Map.of(guardedKey, "should-not-be-allowed");
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT, new HttpEntity<>(updates, userOnly), Void.class);

        assertEquals(HttpStatus.FORBIDDEN, put.getStatusCode(),
                "ROLE_USER must be rejected by SettingsController.updateSettings @PreAuthorize. A 401 here would mean authentication itself broke; a 200 would mean the @PreAuthorize gate regressed.");

        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_settings WHERE setting_key = ?", Long.class, guardedKey);
        assertEquals(0L, rowCount,
                "rejection MUST happen before SettingsService runs — a count > 0 would mean @PreAuthorize fired late, after the service had written.");
    }

    // §26 — Case (g) — GAP PIN for matrix case 2 ("org-level overrides"). The app_settings
    // table has no org_id column (migration 001-schema.sql §15), so every setting is
    // globally visible — a user from "orgB" sees the value "orgA" wrote. This test pins
    // that single-tenant shape; when per-org settings land (new table or new column), flip
    // the assertion to expect isolation.
    @Test
    void settingsAreGloballyVisibleBecauseAppSettingsHasNoOrgColumn() {
        HttpHeaders writerAuth = authenticateAs("settings-org-a", "settings-org-a@test.local",
                "pass-settings-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
        HttpHeaders readerAuth = authenticateAs("settings-org-b", "settings-org-b@test.local",
                "pass-settings-1234", List.of("ROLE_USER"));

        String sharedKey = "crawler.maxPages";
        String sharedValue = "777";
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT,
                new HttpEntity<>(Map.of(sharedKey, sharedValue), writerAuth), Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode());

        ResponseEntity<Map<String, String>> readerGet = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET, new HttpEntity<>(readerAuth), STRING_MAP);
        assertEquals(HttpStatus.OK, readerGet.getStatusCode());
        assertEquals(sharedValue, readerGet.getBody().get(sharedKey),
                "settings are global today because app_settings has no org_id column — reader seeing a different (or absent) value would indicate that per-org scoping has landed, at which point this test should assert isolation instead");
    }
}
