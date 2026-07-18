package ai.operativus.agentmanager.compute.advisor;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Fast, lightweight regex-based local safety check avoiding remote NLP API calls.
 * State: Stateless
 */
@Service
public class LocalRegexModerationService implements ModerationService {

    private final Logger log = LoggerFactory.getLogger(LocalRegexModerationService.class);

    /**
     * @summary Moderates local string variables to ensure safe semantic values before transmission.
     * @logic Parses the string buffer, throwing a SecurityException if blocked terms are identified.
     *     Returns {@link ModerationResult#clean()} on the pass-through path. The score surface is
     *     currently always 0.0 here — future regex extensions can populate non-zero soft-signal
     *     scores without changing the advisor wiring (the advisor already records the score into a
     *     Micrometer DistributionSummary).
     */
    @Override
    public ModerationResult checkContent(String content) throws SecurityException {
        if (content == null || content.trim().isEmpty()) {
            return ModerationResult.clean();
        }

        if (content.contains("BOMB_MAKING_INSTRUCTIONS")) {
            log.warn("LocalRegexModerationService blocked output containing explicitly banned signature.");
            throw new SecurityException("Output blocked due to safety violations (Regex check flagged content).");
        }

        return ModerationResult.clean();
    }
}
