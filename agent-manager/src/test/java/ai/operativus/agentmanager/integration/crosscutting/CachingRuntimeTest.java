package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.control.registry.DatabaseAgentRegistry;
import ai.operativus.agentmanager.control.service.SettingsService;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Domain Responsibility: Black-box coverage of the {@link org.springframework.cache.annotation.EnableCaching}
 *   contract around {@link SettingsService} (and, by proxy, every cache-annotated service in
 *   the codebase — they all share the same {@code @EnableCaching} wiring defined in
 *   {@link ai.operativus.agentmanager.control.config.CacheConfig}). Pins:
 *   <ul>
 *     <li>{@code @Cacheable} results are served from the cache on subsequent calls without
 *         round-tripping the DB.</li>
 *     <li>{@code @CacheEvict(allEntries=true)} on write-path methods clears ALL keys from
 *         the named cache, not just the key being mutated.</li>
 *     <li>A second {@code @Cacheable} with a different SpEL key populates a distinct cache
 *         entry — the cache is keyed correctly (not single-entry overwrite).</li>
 *   </ul>
 *
 * Harness note: the default {@link ai.operativus.agentmanager.integration.support.NoOpCacheConfig}
 *   supplies a {@link org.springframework.cache.support.NoOpCacheManager} so sibling tests
 *   exercise the DB on every call. THIS test class overrides that with a
 *   {@link ConcurrentMapCacheManager} via {@link RealCacheOverride} so cache behavior is
 *   actually observable. {@code spring.main.allow-bean-definition-overriding=true} (set in
 *   {@code application-test.properties}) lets the override win bean-name collision.
 *
 * State: Stateless (per-test DB truncation; each test explicitly clears the cache in
 *   {@code @BeforeEach} so ordering is irrelevant).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.10 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T050.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, CachingRuntimeTest.RealCacheOverride.class})
public class CachingRuntimeTest extends BaseIntegrationTest {

    @Autowired private CacheManager cacheManager;
    @Autowired private SettingsService settingsService;
    @Autowired private DatabaseAgentRegistry agentRegistry;

    /**
     * Overrides {@link ai.operativus.agentmanager.integration.support.NoOpCacheConfig}'s
     * no-op bean with a real in-memory cache so cache hits and evictions are observable.
     * Pre-registers every cache name the production code reads — missing pre-registration
     * under a ConcurrentMapCacheManager with {@code allowNullValues=true, dynamic=true}
     * would still create caches on demand, but listing them keeps the override explicit.
     */
    @TestConfiguration
    static class RealCacheOverride {
        @Bean(name = "cacheManager")
        @Primary
        public CacheManager realCacheManager() {
            return new ConcurrentMapCacheManager("settings", "models", "agents", "allAgents");
        }
    }

    @BeforeEach
    void clearCacheBeforeEachTest() {
        // Per-test isolation — avoid a prior test's cache entries leaking into the next.
        cacheManager.getCacheNames().forEach(name -> {
            Cache c = cacheManager.getCache(name);
            if (c != null) c.clear();
        });
    }

    // §27.10 case 1 — Override sanity: the @TestConfiguration bean must be the one wired in.
    // If this fails every other assertion in this file is a false positive (NoOpCacheManager
    // returns empty Optional for any lookup, which would make our "cache hit masks DB" tests
    // look like cache hits when they're really reading a freshly-ignored cache). Pinning this
    // first keeps the debugging story short.
    @Test
    void cacheManagerIsTheRealOverrideNotNoOp() {
        assertTrue(cacheManager instanceof ConcurrentMapCacheManager,
                "CacheManager bean must be the ConcurrentMapCacheManager override from "
                        + "RealCacheOverride, not the NoOpCacheManager from NoOpCacheConfig. "
                        + "Got: " + cacheManager.getClass().getName());
    }

