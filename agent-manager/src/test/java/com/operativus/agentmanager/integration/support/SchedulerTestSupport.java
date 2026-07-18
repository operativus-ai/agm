package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import com.operativus.agentmanager.compute.memory.MemoryConsolidationWorker;
import com.operativus.agentmanager.compute.service.BatchReasoningQueueService;
import com.operativus.agentmanager.control.a2a.PeerHealthMonitor;
import com.operativus.agentmanager.control.service.AlertIntegrationService;
import com.operativus.agentmanager.control.service.AlertingService;
import com.operativus.agentmanager.control.service.ApprovalService;
import com.operativus.agentmanager.control.service.DataRetentionService;
import com.operativus.agentmanager.control.service.PersistentJobQueueService;
import com.operativus.agentmanager.control.service.ScheduleExecutionPoller;
import com.operativus.agentmanager.control.service.SloTrackingService;

/**
 * Domain Responsibility: Direct-invoke façade for every {@code @Scheduled} bean in
 *   the application. Production schedules these on fixed-rate or cron triggers, but
 *   {@code application-test.properties} pushes every interval to 24h (decision 4.4)
 *   so nothing fires mid-test. Tests instead exercise the scheduled work by calling
 *   the corresponding {@code tickXxx()} method here.
 * State: Stateless (delegates to autowired Spring beans).
 *
 * Why direct invocation, not property override: Spring resolves
 *   {@code @Scheduled(fixedRateString="${prop}")} at bean post-processing.
 *   {@code @DynamicPropertySource} cannot change the interval of an already-scheduled
 *   method, and recreating the bean per-test defeats the singleton container model.
 *   Calling the work method directly is the documented escape hatch (decision 4.4).
 *
 * Coverage: Mirrors all 11 scheduled methods discovered via {@code @Scheduled} grep.
 *   {@link DataRetentionService#enforceRetentionPolicies()} runs on a 3am cron in
 *   prod (no property override surface) — it's still exposed here so tests can drive
 *   it deterministically.
 *
 * Usage: {@code @Import(SchedulerTestSupport.class)} on the test class, then
 *   {@code @Autowired protected SchedulerTestSupport scheduler;}.
 */
public class SchedulerTestSupport {

    private final ApprovalService approvals;
    private final DataRetentionService retention;
    private final AlertingService alerting;
    private final AlertIntegrationService alertIntegrations;
    private final ScheduleExecutionPoller schedules;
    private final PersistentJobQueueService jobQueue;
    private final PeerHealthMonitor peerHealth;
    private final SloTrackingService slo;
    private final BatchReasoningQueueService batchReasoning;
    private final MemoryConsolidationWorker memory;
    private final EphemeralSwarmContext swarm;

    public SchedulerTestSupport(ApprovalService approvals,
                                DataRetentionService retention,
                                AlertingService alerting,
                                AlertIntegrationService alertIntegrations,
                                ScheduleExecutionPoller schedules,
                                PersistentJobQueueService jobQueue,
                                PeerHealthMonitor peerHealth,
                                SloTrackingService slo,
                                BatchReasoningQueueService batchReasoning,
                                MemoryConsolidationWorker memory,
                                EphemeralSwarmContext swarm) {
        this.approvals = approvals;
        this.retention = retention;
        this.alerting = alerting;
        this.alertIntegrations = alertIntegrations;
        this.schedules = schedules;
        this.jobQueue = jobQueue;
        this.peerHealth = peerHealth;
        this.slo = slo;
        this.batchReasoning = batchReasoning;
        this.memory = memory;
        this.swarm = swarm;
    }

    public void tickApprovalSlaCheck() {
        approvals.checkApprovalSla();
    }

    public void tickApprovalCleanup() {
        approvals.expireStaleApprovals();
    }

    public void tickDataRetention() {
        retention.enforceRetentionPolicies();
    }

    public void tickAlerting() {
        alerting.evaluateRules();
    }

    public void tickAlertRetry() {
        alertIntegrations.redispatchPendingFailures();
    }

    public void tickSchedulePoll() {
        schedules.evaluateSchedules();
    }

    public void tickJobQueue() {
        jobQueue.processQueue();
    }

    public void tickPeerHealth() {
        peerHealth.checkPeerHealth();
    }

    public void tickSloEvaluation() {
        slo.evaluateSlos();
    }

    public void tickBatchReasoning() {
        batchReasoning.processBatch();
    }

    public void tickMemoryConsolidation() {
        memory.processPendingMemoryExtractions();
    }

    public void tickSwarmCleanup() {
        swarm.evictStaleContexts();
    }
}
