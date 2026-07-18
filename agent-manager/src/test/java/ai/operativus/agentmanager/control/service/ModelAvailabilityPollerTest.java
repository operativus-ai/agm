package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.ModelRepository;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.entity.ModelType;
import ai.operativus.agentmanager.core.model.ModelPingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for §7 Model Pinger Part 2 — {@link ModelAvailabilityPoller}.
 * Pins:
 *   - Empty repo: no save calls, no NPE.
 *   - All-up: every model row's available is set to true and last_pinged_at is set.
 *   - Mixed up/down: per-model outcomes correctly reflected; both rows persisted.
 *   - One model's ping throws: the loop continues past the failure and persists the rest.
 *   - The scheduler does not gate on availability — pingEntity is called regardless of prior state.
 */
@ExtendWith(MockitoExtension.class)
class ModelAvailabilityPollerTest {

    @Mock
    private ModelRepository modelRepository;

    @Mock
    private ModelService modelService;

    private ModelAvailabilityPoller poller;

    @BeforeEach
    void setUp() {
        poller = new ModelAvailabilityPoller(modelRepository, modelService);
    }

    @Test
    @DisplayName("Empty repository: no save calls, no exception thrown")
    void emptyRepo_noSaves() {
        when(modelRepository.findAll()).thenReturn(List.of());
        poller.pollAllModels();
        verify(modelService, never()).pingEntity(any());
        verify(modelRepository, never()).save(any());
    }

    @Test
    @DisplayName("All models reachable: each row's available is set true and persisted")
    void allUp_persistsAvailableTrue() {
        ModelEntity m1 = entity("m1", "OPENAI");
        ModelEntity m2 = entity("m2", "ANTHROPIC");
        when(modelRepository.findAll()).thenReturn(List.of(m1, m2));
        when(modelService.pingEntity(m1)).thenReturn(new ModelPingResult("m1", true, 50, null));
        when(modelService.pingEntity(m2)).thenReturn(new ModelPingResult("m2", true, 80, null));
        when(modelRepository.findById("m1")).thenReturn(Optional.of(m1));
        when(modelRepository.findById("m2")).thenReturn(Optional.of(m2));

        poller.pollAllModels();

        ArgumentCaptor<ModelEntity> saved = ArgumentCaptor.forClass(ModelEntity.class);
        verify(modelRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ModelEntity::getAvailable).containsExactly(true, true);
        assertThat(saved.getAllValues()).allMatch(e -> e.getLastPingedAt() != null);
    }

    @Test
    @DisplayName("Mixed up/down: per-model outcomes correctly reflected on each row")
    void mixedUpDown_persistsCorrectFlags() {
        ModelEntity up = entity("up", "OPENAI");
        ModelEntity down = entity("down", "ANTHROPIC");
        when(modelRepository.findAll()).thenReturn(List.of(up, down));
        when(modelService.pingEntity(up)).thenReturn(new ModelPingResult("up", true, 60, null));
        when(modelService.pingEntity(down)).thenReturn(new ModelPingResult("down", false, 5000, "timeout"));
        when(modelRepository.findById("up")).thenReturn(Optional.of(up));
        when(modelRepository.findById("down")).thenReturn(Optional.of(down));

        poller.pollAllModels();

        verify(modelRepository, times(2)).save(any());
        assertThat(up.getAvailable()).isTrue();
        assertThat(down.getAvailable()).isFalse();
    }

    @Test
    @DisplayName("One model's pingEntity throws: loop continues, surviving rows still persisted")
    void oneRowThrows_loopContinues() {
        ModelEntity ok = entity("ok", "OPENAI");
        ModelEntity broken = entity("broken", "WEIRD");
        when(modelRepository.findAll()).thenReturn(List.of(broken, ok));
        when(modelService.pingEntity(broken)).thenThrow(new RuntimeException("classloader explosion"));
        when(modelService.pingEntity(ok)).thenReturn(new ModelPingResult("ok", true, 50, null));
        when(modelRepository.findById("broken")).thenReturn(Optional.of(broken));
        when(modelRepository.findById("ok")).thenReturn(Optional.of(ok));

        poller.pollAllModels();

        // Both rows persisted: broken=false (catch-block path), ok=true (happy path)
        verify(modelRepository, times(2)).save(any());
        assertThat(broken.getAvailable()).isFalse();
        assertThat(ok.getAvailable()).isTrue();
    }

    @Test
    @DisplayName("Row deleted between findAll and findById: persistOutcome silently skips it")
    void rowGoneDuringTick_skipsPersist() {
        ModelEntity transient_ = entity("transient", "OPENAI");
        when(modelRepository.findAll()).thenReturn(List.of(transient_));
        when(modelService.pingEntity(transient_)).thenReturn(new ModelPingResult("transient", true, 50, null));
        when(modelRepository.findById("transient")).thenReturn(Optional.empty()); // row deleted mid-tick

        poller.pollAllModels();

        verify(modelRepository, never()).save(any()); // nothing to write
    }

    private ModelEntity entity(String id, String provider) {
        ModelEntity e = new ModelEntity(id, id + "-name", provider, "https://example", "key", id + "-model");
        e.setModelType(ModelType.CHAT);
        return e;
    }
}
