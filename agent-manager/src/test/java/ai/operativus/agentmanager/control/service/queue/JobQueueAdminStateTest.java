package ai.operativus.agentmanager.control.service.queue;

import ai.operativus.agentmanager.control.repository.GlobalSettingRepository;
import ai.operativus.agentmanager.core.entity.GlobalSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobQueueAdminStateTest {

    @Mock
    private GlobalSettingRepository repo;

    @Test
    void init_noPersistedFlag_defaultsToFalse() {
        when(repo.findById(JobQueueAdminState.SETTING_KEY)).thenReturn(Optional.empty());
        JobQueueAdminState state = new JobQueueAdminState(repo);
        state.init();
        assertThat(state.isPaused()).isFalse();
    }

    @Test
    void init_persistedTrue_startsAsPaused() {
        when(repo.findById(JobQueueAdminState.SETTING_KEY))
                .thenReturn(Optional.of(new GlobalSetting(JobQueueAdminState.SETTING_KEY, "true", "")));
        JobQueueAdminState state = new JobQueueAdminState(repo);
        state.init();
        assertThat(state.isPaused()).isTrue();
    }

    @Test
    void setPaused_true_updatesAtomicAndPersists() {
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JobQueueAdminState state = new JobQueueAdminState(repo);
        state.init();
        state.setPaused(true);

        assertThat(state.isPaused()).isTrue();
        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(JobQueueAdminState.SETTING_KEY);
        assertThat(captor.getValue().getValue()).isEqualTo("true");
    }

    @Test
    void setPaused_false_updatesAtomicAndPersistsFalse() {
        when(repo.findById(any()))
                .thenReturn(Optional.of(new GlobalSetting(JobQueueAdminState.SETTING_KEY, "true", "")));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        JobQueueAdminState state = new JobQueueAdminState(repo);
        state.init();
        state.setPaused(false);

        assertThat(state.isPaused()).isFalse();
        ArgumentCaptor<GlobalSetting> captor = ArgumentCaptor.forClass(GlobalSetting.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("false");
    }
}
