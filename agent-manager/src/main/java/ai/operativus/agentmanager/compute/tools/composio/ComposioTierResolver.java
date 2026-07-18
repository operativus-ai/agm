package ai.operativus.agentmanager.compute.tools.composio;

import ai.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import ai.operativus.agentmanager.core.model.DecisionPackage;
import ai.operativus.agentmanager.core.spi.ToolTierResolverProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: Implements {@link ToolTierResolverProvider} for the Composio adapter.
 * Owns the HITL tier policy for any tool name beginning with the {@code composio_} prefix.
 * Returns {@link Optional#empty()} for non-Composio names so {@code HitlAdvisor} can consult
 * the next provider or fall through to its built-in static sets.
 * State: Mutable — DB tier cache rebuilt on {@link ComposioConfigChangedEvent}. Tier sets normalized to UPPERCASE.
 *
 * <p>Per agm-agentos-tool-parity-impl.md §3 (HITL tier resolution AC) + §4 (architectural
 * decisions) + audit Finding 7. Default tier is Tier 2 for any {@code composio_*} action not
 * explicitly listed in tier-1 (allow-list) or tier-3 (deny-list-style strict).</p>
 *
 * <p>Resolution precedence: DB row (if present and enabled) → {@code @Value} tier sets → Tier 2 default.</p>
 */
@Component
public class ComposioTierResolver implements ToolTierResolverProvider {

    private static final Logger log = LoggerFactory.getLogger(ComposioTierResolver.class);

    static final String TOOL_NAME_PREFIX = "composio_";

    private final Set<String> tier1Actions;
    private final Set<String> tier3Actions;
    private final ComposioActionConfigRepository actionConfigRepository;
    private final AtomicReference<Map<String, DecisionPackage.DecisionTier>> dbTierCache =
            new AtomicReference<>(Collections.emptyMap());

    public ComposioTierResolver(
            @Value("${agent.tools.composio.tier-1-actions:}") List<String> tier1Configured,
            @Value("${agent.tools.composio.tier-3-actions:}") List<String> tier3Configured,
            ComposioActionConfigRepository actionConfigRepository) {
        this.tier1Actions = normalize(tier1Configured);
        this.tier3Actions = normalize(tier3Configured);
        this.actionConfigRepository = actionConfigRepository;
    }

    @PostConstruct
    void loadDbTierCache() {
        rebuildDbCache();
    }

    @EventListener(ComposioConfigChangedEvent.class)
    public void onComposioConfigChanged(ComposioConfigChangedEvent event) {
        log.debug("ComposioTierResolver reloading DB tier cache: reason={}", event.reason());
        rebuildDbCache();
    }

    private void rebuildDbCache() {
        try {
            Map<String, DecisionPackage.DecisionTier> cache = new HashMap<>();
            actionConfigRepository.findByEnabledTrueOrderByActionName().forEach(cfg -> {
                DecisionPackage.DecisionTier tier = mapDbTier(cfg.getTier());
                if (tier != null) {
                    cache.put(cfg.getActionName().toUpperCase(), tier);
                }
            });
            dbTierCache.set(Collections.unmodifiableMap(cache));
            log.debug("ComposioTierResolver DB tier cache loaded: {} entries", cache.size());
        } catch (Exception e) {
            log.warn("ComposioTierResolver failed to reload DB tier cache — retaining prior snapshot", e);
        }
    }

    private static DecisionPackage.DecisionTier mapDbTier(Integer tier) {
        if (tier == null) return null;
        return switch (tier) {
            case 1 -> DecisionPackage.DecisionTier.TIER_1_SAFE;
            case 2 -> DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK;
            case 3 -> DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE;
            default -> null;
        };
    }

    private static Set<String> normalize(List<String> configured) {
        Set<String> result = new LinkedHashSet<>();
        for (String raw : configured) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed.toUpperCase());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Optional<DecisionPackage.DecisionTier> resolveTier(String toolName) {
        if (toolName == null || !toolName.startsWith(TOOL_NAME_PREFIX)) {
            return Optional.empty();
        }
        String composioAction = toolName.substring(TOOL_NAME_PREFIX.length()).toUpperCase();

        // DB row takes precedence over @Value static sets
        DecisionPackage.DecisionTier dbTier = dbTierCache.get().get(composioAction);
        if (dbTier != null) {
            return Optional.of(dbTier);
        }

        if (tier3Actions.contains(composioAction)) {
            return Optional.of(DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE);
        }
        if (tier1Actions.contains(composioAction)) {
            return Optional.of(DecisionPackage.DecisionTier.TIER_1_SAFE);
        }
        // Default for any other composio_* action: Tier 2 (FinOps block — proxy for HITL approval)
        return Optional.of(DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK);
    }
}
