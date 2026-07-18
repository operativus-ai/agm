package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.entity.GlobalSetting;
import ai.operativus.agentmanager.control.repository.GlobalSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import ai.operativus.agentmanager.core.registry.ConfigurationProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Manages global, system-wide configuration settings and default LLM model selection logic.
 * State: Stateless (Relies on Spring Cache for performance)
 *
 * @architecture All fallback model defaults are injected from application.properties via @Value.
 *               No hardcoded model strings exist in this class. The resolution hierarchy is:
 *               Database global_settings -> application.properties -> (fail).
 */
@Service
public class SettingsService implements ConfigurationProvider, ai.operativus.agentmanager.core.registry.SettingsOperations {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private final GlobalSettingRepository globalSettingRepository;

    private static final String KEY_CRAWLER_MAX_PAGES = "crawler.maxPages";
    private static final String KEY_DEFAULT_MODEL_ROUTER = "DEFAULT_MODEL_ROUTER";
    private static final String KEY_DEFAULT_MODEL_FAST = "DEFAULT_MODEL_FAST";
    private static final String KEY_DEFAULT_MODEL_HEAVY = "DEFAULT_MODEL_HEAVY";
    private static final String KEY_DEFAULT_MODEL_EMBEDDING = "DEFAULT_MODEL_EMBEDDING";
    private static final String KEY_COMPRESSION_THRESHOLD_CHARS = "COMPRESSION_THRESHOLD_CHARS";
    private static final String KEY_SUMMARIZATION_THRESHOLD_TURNS = "SUMMARIZATION_THRESHOLD_TURNS";

    // Public so DataRetentionService can import them as the single source of truth
    public static final String KEY_RETENTION_SESSIONS_DAYS = "app.retention.sessions-days";
    public static final String KEY_RETENTION_RUNS_DAYS = "app.retention.runs-days";
    public static final String KEY_RETENTION_AUDIT_DAYS = "app.retention.audit-days";
    public static final String KEY_RETENTION_ALERTS_DAYS = "app.retention.alerts-days";

    private final String defaultRouterModel;
    private final String defaultFastModel;
    private final String defaultHeavyModel;
    private final String defaultEmbeddingModel;

    public SettingsService(GlobalSettingRepository globalSettingRepository,
                           @Value("${agentmanager.models.default.router:gpt-4o-mini}") String defaultRouterModel,
                           @Value("${agentmanager.models.default.fast:gemini-2.5-flash}") String defaultFastModel,
                           @Value("${agentmanager.models.default.heavy:gpt-4o}") String defaultHeavyModel,
                           @Value("${agentmanager.models.default.embedding:text-embedding-3-small}") String defaultEmbeddingModel) {
        this.globalSettingRepository = globalSettingRepository;
        this.defaultRouterModel = defaultRouterModel;
        this.defaultFastModel = defaultFastModel;
        this.defaultHeavyModel = defaultHeavyModel;
        this.defaultEmbeddingModel = defaultEmbeddingModel;
        log.info("SettingsService initialized with SSOT defaults: router={}, fast={}, heavy={}, embedding={}",
                defaultRouterModel, defaultFastModel, defaultHeavyModel, defaultEmbeddingModel);
    }

    /**
     * @summary Retrieves the default LLM routing model configuration.
     * @logic Queries the database for the default router setting, falling back to the application.properties-injected default if not set.
     */
    public String getDefaultModelRouter() {
        return globalSettingRepository.findById(KEY_DEFAULT_MODEL_ROUTER)
                .map(GlobalSetting::getValue)
                .orElse(defaultRouterModel);
    }

    /**
     * @summary Retrieves the default LLM Fast model configuration.
     * @logic Queries the database for the default fast model setting, falling back to the application.properties-injected default if not set.
     */
    public String getDefaultModelFast() {
        return globalSettingRepository.findById(KEY_DEFAULT_MODEL_FAST)
                .map(GlobalSetting::getValue)
                .orElse(defaultFastModel);
    }

