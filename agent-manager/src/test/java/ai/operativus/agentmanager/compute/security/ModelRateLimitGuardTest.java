package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.RateLimitExceededException;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin §6 M-12 Phase 2 enforcement semantics. The guard MUST
 * - no-op for null modelId (synthetic / default-model paths),
 * - no-op for models without a per-model override (rateLimitRpm == null or <= 0),
 * - admit the first {@code N} requests within the refresh window,
 * - reject the (N+1)st with {@link RateLimitExceededException} so the global handler
 *   can map it to a 429 with Retry-After: 60.
 *
 * Lazy-creation + change-pickup behaviour is also pinned because the live-update flow
 * (operator changes RPM via PUT /api/models/{id}) depends on appliedRpm bookkeeping.
 */
class ModelRateLimitGuardTest {

    private ModelOperations modelOperations;
    private RateLimiterRegistry registry;
    private ModelRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        modelOperations = mock(ModelOperations.class);
        registry = RateLimiterRegistry.ofDefaults();
        guard = new ModelRateLimitGuard(modelOperations, registry);
    }

    @Test
    void acquireOrThrow_nullModelId_isNoOp() {
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow(null));
        assertThat(guard.registeredLimiterCount()).isZero();
    }

    @Test
    void acquireOrThrow_modelWithoutOverride_isNoOp() {
        when(modelOperations.getModelEntityById("m-1"))
                .thenReturn(Optional.of(modelWithRpm(null)));

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-1"));
        assertThat(guard.registeredLimiterCount()).isZero();
    }

    @Test
    void acquireOrThrow_unknownModel_isNoOp() {
        when(modelOperations.getModelEntityById("m-x")).thenReturn(Optional.empty());
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-x"));
        assertThat(guard.registeredLimiterCount()).isZero();
    }

    @Test
    void acquireOrThrow_admitsUpToConfiguredCeiling_thenRejects() {
        when(modelOperations.getModelEntityById("m-2"))
                .thenReturn(Optional.of(modelWithRpm(3)));

        // First 3 admitted within the same minute window.
        for (int i = 0; i < 3; i++) {
            int attempt = i;
            assertThatNoException().as("admit #%d", attempt).isThrownBy(() -> guard.acquireOrThrow("m-2"));
        }
        // 4th rejected with the typed exception.
        assertThatThrownBy(() -> guard.acquireOrThrow("m-2"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("3 requests/minute");
    }

    @Test
    void acquireOrThrow_zeroOrNegativeRpm_treatedAsDisabled() {
        when(modelOperations.getModelEntityById("m-3"))
                .thenReturn(Optional.of(modelWithRpm(0)));
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-3"));

        when(modelOperations.getModelEntityById("m-3-neg"))
                .thenReturn(Optional.of(modelWithRpm(-1)));
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-3-neg"));
    }

    @Test
    void acquireOrThrow_perModelKeyedSeparately_oneModelDoesNotStarveAnother() {
        when(modelOperations.getModelEntityById("m-a"))
                .thenReturn(Optional.of(modelWithRpm(1)));
        when(modelOperations.getModelEntityById("m-b"))
                .thenReturn(Optional.of(modelWithRpm(1)));

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-a"));
        // m-a is now exhausted, but m-b has its own limiter and should admit.
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-b"));
        assertThatThrownBy(() -> guard.acquireOrThrow("m-a"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void acquireOrThrow_rpmChangedLive_rebuildsLimiterWithNewCeiling() {
        // Initial RPM = 1; first call admitted, second rejected.
        when(modelOperations.getModelEntityById("m-live"))
                .thenReturn(Optional.of(modelWithRpm(1)));
        guard.acquireOrThrow("m-live");
        assertThatThrownBy(() -> guard.acquireOrThrow("m-live"))
                .isInstanceOf(RateLimitExceededException.class);

        // Operator raises RPM to 5 → guard MUST drop the stale limiter and admit again.
        when(modelOperations.getModelEntityById("m-live"))
                .thenReturn(Optional.of(modelWithRpm(5)));
        for (int i = 0; i < 5; i++) {
            int attempt = i;
            assertThatNoException()
                    .as("admit after raise #%d", attempt)
                    .isThrownBy(() -> guard.acquireOrThrow("m-live"));
        }
        assertThatThrownBy(() -> guard.acquireOrThrow("m-live"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void invalidate_dropsCachedLimiterAndAppliedRpm_soNextSetTrulyStartsFresh() {
        // Operator sets rpm=1, exhausts it.
        when(modelOperations.getModelEntityById("m-clr"))
                .thenReturn(Optional.of(modelWithRpm(1)));
        guard.acquireOrThrow("m-clr");
        assertThatThrownBy(() -> guard.acquireOrThrow("m-clr"))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(guard.registeredLimiterCount()).isEqualTo(1);

        // Operator clears the override entirely (DB column → null).
        guard.invalidate("m-clr");
        assertThat(guard.registeredLimiterCount()).isZero();

        // Lookup now returns rpm=null → guard is a no-op, model can fire freely.
        when(modelOperations.getModelEntityById("m-clr"))
                .thenReturn(Optional.of(modelWithRpm(null)));
        for (int i = 0; i < 50; i++) {
            int attempt = i;
            assertThatNoException()
                    .as("post-clear admit #%d", attempt)
                    .isThrownBy(() -> guard.acquireOrThrow("m-clr"));
        }
    }

    @Test
    void invalidate_idempotent_onNeverConfiguredModel() {
        // Operator clears a model that never had a limiter — no exception, no state change.
        assertThatNoException().isThrownBy(() -> guard.invalidate("never-set"));
        assertThat(guard.registeredLimiterCount()).isZero();
    }

    @Test
    void invalidate_nullModelId_isNoOp() {
        assertThatNoException().isThrownBy(() -> guard.invalidate(null));
    }

    @Test
    void invalidate_thenReSetSameRpm_buildsFreshLimiterNotStaleOne() {
        // Reproducer for the exact A1 race: clear → re-set same RPM. The bare appliedRpm map
        // would have allowed the post-clear setter to skip rebuild because prev == new.
        // After invalidate(), appliedRpm is gone, so the next acquire goes through the
        // "first observation" branch and builds a fresh limiter.
        when(modelOperations.getModelEntityById("m-recycle"))
                .thenReturn(Optional.of(modelWithRpm(2)));
        guard.acquireOrThrow("m-recycle");
        guard.acquireOrThrow("m-recycle");
        assertThatThrownBy(() -> guard.acquireOrThrow("m-recycle"))
                .isInstanceOf(RateLimitExceededException.class);

        guard.invalidate("m-recycle");

        // Operator re-sets to the same 2 rpm — fresh limiter must admit 2 again.
        for (int i = 0; i < 2; i++) {
            int attempt = i;
            assertThatNoException()
                    .as("re-set admit #%d", attempt)
                    .isThrownBy(() -> guard.acquireOrThrow("m-recycle"));
        }
        assertThatThrownBy(() -> guard.acquireOrThrow("m-recycle"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    private static ModelEntity modelWithRpm(Integer rpm) {
        ModelEntity e = new ModelEntity();
        e.setId("m");
        e.setName("m");
        e.setProvider("OPENAI");
        e.setModelName("gpt-x");
        e.setRateLimitRpm(rpm);
        return e;
    }
}
