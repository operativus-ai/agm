package com.operativus.agentmanager.control.service.queue;

import com.operativus.agentmanager.control.repository.GlobalSettingRepository;
import com.operativus.agentmanager.core.entity.GlobalSetting;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Domain Responsibility: Holds and persists the job-queue pause flag (anti-pattern G-A5).
 * The flag is stored in {@code app_settings} under key {@code job_queue.paused} so it
 * survives JVM restarts. A local {@link AtomicBoolean} cache is refreshed on every
 * {@link #setPaused} call; {@link PersistentJobQueueService} reads the cache on each poll
 * tick, so no DB hit occurs on the hot path.
 *
 * <p>All mutations go through this component — callers must NOT write to
 * {@code GlobalSettingRepository} directly for this key.</p>
 *
 * State: Stateful singleton (pause flag).
 */
@Component
public class JobQueueAdminState {

    static final String SETTING_KEY = "job_queue.paused";

    private static final Logger log = LoggerFactory.getLogger(JobQueueAdminState.class);

    private final GlobalSettingRepository settingRepository;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public JobQueueAdminState(GlobalSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    void init() {
        boolean persisted = settingRepository.findById(SETTING_KEY)
                .map(s -> "true".equalsIgnoreCase(s.getValue()))
                .orElse(false);
        paused.set(persisted);
        if (persisted) {
            log.warn("[job-queue] Queue is PAUSED on startup (persisted flag set). " +
                     "Call POST /api/v1/observability/background-jobs/resume to re-enable.");
        } else {
            log.info("[job-queue] Queue is ACTIVE on startup.");
        }
    }

    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Updates both the in-process flag and the persisted {@code app_settings} row atomically
     * enough for ops use: a tiny window exists between the atomic update and the DB save, but
     * the flag is only read on the 30-second poll interval so the skew is inconsequential.
     */
    public void setPaused(boolean value) {
        paused.set(value);
        GlobalSetting setting = new GlobalSetting(
                SETTING_KEY,
                String.valueOf(value),
                "Whether the background job queue is administratively paused");
        settingRepository.save(setting);
        log.info("[job-queue] Queue pause flag set to {} (persisted)", value);
    }
}
