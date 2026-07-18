package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.core.callback.AgentContextSnapshot;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RequiredActionType;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Resolves a pending {@link RequiredActionType#SWARM_ESCALATION_APPROVAL}
 *     by mapping the caller-provided {@code escalationId} to its underlying paused
 *     {@code agent_runs} row, validating tenant ownership, and dispatching the
 *     {@link AgentOperations#continueRun} resume on a virtual thread so the HTTP caller
 *     does not block on the agent's resumption work.
 * State: Stateless.
 *
 * <p>Why service-side parse, not a JSONB query: {@code agent_runs.required_action} is a
 * {@code TEXT} column. A naive {@code required_action::jsonb ->> 'escalationId'} predicate
 * fails the entire query if any legacy row contains non-JSON text. Iterating the
 * (typically small) PAUSED set per org and parsing each {@link RequiredAction} payload
 * sidesteps that risk and yields actionable {@link JsonProcessingException} log lines
 * for any malformed row encountered.
 */
@Service
public class EscalationResolveService {

    private static final Logger log = LoggerFactory.getLogger(EscalationResolveService.class);

    private final RunRepository runRepository;
    private final AgentOperations agentOperations;
    private final ObjectMapper objectMapper;

    public EscalationResolveService(RunRepository runRepository,
                                    AgentOperations agentOperations,
                                    ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.agentOperations = agentOperations;
        this.objectMapper = objectMapper;
    }

    /**
     * Locates the PAUSED agent_run whose persisted {@link RequiredAction} matches the
     * given {@code escalationId} within the caller's org, then dispatches the resume on
     * a virtual thread under a captured {@link AgentContextSnapshot} (matches the
     * approval-resolve pattern in {@code ApprovalService.resolveApprovalForOrg}).
     *
     * @return the runId tied to the escalation — useful for the caller to invalidate
     *     run-status caches.
     * @throws ResourceNotFoundException if no matching row exists in the caller's org.
     *     Cross-tenant attempts return the same 404 as missing rows; existence is never
     *     leaked.
     * @throws BusinessValidationException if the row is TEAM-paused or if the resume
     *     itself rejects the action (delegated from {@link AgentOperations#continueRun}).
     */
    public String resolveEscalationForOrg(String escalationId, RunStatus decision, String orgId) {
        AgentRun match = findPausedByEscalationIdAndOrg(escalationId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Escalation", escalationId));

        String runId = match.getId();
        log.info("Resolving escalation {} (runId={}, orgId={}) → {}",
                escalationId, runId, orgId, decision);

        AgentContextSnapshot resumeSnapshot = AgentContextSnapshot.capture();
        Thread.ofVirtual().start(() -> resumeSnapshot.run(() -> {
            try {
                agentOperations.continueRun(runId, decision.name());
            } catch (RuntimeException ex) {
                log.warn("Escalation resume failed escalationId={} runId={}",
                        escalationId, runId, ex);
            }
        }));

        return runId;
    }

    private Optional<AgentRun> findPausedByEscalationIdAndOrg(String escalationId, String orgId) {
        List<AgentRun> paused = runRepository.findByOrgIdAndStatus(orgId, RunStatus.PAUSED);
        for (AgentRun run : paused) {
            String reqAction = run.getRequiredAction();
            if (reqAction == null || reqAction.isBlank()) continue;
            try {
                RequiredAction parsed = objectMapper.readValue(reqAction, RequiredAction.class);
                if (parsed != null && escalationId.equals(parsed.escalationId())) {
                    return Optional.of(run);
                }
            } catch (JsonProcessingException e) {
                log.debug("Skipping paused runId={} with malformed required_action", run.getId());
            }
        }
        return Optional.empty();
    }
}