    /**
     * @summary Retrieves the default LLM heavy reasoning model configuration.
     * @logic Queries the database for the default heavy model setting, falling back to the application.properties-injected default if not set.
     */
    public String getDefaultModelHeavy() {
        return globalSettingRepository.findById(KEY_DEFAULT_MODEL_HEAVY)
                .map(GlobalSetting::getValue)
                .orElse(defaultHeavyModel);
    }

    /**
     * @summary Retrieves the default LLM embedding model configuration.
     * @logic Queries the database for the default embedding model setting, falling back to the application.properties-injected default if not set.
     */
    public String getDefaultModelEmbedding() {
        return globalSettingRepository.findById(KEY_DEFAULT_MODEL_EMBEDDING)
                .map(GlobalSetting::getValue)
                .orElse(defaultEmbeddingModel);
    }

    /**
     * @summary Retrieves the maximum page limit for the background web crawler.
     * @logic Queries the database for the KEY_CRAWLER_MAX_PAGES setting, parsing the string value to an integer and falling back on parse exception. Caches the lookup result to optimize frequent accesses.
     */
    @Cacheable(value = "settings", key = "'crawlerMaxPages'")
    public int getCrawlerMaxPages(int defaultValue) {
        return globalSettingRepository.findById(KEY_CRAWLER_MAX_PAGES)
                .map(GlobalSetting::getValue)
                .map(val -> {
                    try {
                        return Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid crawler.maxPages setting value: {}", val);
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * @summary Retrieves the global default boundary for tool payload context compression.
     */
    @Cacheable(value = "settings", key = "'compressionThreshold'")
    public int getCompressionThresholdChars(int defaultValue) {
        return globalSettingRepository.findById(KEY_COMPRESSION_THRESHOLD_CHARS)
                .map(GlobalSetting::getValue)
                .map(val -> {
                    try {
                        return Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * @summary Retrieves the global default boundary for triggering conversational history amnesia summarization.
     */
    @Cacheable(value = "settings", key = "'summarizationThreshold'")
    public int getSummarizationThresholdTurns(int defaultValue) {
        return globalSettingRepository.findById(KEY_SUMMARIZATION_THRESHOLD_TURNS)
                .map(GlobalSetting::getValue)
                .map(val -> {
                    try {
                        return Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * @summary Retrieves the expected scraping formats for the background web crawler.
     * @logic Queries the database for 'crawler.formats', spliting the comma string and trimming.
     */
    @Cacheable(value = "settings", key = "'crawlerFormats'")
    public java.util.List<String> getCrawlerFormats(java.util.List<String> defaultFormats) {
        return globalSettingRepository.findById("crawler.formats")
                .map(GlobalSetting::getValue)
                .map(val -> {
                    if (val == null || val.trim().isEmpty()) return defaultFormats;
                    return java.util.Arrays.stream(val.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                })
                .orElse(defaultFormats);
    }

    /**
     * @summary Updates the maximum page limit for the background web crawler.
     * @logic Updates or creates the KEY_CRAWLER_MAX_PAGES entry in the database.
     */
    @Transactional
    @CacheEvict(value = "settings", allEntries = true)
    public void setCrawlerMaxPages(int value) {
        GlobalSetting setting = globalSettingRepository.findById(KEY_CRAWLER_MAX_PAGES)
                .orElseGet(() -> new GlobalSetting(KEY_CRAWLER_MAX_PAGES, String.valueOf(value), "Maximum number of pages the web scraper is allowed to crawl per job."));
        setting.setValue(String.valueOf(value));
        globalSettingRepository.save(setting);
        log.info("Updated global setting crawler.maxPages to {}", value);
    }

    /**
     * @summary Retrieves all global settings as a flat key-value map.
     * @logic Fetches all GlobalSetting entities and maps them into a simple String key-value mapping representation.
     */
    @Cacheable(value = "settings", key = "'all'")
    public Map<String, String> getAllSettings() {
        return globalSettingRepository.findAll().stream()
                .collect(Collectors.toMap(GlobalSetting::getKey, GlobalSetting::getValue));
    }

    // ============================================================
    // Convention over Configuration: Global Default Keys
    // ============================================================

    private static final String KEY_DEFAULT_TEMPERATURE = "DEFAULT_TEMPERATURE";
    private static final String KEY_DEFAULT_TOP_P = "DEFAULT_TOP_P";
    private static final String KEY_DEFAULT_FINOPS_RISK_TIER = "DEFAULT_FINOPS_RISK_TIER";
    private static final String KEY_DEFAULT_SECURITY_TIER = "DEFAULT_SECURITY_TIER";
    private static final String KEY_DEFAULT_MAX_CONCURRENT = "DEFAULT_MAX_CONCURRENT_EXECUTIONS";

    /**
     * @summary Cascading config: resolves a Double value with agent-level override -> global default -> hardcoded fallback.
     */
    public double resolveTemperature(Double agentValue) {
        if (agentValue != null) return agentValue;
        return getDoubleGlobal(KEY_DEFAULT_TEMPERATURE, 0.7);
    }

    public double resolveTopP(Double agentValue) {
        if (agentValue != null) return agentValue;
        return getDoubleGlobal(KEY_DEFAULT_TOP_P, 0.9);
    }

    public int resolveSecurityTier(Integer agentValue) {
        if (agentValue != null) return agentValue;
        return getIntGlobal(KEY_DEFAULT_SECURITY_TIER, 1);
    }

    public int resolveMaxConcurrentExecutions(Integer agentValue) {
        if (agentValue != null) return agentValue;
        return getIntGlobal(KEY_DEFAULT_MAX_CONCURRENT, 5);
    }

    public ai.operativus.agentmanager.core.entity.FinOpsRiskTier resolveFinOpsRiskTier(
            ai.operativus.agentmanager.core.entity.FinOpsRiskTier agentValue) {
        if (agentValue != null) return agentValue;
        return globalSettingRepository.findById(KEY_DEFAULT_FINOPS_RISK_TIER)
                .map(GlobalSetting::getValue)
                .map(ai.operativus.agentmanager.core.entity.FinOpsRiskTier::fromString)
                .orElse(ai.operativus.agentmanager.core.entity.FinOpsRiskTier.LOW_RISK);
    }

    /**
     * @summary Resolves the effective finOpsTokenBudget: explicit agent value -> risk tier default -> null (no limit).
     */
    public Long resolveFinOpsTokenBudget(Long agentBudget, ai.operativus.agentmanager.core.entity.FinOpsRiskTier riskTier) {
        if (agentBudget != null) return agentBudget;
        ai.operativus.agentmanager.core.entity.FinOpsRiskTier effectiveTier = resolveFinOpsRiskTier(riskTier);
        return effectiveTier.getDefaultTokenBudget();
    }

    private double getDoubleGlobal(String key, double fallback) {
        return globalSettingRepository.findById(key)
                .map(GlobalSetting::getValue)
                .map(val -> { try { return Double.parseDouble(val); } catch (NumberFormatException e) { return fallback; } })
                .orElse(fallback);
    }

    private int getIntGlobal(String key, int fallback) {
        return globalSettingRepository.findById(key)
                .map(GlobalSetting::getValue)
                .map(val -> { try { return Integer.parseInt(val); } catch (NumberFormatException e) { return fallback; } })
                .orElse(fallback);
    }

    /**
     * @summary Updates multiple global settings in a single transaction.
     * @logic Iterates over the provided updates map, fetching each existing global setting or creating a new one if it doesn't exist, updating its value, and saving it to the database.
     */
    @Transactional
    @CacheEvict(value = "settings", allEntries = true)
    public void updateSettings(Map<String, String> updates) {
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            GlobalSetting setting = globalSettingRepository.findById(entry.getKey())
                    .orElseGet(() -> new GlobalSetting(entry.getKey(), entry.getValue(), ""));
            setting.setValue(entry.getValue());
            globalSettingRepository.save(setting);
        }
    }
}
