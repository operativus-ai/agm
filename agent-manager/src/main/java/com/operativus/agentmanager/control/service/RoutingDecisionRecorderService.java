package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.RoutingDecisionRepository;
import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Records one {@link RoutingDecisionEntity} per
 *     {@code resolveAgentId} invocation, asynchronously. The {@code @Async} boundary
 *     keeps recorder failures (DB hiccup, serialization issue) from impacting the
 *     dispatch request — recorder is best-effort observability, not load-bearing.
 *     Captures orgId/userId/etc as method parameters so propagation through the async
 *     thread doesn't depend on ScopedValue inheritance.
 * State: Stateless (Spring singleton)
 */
@Service
public class RoutingDecisionRecorderService {

    private static final Logger log = LoggerFactory.getLogger(RoutingDecisionRecorderService.class);

    private final RoutingDecisionRepository repository;
    private final MeterRegistry meterRegistry;

    public RoutingDecisionRecorderService(RoutingDecisionRepository repository,
                                          MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Async
    public void recordDecision(String orgId, String userId, String sessionId, String message,
                               String resolvedAgentId,
                               RoutingDecisionEntity.StrategyUsed strategy,
                               Double confidence,
                               int candidateCount,
                               long latencyMs,
                               String rationale) {
        try {
            RoutingDecisionEntity entity = new RoutingDecisionEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setOrgId(orgId);
            entity.setUserId(userId);
            entity.setSessionId(sessionId);
            entity.setMessageHash(sha256Hex(message));
            entity.setMessageLength(message == null ? 0 : message.length());
            entity.setResolvedAgentId(resolvedAgentId);
            entity.setResolutionStatus(resolvedAgentId == null
                    ? RoutingDecisionEntity.ResolutionStatus.UNRESOLVED
                    : RoutingDecisionEntity.ResolutionStatus.RESOLVED);
            entity.setStrategyUsed(strategy == null ? RoutingDecisionEntity.StrategyUsed.NONE : strategy);
            if (confidence != null) {
                entity.setConfidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP));
            }
            entity.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            entity.setCandidateCount(candidateCount);
            entity.setRationale(rationale);
            repository.save(entity);

            String strategyTag = entity.getStrategyUsed().name();
            String outcomeTag = entity.getResolutionStatus().name();
            Counter.builder("agm.routing.decisions")
                    .tag("strategy", strategyTag)
                    .tag("org", orgId == null ? "unknown" : orgId)
                    .tag("outcome", outcomeTag)
                    .register(meterRegistry).increment();
            Timer.builder("agm.routing.resolve.latency")
                    .tag("strategy", strategyTag)
                    .register(meterRegistry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("Failed to record routing decision for org={}, strategy={}: {}",
                    orgId, strategy, ex.getMessage());
        }
    }

    private static String sha256Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