    // §27.10 case 2 — @Cacheable("settings", key="'all'") on SettingsService.getAllSettings().
    // First call populates the cache from the DB; second call must NOT hit the DB. We prove
    // that by mutating the DB directly via JDBC (which bypasses @CacheEvict on the service
    // proxy) between calls. If cache is active, the second call returns the STALE result.
    @Test
    void cacheableMethodReturnsStaleResultWhenDbMutatedOutOfBand() {
        // Warm the cache with the pre-mutation (empty) snapshot.
        Map<String, String> warmed = settingsService.getAllSettings();
        assertTrue(warmed.isEmpty(), "precondition — settings table is truncated per test, must be empty");

        // Mutate the DB directly — JDBC bypasses any service-layer @CacheEvict.
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, description)
                VALUES (?, ?, ?)
                """, "cache.probe.key", "probe-value", "out-of-band probe for cache staleness");

        // Second call must see the cached (pre-mutation) empty map, NOT the fresh DB row.
        Map<String, String> second = settingsService.getAllSettings();
        assertTrue(second.isEmpty(),
                "second @Cacheable call must return the cached empty snapshot, NOT the freshly "
                        + "JDBC-inserted probe row. Cache is not active — got: " + second);

        // And the cache itself must contain the 'all' entry.
        Cache settings = cacheManager.getCache("settings");
        assertNotNull(settings, "settings cache must exist");
        Cache.ValueWrapper allEntry = settings.get("all");
        assertNotNull(allEntry, "cache entry under key 'all' must be populated after the @Cacheable call");
    }

    // §27.10 case 3 — @CacheEvict(value="settings", allEntries=true) on updateSettings()
    // MUST flush the cache. After eviction the next @Cacheable call rebuilds from the DB,
    // which now includes both the out-of-band JDBC insert AND the updateSettings write.
    @Test
    void cacheEvictOnUpdateCausesNextReadToRefreshFromDb() {
        // Warm cache.
        settingsService.getAllSettings();

        // Insert out-of-band, confirm cache still serves stale.
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, description) VALUES (?, ?, ?)
                """, "cache.ob.key", "ob-value", "");

        Map<String, String> stale = settingsService.getAllSettings();
        assertFalse(stale.containsKey("cache.ob.key"),
                "sanity — cache must still be serving stale before the evict");

        // Trigger @CacheEvict by going through the proxy.
        settingsService.updateSettings(Map.of("cache.evict.key", "evicted-value"));

        // Next read must rebuild from DB — includes both the OOB row AND the evict-path write.
        Map<String, String> fresh = settingsService.getAllSettings();
        assertEquals("ob-value", fresh.get("cache.ob.key"),
                "post-evict read must see the out-of-band JDBC row — the cache was flushed "
                        + "and the method re-ran against the live DB");
        assertEquals("evicted-value", fresh.get("cache.evict.key"),
                "post-evict read must see the row written via updateSettings(...)");
    }

    // §27.10 case 4 — allEntries=true means ALL keys under "settings" are cleared, not just
    // one. Pre-warm two distinct keys ('all' via getAllSettings and a single-key Cacheable
    // via getCrawlerFormats which uses key='crawlerFormats'), call updateSettings(...) which
    // carries @CacheEvict(allEntries=true), assert both keys are gone.
    @Test
    void cacheEvictAllEntriesClearsEveryKeyNotJustOne() {
        // Pre-warm two different keys in the "settings" cache.
        settingsService.getAllSettings();                                      // key='all'
        settingsService.getCrawlerFormats(List.of("markdown"));                // key='crawlerFormats'

        Cache settings = cacheManager.getCache("settings");
        assertNotNull(settings);
        assertNotNull(settings.get("all"),
                "precondition — 'all' entry must be populated before evict");
        assertNotNull(settings.get("crawlerFormats"),
                "precondition — 'crawlerFormats' entry must be populated before evict");

        // Trigger evict.
        settingsService.updateSettings(Map.of("unrelated.key", "unrelated-value"));

        // Both keys must be gone (allEntries=true). If the annotation were allEntries=false
        // with key='all', only the 'all' key would drop and 'crawlerFormats' would survive
        // stale — this assertion catches that regression.
        assertNull(settings.get("all"),
                "'all' cache entry must be cleared by @CacheEvict(allEntries=true)");
        assertNull(settings.get("crawlerFormats"),
                "'crawlerFormats' cache entry must ALSO be cleared — @CacheEvict(allEntries=true) "
                        + "is meant to flush the ENTIRE cache, not one key");
    }

    // §27.10 case 5 — End-to-end HTTP path: the @CacheEvict on the write controller actually
    // invalidates the cache seen by the read controller. This is the classic "POST then GET
    // sees new value" contract the UI depends on. Proves the cache layer is wired around the
    // controller-bound service bean (not a sibling auto-wired copy that would bypass the proxy).
    @Test
    void httpPutInvalidatesCacheVisibleToSubsequentHttpGet() {
        // PUT /api/v1/settings is hasRole('ADMIN') (SettingsController) — a ROLE_USER caller gets 403.
        HttpHeaders auth = authenticateAs("cache-http-" + shortUuid(),
                "cache-http-" + shortUuid() + "@test.local", "pass-cache-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));

        // Warm the cache with an empty map via HTTP GET.
        ResponseEntity<Map> first = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth), Map.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertTrue(first.getBody().isEmpty(),
                "precondition — settings empty before PUT. Got: " + first.getBody());

        // Mutate via HTTP PUT.
        ResponseEntity<Void> put = rest.exchange(
                url("/api/v1/settings"), HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("http.cache.key", "http-cache-value"), auth),
                Void.class);
        assertEquals(HttpStatus.OK, put.getStatusCode(),
                "PUT /api/v1/settings must succeed (200). Got: " + put.getStatusCode());

        // GET after PUT must see the new row — i.e. the cache was evicted by the PUT's
        // @CacheEvict and rebuilt on this GET.
        ResponseEntity<Map> second = rest.exchange(
                url("/api/v1/settings"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth), Map.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals("http-cache-value", second.getBody().get("http.cache.key"),
                "post-PUT GET must see the new row — cache was not invalidated. Got: "
                        + second.getBody());
    }

    // §27.10 case 6 — Cache keying correctness. getCrawlerFormats uses key='crawlerFormats';
    // getAllSettings uses key='all'. They share the same cache name ('settings'), so a naive
    // implementation that forgot distinct keys would overwrite each other. This test pre-warms
    // both and confirms they coexist — proving the SpEL key expressions are actually being
    // honored by Spring's CacheAspectSupport.
    @Test
    void distinctSpelKeysStoreDistinctCacheEntries() {
        settingsService.getAllSettings();                          // key='all'
        List<String> formats = settingsService.getCrawlerFormats(List.of("markdown")); // key='crawlerFormats'
        assertNotNull(formats);

        Cache settings = cacheManager.getCache("settings");
        assertNotNull(settings);
        Cache.ValueWrapper allEntry = settings.get("all");
        Cache.ValueWrapper formatsEntry = settings.get("crawlerFormats");
        assertNotNull(allEntry, "SpEL key 'all' must produce its own cache entry");
        assertNotNull(formatsEntry, "SpEL key 'crawlerFormats' must produce its own cache entry");
        // Identity check: two distinct cache entries, not the same wrapper pointing to one value.
        assertTrue(allEntry != formatsEntry,
                "entries for different SpEL keys must be distinct wrappers — if equal, the keying "
                        + "is broken and one @Cacheable is overwriting the other");
    }

    // §27.10 case 7 — cache key is tenant-scoped, post-tenant-filter contract.
    //
    // Original test premise (one agent visible to two orgs) became invalid when
    // AgentRepository switched from findById to findByIdAndOrgId — the tenant filter now
    // hides cross-org reads at the row level. The cache invariant that still matters:
    // DatabaseAgentRegistry.findById's SpEL key MUST include AgentContextHolder.getOrgId(),
    // so an orgA-owned cache entry cannot be returned to an orgB caller.
    //
    // Strategy:
    //   - Seed one agent owned by orgA (org_id = orgA).
    //   - Under AgentContextHolder.orgId = orgA: findById resolves the agent → cache entry
    //     populated under "<agentId>|<orgA>" with the loaded AgentDefinition.
    //   - Under AgentContextHolder.orgId = orgB: findByIdAndOrgId returns empty → findById
    //     returns null → tenant filter is honored (the agent is invisible to orgB).
    //   - Load-bearing assertion: NO cache entry exists under the bare agentId. If one did,
    //     the SpEL key fell back to the method arg and any caller would inherit orgA's hit.
    //
    // Honest note on the orgB-keyed cache entry: production's @Cacheable on findById has no
    // `unless="#result == null"`, so the null result for orgB IS cached under "<agentId>|<orgB>".
    // That is fine — the cached value is null, not orgA's AgentDefinition, so no cross-tenant
    // read can leak through it. We deliberately do not assert presence/absence of the orgB
    // entry; the asymmetric per-org keying is what the rewrite proves, not the null-cache
    // policy (which is a separate design choice).
    @Test
    void cacheIsScopedPerOrganization() throws Exception {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, "gpt-4o-mini", "gpt-4o-mini", "gpt-4o-mini");

        String agentId = "agent-cache-org-" + shortUuid();
        String orgA = "org-cache-a-" + shortUuid();
        String orgB = "org-cache-b-" + shortUuid();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, now(), now())
                """, agentId, "cache-org-agent", "gpt-4o-mini", orgA);

        AgentDefinition resultA = ScopedValue.where(AgentContextHolder.orgId, orgA)
                .call(() -> agentRegistry.findById(agentId, orgA));
        AgentDefinition resultB = ScopedValue.where(AgentContextHolder.orgId, orgB)
                .call(() -> agentRegistry.findById(agentId, orgB));

        assertNotNull(resultA, "orgA owns the agent — findByIdAndOrgId(agentId, orgA) must resolve it");
        assertNull(resultB, "tenant filter must hide the orgA-owned agent from orgB callers");

        Cache agents = cacheManager.getCache("agents");
        assertNotNull(agents, "'agents' cache must exist (pre-registered in RealCacheOverride)");

        // Cache key format is "<agentId>|<orgId>" (see DatabaseAgentRegistry @Cacheable SpEL).
        String keyA = agentId + "|" + orgA;
        Cache.ValueWrapper wrapperA = agents.get(keyA);
        assertNotNull(wrapperA,
                "cache entry for orgA-scoped lookup must exist under key '" + keyA + "'. "
                        + "Missing means the SpEL key did not include AgentContextHolder.getOrgId().");
        assertNotNull(wrapperA.get(),
                "orgA cache value must be the resolved AgentDefinition, not a null sentinel");

        // Load-bearing regression guard: the un-scoped raw agentId must NOT be a key. If it
        // is, the SpEL key fell back to the method arg and orgA's read would be returned to
        // any caller — the multi-tenant cache leak this test exists to prevent.
        assertNull(agents.get(agentId),
                "raw agentId '" + agentId + "' must NOT be a cache key — that would indicate "
                        + "the SpEL key fell back to the method arg and cross-tenant reads leak.");
    }

    // §27.10 case — the Redis 24h TTL contract is covered by a sibling test class that spins
    // up a Testcontainers Redis and pins spring.cache.redis.time-to-live=PT2S so expiry is
    // observable in seconds. This class uses ConcurrentMapCacheManager (no TTL concept) so
    // it cannot host that assertion. See {@link CacheTtlRuntimeTest#cacheEntriesExpireAfterConfiguredTtl}.

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
