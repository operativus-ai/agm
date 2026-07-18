package ai.operativus.agentmanager.control.service.erasure;

import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SessionErasureHandler implements ErasureHandler {

    private final SessionRepository sessionRepository;
    private final RunRepository runRepository;

    public SessionErasureHandler(SessionRepository sessionRepository, RunRepository runRepository) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
    }

    @Override
    public String domain() { return "sessions"; }

    @Override
    @Transactional
    public int erase(String userId) {
        var runs = runRepository.findByUserId(userId);
        runRepository.deleteAll(runs);

        var sessions = sessionRepository.findByUserId(userId);
        sessionRepository.deleteAll(sessions);

        return sessions.size() + runs.size();
    }
}
