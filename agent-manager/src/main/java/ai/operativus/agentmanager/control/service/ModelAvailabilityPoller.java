package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ModelRepository;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.model.ModelPingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Domain Responsibility: §7 Model Pinger Part 2. Periodically probes every saved {@link ModelEntity}
 *     by invoking {@link ModelService#pingEntity} and writes the outcome (and last-pinged-at instant)
 *     back to the row. The {@code agent-runs} path does NOT consult these columns to gate execution —
 *     a stale {@code available=false} must never block a request that the provider has since recovered
 *     for. UI and admin tooling read the columns to render a freshness/health badge.
 * State: Stateless. Pinging cost is bounded by the number of saved models multiplied by one tiny
 *     per-provider call (chat models: a one-token completion; embedding models: a one-token embed).
 *
 * <p><b>Why fixedDelay, not fixedRate.</b> Pings hit external providers; tail latencies (auth retries,
 * network hiccups) routinely push the slowest probe past 30 seconds. {@code fixedDelay} measures from
 * the end of the previous tick, so we never have two overlapping passes mutating the same {@code models}
 * row concurrently. {@code fixedRate} would pile up overlapping ticks on slow runs.
 *
 * <p><b>Why @ConditionalOnProperty.</b> The poll surface is opt-in. Tests, local dev runs, and CI
 * boots don't need a periodic external-provider call burning quota — set
 * {@code agentmanager.scheduler.model-pinger.enabled=false} to disable.
 *
 * <p><b>Per-model error isolation.</b> Each model is pinged inside its own try/catch. One bad row
 * never prevents the rest of the batch from running. Each {@code modelRepository.save} runs in its
 * own implicit Spring Data transaction, so a single row's locking failure (e.g. row edited mid-tick)
 * doesn't roll back already-persisted sibling probes.
 */
@Component
@ConditionalOnProperty(name = "agentmanager.scheduler.model-pinger.enabled", havingValue = "true", matchIfMissing = true)
public class ModelAvailabilityPoller {

    private static final Logger log = LoggerFactory.getLogger(ModelAvailabilityPoller.class);

    private final ModelRepository modelRepository;
    private final ModelService modelService;

    public ModelAvailabilityPoller(ModelRepository modelRepository, ModelService modelService) {
        this.modelRepository = modelRepository;
        this.modelService = modelService;
    }

    /**
     * @summary Probes every saved model and persists the outcome to {@code models.available} +
     *     {@code models.last_pinged_at}.
     * @logic Default 5-minute fixedDelay between ticks. Each persist is an implicit per-call
     *     Spring Data transaction so one row's locking failure doesn't poison the batch.
     */
    @Scheduled(fixedDelayString = "${agentmanager.scheduler.model-pinger-ms:300000}")
    public void pollAllModels() {
        List<ModelEntity> all = modelRepository.findAll();
        if (all.isEmpty()) {
            log.debug("ModelAvailabilityPoller: no saved models, skipping tick");
            return;
        }

        int successes = 0;
        int failures = 0;
        for (ModelEntity entity : all) {
            try {
                ModelPingResult result = modelService.pingEntity(entity);
                persistOutcome(entity.getId(), result.available());
                if (result.available()) successes++; else failures++;
            } catch (RuntimeException e) {
                // Catch-all so one model's blow-up (e.g. provider classloader issues) doesn't kill the loop.
                log.warn("ModelAvailabilityPoller: unexpected error pinging model {}: {}",
                        entity.getId(), e.getMessage());
                try {
                    persistOutcome(entity.getId(), false);
                    failures++;
                } catch (RuntimeException ignored) {
                    // If the persist itself fails (e.g. row gone), give up on this row for this tick.
                }
            }
        }
        log.info("ModelAvailabilityPoller tick complete: {} models probed, {} available, {} unavailable",
                all.size(), successes, failures);
    }

    /**
     * @summary Persists a single model's ping outcome. Each {@code modelRepository.save} runs in its
     *     own implicit Spring Data transaction, so a stale-row optimistic-lock blowup on one model
     *     doesn't poison the whole batch.
     */
    void persistOutcome(String modelId, boolean available) {
        modelRepository.findById(modelId).ifPresent(entity -> {
            entity.setAvailable(available);
            entity.setLastPingedAt(LocalDateTime.now());
            modelRepository.save(entity);
        });
    }
}
