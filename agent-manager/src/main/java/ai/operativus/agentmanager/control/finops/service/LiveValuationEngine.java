package ai.operativus.agentmanager.control.finops.service;

import ai.operativus.agentmanager.control.finops.model.FinOpsRecords.ModelValuationRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.operativus.agentmanager.control.repository.FinOpsValuationRateRepository;
import ai.operativus.agentmanager.core.entity.FinOpsValuationRateEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Provider-agnostic token-to-USD valuation engine.
 * Maintains a concurrent local cache of administrator-configured conversion rates per model
 * dimension. Rates are seeded at application startup and silently invalidated via a background
 * virtual thread scheduler, avoiding high-latency table lookups on the critical inference path.
 *
 * Architecture: High-read, low-write pattern. ConcurrentHashMap provides lock-free reads.
 * The background refresher is a daemon virtual thread — it dies with the JVM and never
 * blocks the platform thread pool.
 *
 * State: Stateful (Concurrent Rate Cache)
 */
@Service
public class LiveValuationEngine implements org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LiveValuationEngine.class);

    /** Cache refresh interval. Stays within prompt-cache TTL window. */
    private final long refreshIntervalMs;

    /** Fallback rate applied when a model is not registered — conservative to avoid silent $0 accounting. */
    private static final ModelValuationRate DEFAULT_RATE = new ModelValuationRate("unknown", 1.00, 2.00);

    private final ConcurrentHashMap<String, ModelValuationRate> rateCache = new ConcurrentHashMap<>();

    private final FinOpsValuationRateRepository repository;

    private volatile boolean shutdownRequested = false;
    private Thread refresherThread;

    public LiveValuationEngine(FinOpsValuationRateRepository repository,
                               @org.springframework.beans.factory.annotation.Value("${agentmanager.finops.valuation-refresh-ms:300000}") long refreshIntervalMs) {
        this.repository = repository;
        this.refreshIntervalMs = refreshIntervalMs;
        reloadRatesFromDatabase();
        scheduleBackgroundRefresh();
    }

    /**
     * @summary Reloads all model valuation rates from the underlying database.
     * @logic Pulls current rates from the administrator-managed finops_valuation_rate table.
     *        If the table is empty (e.g., initial startup before seed execution), it logs a warning.
     */
    private void reloadRatesFromDatabase() {
        if (shutdownRequested) return;
        try {
            List<FinOpsValuationRateEntity> entities = repository.findAll();
            if (entities.isEmpty()) {
                log.warn("No valuation rates found in database. Seed execution may be pending.");
                return;
            }
            entities.forEach(entity -> {
                rateCache.put(entity.getModelId(), new ModelValuationRate(
                    entity.getModelId(),
                    entity.getInputRatePerKTokens(),
                    entity.getOutputRatePerKTokens(),
                    entity.getCachedInputRatePerKTokens(),
                    entity.getReasoningRatePerKTokens()
                ));
            });
            log.info("LiveValuationEngine refreshed with {} model valuation rates from database.", rateCache.size());
        } catch (Exception e) {
            log.error("Failed to reload valuation rates from database. Retaining current cache.", e);
        }
    }

    /**
     * @summary Launches a background virtual thread for periodic cache refresh.
     * @logic Runs on a 5-minute cycle, logging active cache state. In production, this thread
     *        would reload administrator-managed rates from the valuation database table.
     *        The thread is a daemon (virtual) and will not prevent JVM shutdown.
     */
    private void scheduleBackgroundRefresh() {
        refresherThread = Thread.ofVirtual().name("finops-rate-refresher").start(() -> {
            while (!shutdownRequested && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(refreshIntervalMs);
                    if (shutdownRequested) break;
                    log.debug("LiveValuationEngine cache refresh tick executing...");
                    reloadRatesFromDatabase();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("LiveValuationEngine background refresher terminated.");
        });
    }

    /**
     * @summary Gracefully shuts down the background refresher thread on ApplicationContext close.
     * @logic Sets the volatile shutdown flag and interrupts the sleeping thread to ensure
     *        it exits before the DataSource and EntityManagerFactory beans are destroyed.
     */
    @Override
    public void destroy() {
        log.info("LiveValuationEngine shutting down background refresher...");
        shutdownRequested = true;
        if (refresherThread != null) {
            refresherThread.interrupt();
            try {
                refresherThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * @summary Registers or hot-updates a model valuation rate in the concurrent cache.
     * @logic Thread-safe upsert via ConcurrentHashMap. Key is the normalized (lowercase, trimmed) model ID.
     *        This is the administrator-facing write path invoked from the UI-managed valuation table.
     *
     * @param rate The model valuation rate record to register.
     */
    public void register(ModelValuationRate rate) {
        String normalizedKey = normalizeModelId(rate.modelId());
        rateCache.put(normalizedKey, rate);

        try {
            FinOpsValuationRateEntity entity = new FinOpsValuationRateEntity(
                normalizedKey,
                rate.inputRatePerKTokens(),
                rate.outputRatePerKTokens()
            );
            entity.setCachedInputRatePerKTokens(rate.cachedInputRatePerKTokens());
            entity.setReasoningRatePerKTokens(rate.reasoningRatePerKTokens());
            repository.save(entity);
            log.debug("Persisted hot-update for valuation rate model [{}]: input=${}/1k, output=${}/1k, cached=${}/1k, reasoning=${}/1k",
                normalizedKey, rate.inputRatePerKTokens(), rate.outputRatePerKTokens(),
                rate.cachedInputRatePerKTokens(), rate.reasoningRatePerKTokens());
        } catch (Exception e) {
            log.error("Failed to persist hot-update for valuation rate [{}] to database.", normalizedKey, e);
        }
    }

    /**
     * @summary Translates raw token counts into an estimated USD cost using the cached model rate.
     * @logic Resolves the best-matching rate via exact match then prefix matching. Falls back to the
     *        conservative DEFAULT_RATE if no match is found to prevent silent $0 financial accounting.
     *
     * @param modelId      The model identifier from ChatResponse metadata.
     * @param inputTokens  Number of prompt (input) tokens consumed.
     * @param outputTokens Number of completion (output) tokens generated.
     * @return             Estimated USD cost of this inference call.
     */
    public double calculateUsd(String modelId, long inputTokens, long outputTokens) {
        return calculateUsdBreakdown(modelId, inputTokens, outputTokens, 0L, 0L);
    }

    /**
     * @summary Translates granular token breakdown into a precise USD cost, accounting for cached
     *          prompt tokens and reasoning tokens at their distinct administrator-configured rates.
     * @logic
     *   - Standard input tokens = (inputTokens - cachedTokens), billed at inputRatePerKTokens.
     *   - Cached tokens billed at cachedInputRatePerKTokens when > 0; otherwise at inputRatePerKTokens.
     *   - Reasoning tokens billed at reasoningRatePerKTokens when > 0; otherwise at outputRatePerKTokens.
     *   - Standard output tokens billed at outputRatePerKTokens.
     *
     * @param modelId        The model identifier from ChatResponse metadata.
     * @param inputTokens    Total prompt (input) tokens — includes cached tokens.
     * @param outputTokens   Standard completion (output) tokens — excludes reasoning tokens.
     * @param reasoningTokens Reasoning/thinking tokens (non-zero only for o1/thinking-mode models).
     * @param cachedTokens   Cached prompt tokens (non-zero when prompt cache was hit).
     * @return               Precise estimated USD cost of this inference call.
     */
    public double calculateUsdBreakdown(String modelId, long inputTokens, long outputTokens,
                                         long reasoningTokens, long cachedTokens) {
        ModelValuationRate rate = resolveRate(modelId);

        // Cached tokens are a subset of input tokens — avoid double-billing
        long standardInputTokens = Math.max(0, inputTokens - cachedTokens);
        double inputCost = (standardInputTokens / 1_000.0) * rate.inputRatePerKTokens();

        // Cached tokens use the discounted rate if configured, else standard input rate
        double cachedRate = rate.cachedInputRatePerKTokens() > 0
            ? rate.cachedInputRatePerKTokens()
            : rate.inputRatePerKTokens();
        double cachedCost = (cachedTokens / 1_000.0) * cachedRate;

        // Reasoning tokens use distinct rate if configured, else output rate
        double reasoningRate = rate.reasoningRatePerKTokens() > 0
            ? rate.reasoningRatePerKTokens()
            : rate.outputRatePerKTokens();
        double reasoningCost = (reasoningTokens / 1_000.0) * reasoningRate;

        double outputCost = (outputTokens / 1_000.0) * rate.outputRatePerKTokens();

        double total = inputCost + cachedCost + reasoningCost + outputCost;
        log.trace("Valuation breakdown: model={}, standardInput={}tok, cached={}tok, reasoning={}tok, output={}tok, total=${:.4f}",
            modelId, standardInputTokens, cachedTokens, reasoningTokens, outputTokens, total);
        return total;
    }

    /**
     * @summary Returns an immutable snapshot of all currently registered model rates.
     */
    public Map<String, ModelValuationRate> getRateSnapshot() {
        return Map.copyOf(rateCache);
    }

    /**
     * @summary Resolves the best-matching valuation rate for a given model ID.
     * @logic Attempts exact match first, then prefix matching for versioned model IDs
     *        (e.g., "claude-3-5-sonnet-20240620" matches "claude-3-5-sonnet").
     *        Falls back to DEFAULT_RATE to ensure no execution is silently valued at $0.
     */
    public ModelValuationRate resolveRate(String modelId) {
        if (modelId == null) return DEFAULT_RATE;
        String normalized = normalizeModelId(modelId);

        ModelValuationRate exact = rateCache.get(normalized);
        if (exact != null) return exact;

        return rateCache.entrySet().stream()
            .filter(e -> normalized.startsWith(e.getKey()) || e.getKey().startsWith(normalized))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(DEFAULT_RATE);
    }

    private String normalizeModelId(String modelId) {
        return modelId == null ? "unknown" : modelId.toLowerCase().trim();
    }
}
