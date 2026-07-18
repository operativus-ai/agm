package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.AlertEventRepository;
import com.operativus.agentmanager.control.repository.AlertRuleRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AlertEvent;
import com.operativus.agentmanager.core.entity.AlertRule;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import com.operativus.agentmanager.core.model.TenantConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates configured alert rules against live Micrometer metrics on a scheduled interval.
 * Fires AlertEvent records when thresholds are breached.
 */
@Service
public class AlertingService {

    private static final Logger log = LoggerFactory.getLogger(AlertingService.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    public AlertingService(AlertRuleRepository ruleRepository, AlertEventRepository eventRepository, MeterRegistry meterRegistry, ApplicationEventPublisher eventPublisher) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD for AlertRules (all org-scoped to caller via AgentContextHolder) ---

    public List<AlertRule> getAllRules() {
        return ruleRepository.findByOrgId(callerOrgId());
    }

    public AlertRule getRule(String id) {
        return ruleRepository.findByIdAndOrgId(id, callerOrgId()).orElseThrow(() ->
                new com.operativus.agentmanager.core.exception.ResourceNotFoundException("AlertRule", id));
    }

    @Transactional
    public AlertRule createRule(AlertRule rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId(UUID.randomUUID().toString());
        }
        // Server-derived orgId; body-injected value (if any) is ignored via @JsonProperty(READ_ONLY)
        // on the entity, but defensively overwrite here too in case a non-Jackson code path
        // populates the field.
        rule.setOrgId(callerOrgId());
        return ruleRepository.save(rule);
    }

    @Transactional
    public AlertRule updateRule(String id, AlertRule update) {
        AlertRule existing = getRule(id); // 404 on cross-tenant via getRule's findByIdAndOrgId
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setMetricName(update.getMetricName());
        existing.setCondition(update.getCondition());
        existing.setThreshold(update.getThreshold());
        existing.setWindowSeconds(update.getWindowSeconds());
        existing.setSeverity(update.getSeverity());
        existing.setEnabled(update.isEnabled());
        existing.setNotificationChannel(update.getNotificationChannel());
        // orgId is immutable post-create; do NOT copy from `update` (which carries the
        // body-injected value, ignored on input but still readable from the deserialized object).
        return ruleRepository.save(existing);
    }

    @Transactional
    public void deleteRule(String id) {
        if (!ruleRepository.existsByIdAndOrgId(id, callerOrgId())) {
            throw new com.operativus.agentmanager.core.exception.ResourceNotFoundException("AlertRule", id);
        }
        ruleRepository.deleteById(id);
    }

    // --- AlertEvents (org-scoped) ---

    public Page<AlertEvent> getActiveAlerts(Pageable pageable) {
        return eventRepository.findByAcknowledgedFalseAndOrgIdOrderByFiredAtDesc(callerOrgId(), pageable);
    }

    @Transactional
    public void acknowledgeAlert(String eventId) {
        AlertEvent event = eventRepository.findByIdAndOrgId(eventId, callerOrgId()).orElseThrow(() ->
                new com.operativus.agentmanager.core.exception.ResourceNotFoundException("AlertEvent", eventId));
        event.setAcknowledged(true);
        eventRepository.save(event);
    }

    /**
     * Resolves caller's orgId from {@link AgentContextHolder}, falling back to
     * {@link TenantConstants#DEFAULT_SYSTEM_ORG} when no authenticated context exists
     * (system-background callers). Matches the wave-1..wave-5 pattern.
     */
    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }

    // --- Scheduled Evaluation ---

    @Scheduled(fixedRateString = "${agentmanager.scheduler.alerting-ms:60000}")
    @Transactional
    public void evaluateRules() {
        List<AlertRule> enabledRules = ruleRepository.findByEnabledTrue();
        for (AlertRule rule : enabledRules) {
            try {
                Double value = readMetric(rule.getMetricName());
                if (value == null) continue;

                boolean breached = switch (rule.getCondition().toUpperCase()) {
                    case "GT" -> value > rule.getThreshold();
                    case "GTE" -> value >= rule.getThreshold();
                    case "LT" -> value < rule.getThreshold();
                    case "LTE" -> value <= rule.getThreshold();
                    case "EQ" -> Math.abs(value - rule.getThreshold()) < 0.001;
                    default -> false;
                };

                if (breached) {
                    // §25.7 cooldown: suppress re-fires within rule.windowSeconds of the most
                    // recent event for this rule. Prevents flapping when a metric hovers near
                    // the threshold across consecutive ticks.
                    int windowSeconds = Math.max(rule.getWindowSeconds(), 0);
                    if (windowSeconds > 0) {
                        LocalDateTime cooldownFloor = LocalDateTime.now().minusSeconds(windowSeconds);
                        if (eventRepository.existsByRuleIdAndFiredAtAfter(rule.getId(), cooldownFloor)) {
                            continue;
                        }
                    }

                    String message = String.format("Alert '%s': metric %s = %.4f %s threshold %.4f",
                            rule.getName(), rule.getMetricName(), value, rule.getCondition(), rule.getThreshold());
                    log.warn(message);

                    AlertEvent event = new AlertEvent(
                            UUID.randomUUID().toString(),
                            rule.getId(),
                            value,
                            message,
                            rule.getSeverity()
                    );
                    // Stamp the parent rule's orgId so the event is tenant-scoped end-to-end.
                    event.setOrgId(rule.getOrgId());
                    eventRepository.save(event);
                    eventPublisher.publishEvent(new AlertFiredEvent(this, rule.getId(), event.getId(), rule.getSeverity(), message, rule.getOrgId()));
                }
            } catch (Exception e) {
                log.debug("Failed to evaluate alert rule '{}': {}", rule.getName(), e.getMessage());
            }
        }
    }

    private Double readMetric(String metricName) {
        // Try gauge first, then counter, then distribution summary
        Gauge gauge = meterRegistry.find(metricName).gauge();
        if (gauge != null) return gauge.value();

        var counter = meterRegistry.find(metricName).counter();
        if (counter != null) return counter.count();

        var summary = meterRegistry.find(metricName).summary();
        if (summary != null) return summary.totalAmount();

        var timer = meterRegistry.find(metricName).timer();
        if (timer != null) return (double) timer.count();

        return null;
    }
}
